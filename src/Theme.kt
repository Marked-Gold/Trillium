import korlibs.image.color.*
import korlibs.io.async.ObservableProperty

/**
 * Color themes (issue: player-configurable coloring).
 *
 * Every color the game draws is sourced from the *active* [Palette] rather than a hard-coded
 * constant, so switching theme re-tints the whole board, scoreboard, background and power-ups.
 *
 * Hard constraint: the SHARE feature renders the board as colored-square emoji ([Rank.emoji],
 * e.g. 🟩🟥🟦), so each theme must keep every tile inside its emoji's color *family* — green stays
 * green, red stays red, black stays dark, white stays light — so a shared grid still matches what
 * is on screen. The four palettes below are authored with that in mind.
 *
 * The names of every field here mirror the legacy top-level color names in [Colors.kt]/[Rank.kt];
 * those are now thin getters delegating to [currentPalette], which keeps all ~120 call sites
 * untouched while making the colors swappable.
 */
enum class ColorTheme(val displayName: String) {
    BASIC("BASIC"),
    PASTEL("PASTEL"),
    NEON("NEON"),
    DARK("DARK"),
}

/** Observable so views can [ObservableProperty.observe] it and recolor themselves live. */
val colorTheme = ObservableProperty(ColorTheme.BASIC)

/** Storage key for the chosen theme (persisted as [ColorTheme.name]). */
const val colorThemeKey = "colorTheme"

/** The full set of colors for one theme. Field names match the legacy color constants. */
class Palette(
    // Per-tile fills + text, indexed by Rank.ordinal (20 entries each).
    val rankFill: Array<RGBA>,
    val rankText: Array<RGBA>,
    val finalColor: RGBA,
    // Background vertical gradient (top + middle; the bottom tracks the highest forged tier color).
    val gradTop: RGBA,
    val gradMid: RGBA,
    // Playing field + cells.
    val gameFieldColor: RGBA,
    val grayedGameFieldColor: RGBA,
    val cellColor: RGBA,
    // Scoreboard / pause button.
    val restartAndScoreColor: RGBA,
    val scoreTextColor: RGBA,
    // Power-up tray backdrops.
    val bombContainerColor: RGBA,
    val rocketContainerColor: RGBA,
    // Inventory cartridges.
    val emptyCartridgeColor: RGBA,
    val cartridgeBorderColor: RGBA,
    val loadedBombCartridgeColor: RGBA,
    val loadedRocketCartridgeColor: RGBA,
    // Pause / settings menu chrome.
    val pauseScreenBlockColor: RGBA,
    val pauseScreenBlockCopiedColor: RGBA,
    val pauseScreenTextColor: RGBA,
    val pauseScreenTextHoverColor: RGBA,
    val pauseScreenTextDownColor: RGBA,
    // Default ink for the vector UI icons.
    val iconInk: RGBA,
    // Pattern-merge border palette.
    val patternBorderOptions: Array<RGBA>,
    // Themed red accent for the rocket icon (nose cone / fins / ring tint).
    val rocketAccent: RGBA,
)

/** Perceived luminance 0..1, used to choose a readable ink over a fill. */
private fun lum(c: RGBA): Double = (0.299 * c.r + 0.587 * c.g + 0.114 * c.b) / 255.0

/** Picks the dark or light ink for [fill] so tile numbers stay legible. */
private fun autoText(fill: RGBA, darkInk: RGBA, lightInk: RGBA): RGBA =
    if (lum(fill) > 0.6) darkInk else lightInk

private fun textsFor(fills: Array<RGBA>, darkInk: RGBA, lightInk: RGBA): Array<RGBA> =
    Array(fills.size) { autoText(fills[it], darkInk, lightInk) }

/**
 * A high-contrast ink (near-black or near-white) for text sitting directly on [fill], picked by the
 * fill's own luminance rather than the active theme. Used where a label sits on a themed accent
 * fill — e.g. the START button on the mode-switch dialog, whose green is bright in NEON/PASTEL but
 * dark in the DARK theme, so neither a fixed light nor a fixed dark label stays legible everywhere.
 *
 * The dark ink is a neutral near-black rather than a green-tinted one: every fill this runs on is
 * the "copied" green, and a dark *green* ink on the bright NEON copied fill (#1bff9e) reads as the
 * same hue — legible by luminance but muddy. A neutral near-black keeps "COPIED" crisply distinct.
 */
