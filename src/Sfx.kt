import korlibs.audio.sound.AudioData
import korlibs.audio.sound.PlaybackParameters
import korlibs.audio.sound.Sound
import korlibs.audio.sound.SoundAudioData
import korlibs.audio.sound.nativeSoundProvider
import korlibs.audio.sound.readSound
import korlibs.io.file.std.resourcesVfs
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.pow

/** Storage key for the SFX on/off preference. */
const val sfxEnabledKey = "sfxEnabled"

/**
 * Game-facing sound-effects facade, mirror of [Haptics]. Call sites stay terse — Sfx.merge(tier),
 * Sfx.bomb() — and a disabled flag or a sample that failed to load both fall back to no-op.
 *
 * korlibs 6.0 quirk: PlaybackParameters.pitch is stored on the SoundChannel but the platform audio
 * output is opened at the source AudioData's native rate, so the pitch value is silently ignored.
 * To actually pitch a sound we therefore ship the rate ourselves — see [playPitched] — by wrapping
 * the decoded PCM in a fresh AudioData whose rate field is multiplied by the desired pitch ratio.
 * Same samples, just told to render faster, which is what the ear hears as a higher pitch.
 */
object Sfx {
    /** Player setting, toggled from the pause menu. Persisted under [sfxEnabledKey]. */
    var enabled: Boolean = true

    private var ctx: CoroutineContext = EmptyCoroutineContext

    // Pitched sounds: hold the decoded PCM so each play can re-wrap it with a tier-adjusted rate.
    private var mergeData: AudioData? = null
    private var pulseData: AudioData? = null
    // Unpitched sounds: plain Sound is fine.
    private var bombSound: Sound? = null
    private var rocketSound: Sound? = null
    private var gameOverSound: Sound? = null
    private var mooSound: Sound? = null

    suspend fun load(coroutineContext: CoroutineContext) {
        ctx = coroutineContext
        mergeData = resourcesVfs["merge.wav"].readSound().toAudioData()
        pulseData = resourcesVfs["pulse.wav"].readSound().toAudioData()
        bombSound = resourcesVfs["bomb.wav"].readSound()
        rocketSound = resourcesVfs["rocket.wav"].readSound()
        gameOverSound = resourcesVfs["game-over.wav"].readSound()
        mooSound = resourcesVfs["moo.wav"].readSound()
    }

    // 2^(n/12) — one semitone per integer step.
    private fun semitones(n: Int): Double = 2.0.pow(n / 12.0)

    // Whole-step intervals between tiers so consecutive tiers are clearly distinguishable — a 81
    // forge sounds clearly different from a 243 forge, not a barely-perceptible semitone apart.
    private const val SEMITONES_PER_TIER = 2

    /** Pitch ceiling: tier TEN (59049) is the top of the climb; tiers beyond reuse it. */
    private val pitchCapTier: Int get() = Rank.TEN.ordinal

    private fun Sound.fire(volume: Double) {
        if (!enabled) return
        // Sound is decoration: a platform audio failure must never escape into the caller (on
        // iOS these fire from frame-loop animation callbacks, where a throw kills the process).
        try {
            play(ctx, PlaybackParameters.DEFAULT.copy(volume = volume))
        } catch (e: Throwable) {
            Napier.e("Sfx: failed to play '$name': $e")
        }
    }

    // Plays [this] AudioData at a modified sample rate so it sounds pitched. The samples reference
    // is reused — only the rate field changes — so per-play allocation is cheap.
    private fun AudioData.playPitched(pitch: Double, volume: Double) {
        if (!enabled) return
        try {
            val pitched = AudioData(
                rate = (rate * pitch).toInt().coerceAtLeast(1),
                samples = samples,
                name = "${name}_pitched",
            )
            SoundAudioData(ctx, pitched, nativeSoundProvider, "${name}_pitched")
                .play(ctx, PlaybackParameters.DEFAULT.copy(volume = volume))
        } catch (e: Throwable) {
            Napier.e("Sfx: failed to play pitched '$name': $e")
        }
    }

    /** Soft pop on every merge. Two semitones per tier from tier ONE; capped at tier TEN. */
    fun merge(tier: Rank) {
        val capped = tier.ordinal.coerceAtMost(pitchCapTier)
        mergeData?.playPitched(pitch = semitones(capped * SEMITONES_PER_TIER), volume = 0.6)
    }

    /** Singing-bowl swell on every 81+ forge. Two semitones per tier above FOUR, capped at TEN —
     * tier TEN's pulse plays exactly one octave above tier FOUR's. */
    fun pulse(tier: Rank) {
        val above = (tier.ordinal - Rank.FOUR.ordinal)
            .coerceIn(0, pitchCapTier - Rank.FOUR.ordinal)
        pulseData?.playPitched(pitch = semitones(above * SEMITONES_PER_TIER), volume = 0.9)
    }

    /** Muffled boom when a bomb detonates. */
    fun bomb() {
        bombSound?.fire(volume = 0.8)
    }

    /** Quick swap whoosh when a rocket fires. Source peaks near -0.5 dBFS (mastered hot), so a
     * sub-0.2× multiplier is needed to bring the perceived level under the merge pop and pulse. */
    fun rocket() {
        rocketSound?.fire(volume = 0.12)
    }

    /** Descending sigh played alongside the board-shake when the game ends. */
    fun gameOver() {
        gameOverSound?.fire(volume = 0.85)
    }

    /** Studio "moo" during the boot intro. Source is mastered hot (peaks at 0 dBFS, mean -9.9 dB),
     * so a heavy 0.05× knock-down is needed for a reasonable boot-screen level. */
    fun moo() {
        mooSound?.fire(volume = 0.05)
    }
}