fun contrastInkOn(fill: RGBA): RGBA = if (lum(fill) > 0.55) Colors["#0A0A12"] else Colors["#F2F2F2"]

// --- Per-tile fills, ordered by Rank.ordinal --------------------------------
// Each column keeps every tile inside its emoji's color family (see class doc). The first column
// (BASIC) reproduces the exact hex values previously hard-coded on the Rank enum.
//                                  emoji family
private val basicFills = arrayOf(
    Colors["#3F373D"], // 0 ZERO    black ⬛
    Colors["#F3EAA0"], // 1 ONE     yellow 🟨
    Colors["#8B5A2B"], // 2 TWO     brown 🟫
    Colors["#0C6408"], // 3 THREE   green 🟩
    Colors["#8150C3"], // 4 FOUR    purple 🟪
    Colors["#A71D31"], // 5 FIVE    red 🟥
    Colors["#9ECDD3"], // 6 SIX     blue 🟦
    Colors["#FFFFFF"], // 7 SEVEN   white ⬜
    Colors["#FF4500"], // 8 EIGHT   orange 🟧
    Colors["#FFE14D"], // 9 NINE    yellow 💛
    Colors["#08A255"], // 10 TEN    green 💚
    Colors["#4A4A4A"], // 11 ELEVEN black 🖤
    Colors["#8B4513"], // 12 TWELVE brown 🤎
    Colors["#6495ED"], // 13 THIRTEEN blue 💙
    Colors["#FF8FAB"], // 14 FOURTEEN pink 🩷
    Colors["#F0F0F0"], // 15 FIFTEEN white 🤍
    Colors["#9B30FF"], // 16 SIXTEEN purple 💜
    Colors["#FFD700"], // 17 SEVENTEEN gold 🏆
    Colors["#4B0082"], // 18 EIGHTEEN purple 👑
    Colors["#7B3F05"], // 19 NINETEEN amber 💯
)
private val basicTexts = arrayOf(
    Colors["#FFFFFF"], Colors["#150A19"], Colors["#FFFFFF"], Colors["#FFFFFF"], Colors["#FFFBFA"],
    Colors["#FFECCE"], Colors["#220D00"], Colors["#0A0810"], Colors["#FFFFFF"], Colors["#3D3300"],
    Colors["#FFFFFF"], Colors["#FFFFFF"], Colors["#FFFFFF"], Colors["#FFFFFF"], Colors["#2A0A14"],
    Colors["#010101"], Colors["#FFFFFF"], Colors["#3D2A00"], Colors["#FFFFFF"], Colors["#FFFFFF"],
)

private val pastelFills = arrayOf(
    Colors["#6B6470"], // black ⬛ (kept dark)
    Colors["#FBF3C0"], // yellow 🟨
    Colors["#C9A37A"], // brown 🟫
    Colors["#A8E6A1"], // green 🟩
    Colors["#C9A8E9"], // purple 🟪
    Colors["#F4A6A6"], // red 🟥
    Colors["#AECBFA"], // blue 🟦
    Colors["#FFF6FB"], // white ⬜ (kept light)
    Colors["#FFC79A"], // orange 🟧
    Colors["#F0DD7A"], // yellow 💛
    Colors["#A6E6C3"], // green 💚
    Colors["#7A7580"], // black 🖤 (kept dark)
    Colors["#CBA079"], // brown 🤎
    Colors["#B3CBF5"], // blue 💙
    Colors["#FFC2D4"], // pink 🩷
    Colors["#FBFBFD"], // white 🤍 (kept light)
    Colors["#D2B3F2"], // purple 💜
    Colors["#FBE9A0"], // gold 🏆
    Colors["#B79CD9"], // purple 👑
    Colors["#D9A36B"], // amber 💯
)

private val neonFills = arrayOf(
    Colors["#1A1A22"], // black ⬛ (kept dark)
    Colors["#FFE600"], // yellow 🟨
    Colors["#C9742E"], // brown 🟫
    Colors["#00FF85"], // green 🟩
    Colors["#B026FF"], // purple 🟪
    Colors["#FF1E56"], // red 🟥
    Colors["#1FB6FF"], // blue 🟦
    Colors["#FFFFFF"], // white ⬜ (kept light)
    Colors["#FF7A00"], // orange 🟧
    Colors["#EAFF00"], // yellow 💛
    Colors["#1BFF9E"], // green 💚
    Colors["#26262E"], // black 🖤 (kept dark)
    Colors["#C56A1E"], // brown 🤎
    Colors["#2D9BFF"], // blue 💙
    Colors["#FF5FA2"], // pink 🩷
    Colors["#FFFFFF"], // white 🤍 (kept light)
    Colors["#C13CFF"], // purple 💜
    Colors["#FFE000"], // gold 🏆
    Colors["#8A2BE2"], // purple 👑
    Colors["#FF8C1A"], // amber 💯
)

private val darkFills = arrayOf(
    Colors["#1C1A20"], // black ⬛ (kept dark)
    Colors["#C9B83A"], // yellow 🟨
    Colors["#5E3D1C"], // brown 🟫
    Colors["#1E5E2A"], // green 🟩
    Colors["#5B2E8A"], // purple 🟪
    Colors["#7A1626"], // red 🟥
    Colors["#2C5C8A"], // blue 🟦
    Colors["#E8E6EC"], // white ⬜ (kept light)
    Colors["#9E3B12"], // orange 🟧
    Colors["#B8A82C"], // yellow 💛
    Colors["#1B6E45"], // green 💚
    Colors["#2A2A30"], // black 🖤 (kept dark)
    Colors["#5A3110"], // brown 🤎
    Colors["#34568F"], // blue 💙
    Colors["#B5566E"], // pink 🩷
    Colors["#D8D8DE"], // white 🤍 (kept light)
    Colors["#6A1FB0"], // purple 💜
    Colors["#C7A700"], // gold 🏆
    Colors["#3A0A66"], // purple 👑
    Colors["#5E2F04"], // amber 💯
)

private val basicPalette = Palette(
    rankFill = basicFills,
    rankText = basicTexts,
    finalColor = Colors["#ffa96a"],
    gradTop = Colors["#96B2C4"],
    gradMid = Colors["#F4E6D4"],
    gameFieldColor = Colors["#e0d8e822"],
    grayedGameFieldColor = Colors["#aaa6a4cc"],
    cellColor = Colors["#cec0b250"],
    restartAndScoreColor = Colors["#a3d6b840"],
    scoreTextColor = Colors["#12291b"],
    bombContainerColor = Colors["#4f8fd140"],
    rocketContainerColor = Colors["#f8785540"],
    emptyCartridgeColor = Colors["#e6e6e6A0"],
    cartridgeBorderColor = Colors["#ffffff99"],
    loadedBombCartridgeColor = Colors["#1f3079"],
    loadedRocketCartridgeColor = Colors["#F87855"],
    pauseScreenBlockColor = Colors["#a3d6b8"],
    pauseScreenBlockCopiedColor = Colors["#6fcf97"],
    pauseScreenTextColor = Colors["#12291b"],
    pauseScreenTextHoverColor = Colors["#5A5A5A"],
    pauseScreenTextDownColor = Colors["#787878"],
    iconInk = Colors["#12291b"],
    patternBorderOptions = arrayOf(
        Colors["#F26419"], Colors["#86BBD8"], Colors["#33658A"], Colors["#B20D30"],
        Colors["#454372"], Colors["#C69DD2"], Colors["#F79F79"], Colors["#FDE12D"],
    ),
    rocketAccent = Colors["#e8503a"],
)

private val pastelPalette = Palette(
    rankFill = pastelFills,
    rankText = textsFor(pastelFills, darkInk = Colors["#2A2433"], lightInk = Colors["#FFFFFF"]),
    finalColor = Colors["#ffd0a8"],
    gradTop = Colors["#BFD9E6"],
    gradMid = Colors["#FBF1E2"],
    gameFieldColor = Colors["#ffffff22"],
    grayedGameFieldColor = Colors["#f3eef0cc"],
    cellColor = Colors["#ffffff50"],
    restartAndScoreColor = Colors["#cfe8d840"],
    scoreTextColor = Colors["#5a5560"],
    bombContainerColor = Colors["#aecbfa40"],
    rocketContainerColor = Colors["#ffc2d440"],
    emptyCartridgeColor = Colors["#f0eef2A0"],
    cartridgeBorderColor = Colors["#ffffffcc"],
    loadedBombCartridgeColor = Colors["#7fa8e0"],
    loadedRocketCartridgeColor = Colors["#ff9aa8"],
    pauseScreenBlockColor = Colors["#cfe8d8"],
    pauseScreenBlockCopiedColor = Colors["#a8e6c0"],
    pauseScreenTextColor = Colors["#5a5560"],
    pauseScreenTextHoverColor = Colors["#8a8590"],
    pauseScreenTextDownColor = Colors["#a8a3ad"],
    iconInk = Colors["#5a5560"],
    patternBorderOptions = arrayOf(
        Colors["#F7B267"], Colors["#A9D6E5"], Colors["#7AA5C9"], Colors["#E89BA8"],
        Colors["#9C8FC4"], Colors["#D9BCE6"], Colors["#FBC4AB"], Colors["#FCE79E"],
    ),
    rocketAccent = Colors["#ff9a8a"],
)

private val neonPalette = Palette(
    rankFill = neonFills,
    rankText = textsFor(neonFills, darkInk = Colors["#0A0A12"], lightInk = Colors["#FFFFFF"]),
    finalColor = Colors["#ff9a3c"],
    gradTop = Colors["#1A2A3A"],
    gradMid = Colors["#221833"],
    gameFieldColor = Colors["#10101a30"],
    grayedGameFieldColor = Colors["#14141ee0"],
    cellColor = Colors["#ffffff14"],
    restartAndScoreColor = Colors["#182038cc"],
    scoreTextColor = Colors["#8af7e0"],
    bombContainerColor = Colors["#1fb6ff40"],
    rocketContainerColor = Colors["#ff1e5640"],
    emptyCartridgeColor = Colors["#2a2a3aA0"],
    cartridgeBorderColor = Colors["#ffffffaa"],
    loadedBombCartridgeColor = Colors["#1fb6ff"],
    loadedRocketCartridgeColor = Colors["#ff2d95"],
    pauseScreenBlockColor = Colors["#1c2740"],
    pauseScreenBlockCopiedColor = Colors["#1bff9e"],
    pauseScreenTextColor = Colors["#8af7e0"],
    pauseScreenTextHoverColor = Colors["#c8fff0"],
    pauseScreenTextDownColor = Colors["#5fc7b4"],
    iconInk = Colors["#8af7e0"],
    patternBorderOptions = arrayOf(
        Colors["#FF1E56"], Colors["#1FB6FF"], Colors["#00FF85"], Colors["#B026FF"],
        Colors["#FFE600"], Colors["#FF7A00"], Colors["#FF2D95"], Colors["#2D9BFF"],
    ),
    rocketAccent = Colors["#ff2d95"],
)

private val darkPalette = Palette(
    rankFill = darkFills,
    rankText = textsFor(darkFills, darkInk = Colors["#14141A"], lightInk = Colors["#F0EEF5"]),
    finalColor = Colors["#c98a5a"],
    gradTop = Colors["#2C3038"],
    gradMid = Colors["#3A3540"],
    gameFieldColor = Colors["#1a1a2022"],
    grayedGameFieldColor = Colors["#20202ae0"],
    cellColor = Colors["#ffffff18"],
    restartAndScoreColor = Colors["#3a3a48cc"],
    scoreTextColor = Colors["#e8e6f0"],
    bombContainerColor = Colors["#2c5c8a40"],
    rocketContainerColor = Colors["#7a162640"],
    emptyCartridgeColor = Colors["#2e2e38a0"],
    cartridgeBorderColor = Colors["#ffffff99"],
    loadedBombCartridgeColor = Colors["#3a5a8a"],
    loadedRocketCartridgeColor = Colors["#b3324a"],
    pauseScreenBlockColor = Colors["#34343f"],
    pauseScreenBlockCopiedColor = Colors["#3d7a55"],
    pauseScreenTextColor = Colors["#e8e6f0"],
    pauseScreenTextHoverColor = Colors["#b0aec0"],
    pauseScreenTextDownColor = Colors["#8a8898"],
    iconInk = Colors["#e8e6f0"],
    patternBorderOptions = arrayOf(
        Colors["#C0563E"], Colors["#5A8AB0"], Colors["#3E6E8A"], Colors["#8A2E40"],
        Colors["#5A5878"], Colors["#9A7FB0"], Colors["#C08A6A"], Colors["#C9B85A"],
    ),
    rocketAccent = Colors["#c0394f"],
)

/** Indexed by [ColorTheme.ordinal]. */
private val palettes: Array<Palette> = arrayOf(basicPalette, pastelPalette, neonPalette, darkPalette)

/** The active palette — a plain array index, cheap enough for per-draw color lookups. */
fun currentPalette(): Palette = palettes[colorTheme.value.ordinal]

/** A specific theme's palette, for previewing a theme without switching to it (the THEMES picker). */
fun paletteOf(theme: ColorTheme): Palette = palettes[theme.ordinal]
