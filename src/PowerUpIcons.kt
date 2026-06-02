import korlibs.image.color.*
import korlibs.image.paint.*
import korlibs.korge.view.*
import korlibs.math.geom.*
import korlibs.math.geom.vector.*
import korlibs.time.*
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Vector-drawn power-up icons (bomb, rocket) with a live "armed" animation.
 *
 * Like [Icons.kt] these are built from KorGE vector shapes so they stay crisp at any
 * resolution, replacing the old bomb/rocket PNG assets. Gradient fills give the body
 * shapes volume (a spherical bomb, a cylindrical rocket).
 *
 * Each icon takes an [isActive] probe that it polls every frame:
 *  - the bomb lights its fuse — a flickering spark with drifting embers — and the lit
 *    fuse waves back and forth, carrying the flame with it;
 *  - the rocket fires its engine — it lifts up out of the cell, billows a flame and
 *    sprays offshoot exhaust flames, with a faint chassis vibration.
 *
 * Effect views (spark, embers, flame, particles) start hidden so the one-off
 * `centerOn` at creation time measures only the static `s x s` icon box.
 */

// Fire colours — shared by the bomb spark/embers and the rocket flame/exhaust.
private val fireOuter = Colors["#ff5a1e"]
private val fireMid = Colors["#ffa12e"]
private val fireCore = Colors["#fff2c4"]
private val emberColor = Colors["#ffd27a"]

private const val EMBER_COUNT = 6
private const val EXHAUST_COUNT = 8

// The rocket's red accent (nose cone, fins) is themed; the light/dark gradient stops are derived
// from the base by blending toward white / black so any theme accent yields a coherent shaded part.
private fun RGBA.mix(o: RGBA, t: Double): RGBA = RGBA(
    (r + (o.r - r) * t).roundToInt().coerceIn(0, 255),
    (g + (o.g - g) * t).roundToInt().coerceIn(0, 255),
    (b + (o.b - b) * t).roundToInt().coerceIn(0, 255),
    255,
)

/**
 * Appends a flame outline to the current path: a rounded top lobe centred on
 * ([cx], [cy]) that tapers to a point [len] away. [dir] is +1 to point down
 * (rocket exhaust) or -1 to point up (a burning fuse).
 */
private fun VectorBuilder.flameShape(
    cx: Double,
    cy: Double,
    w: Double,
    len: Double,
    dir: Double,
) {
    moveTo(cx - w, cy)
    quadTo(cx - w * 0.55, cy - dir * w * 0.85, cx, cy - dir * w * 0.45)
    quadTo(cx + w * 0.55, cy - dir * w * 0.85, cx + w, cy)
    quadTo(cx + w * 0.5, cy + dir * len * 0.55, cx, cy + dir * len)
    quadTo(cx - w * 0.5, cy + dir * len * 0.55, cx - w, cy)
    close()
}

/**
 * Bomb power-up icon. When loaded and [isActive] the fuse tip lights: a pulsing
 * spark flares at the end of the fuse, embers drift up off it, and the whole fuse
 * (with its flame) waves back and forth.
 */
class BombIcon(
    private val s: Double,
    private val loaded: Boolean,
    private val isActive: () -> Boolean,
) : Container() {

    // The fuse pivots on the point where it leaves the cap.
    private val fuseBaseX = s * 0.47
    private val fuseBaseY = s * 0.110
    // Resting spark position (the fuse tip), and the same point in fuse-local space.
    private val sparkX = s * 0.67
    private val sparkY = s * 0.060
    private val tipLocalX = sparkX - fuseBaseX
    private val tipLocalY = sparkY - fuseBaseY

    private val fuseGroup: Container
    private val spark: View
    private val embers = ArrayList<View>(EMBER_COUNT)
    private val emberLife = DoubleArray(EMBER_COUNT)
    private val emberMaxLife = DoubleArray(EMBER_COUNT)
    private val emberVX = DoubleArray(EMBER_COUNT)
    private val emberVY = DoubleArray(EMBER_COUNT)

    private var time = 0.0
    private var wasOn = false
    private var swayAmp = 0.0

    init {
        if (!loaded) alpha = 0.6

        // Invisible square pins the bounds to an s x s box for a stable centerOn().
        solidRect(s, s, RGBA(0, 0, 0, 0))

        val cx = fuseBaseX
        val cy = s * 0.540
        val r = s * 0.31

        // Spherical body shading: a highlight offset toward the upper-left.
        val bodyPaint = RadialGradientPaint(cx - r * 0.32, cy - r * 0.36, 0.0, cx, cy, r * 1.15) {
            if (loaded) {
                addColorStop(0.0, Colors["#70707e"])
                addColorStop(0.4, Colors["#2c2c35"])
                addColorStop(1.0, Colors["#0a0a0e"])
            } else {
                addColorStop(0.0, Colors["#dcdce0"])
                addColorStop(0.5, Colors["#bcbcc1"])
                addColorStop(1.0, Colors["#909096"])
            }
        }
        val capDark = if (loaded) Colors["#2a2a32"] else Colors["#a8a8ad"]
        val capLight = if (loaded) Colors["#4a4a55"] else Colors["#c6c6ca"]
        val fuse = if (loaded) Colors["#9c7b4e"] else Colors["#bcbcbf"]
        val fuseLight = if (loaded) Colors["#c8ab78"] else Colors["#d2d2d4"]

        // Fuse group: the fuse and its spark, both drawn in fuse-local space so the
        // group can rotate about the fuse base to wave the fuse back and forth.
        fuseGroup = container()
        fuseGroup.xy(fuseBaseX, fuseBaseY)
        fuseGroup.graphics {
            stroke(fuse, lineWidth = s * 0.058, lineCap = LineCap.ROUND) {
                moveTo(0.0, 0.0)
                quadTo(s * 0.28, -s * 0.125, tipLocalX, tipLocalY)
            }
            stroke(fuseLight, lineWidth = s * 0.022, lineCap = LineCap.ROUND) {
                moveTo(0.0, 0.0)
                quadTo(s * 0.28, -s * 0.125, tipLocalX, tipLocalY)
            }
        }
        // Spark: stacked flame lobes pointing up, drawn around the origin so the
        // flicker scaling pivots on the fuse tip.
        spark = fuseGroup.graphics {
            fill(fireOuter) { flameShape(0.0, 0.0, s * 0.10, s * 0.21, -1.0) }
            fill(fireMid) { flameShape(0.0, 0.0, s * 0.068, s * 0.15, -1.0) }
            fill(fireCore) { flameShape(0.0, 0.0, s * 0.038, s * 0.09, -1.0) }
        }
        spark.xy(tipLocalX, tipLocalY)
        spark.visible = false

        // Body, cap and gloss — drawn after the fuse so the cap covers the fuse base.
        graphics {
            // Collar cap: a dark base with a lighter top edge so it reads as metal.
            // Both layers are centred on the body's x so the cap sits squarely on top.
            fill(capDark) {
                roundRect(cx - s * 0.11, s * 0.100, s * 0.22, s * 0.135, s * 0.04, s * 0.04)
            }
            fill(capLight) {
                roundRect(cx - s * 0.09, s * 0.085, s * 0.18, s * 0.055, s * 0.025, s * 0.025)
            }
            // Bomb body.
            fill(bodyPaint) {
                circle(Point(cx, cy), r)
            }
            // Glossy highlight: a soft sheen plus a sharp specular dot.
            fill(RGBA(255, 255, 255, 70)) {
                circle(Point(cx - r * 0.33, cy - r * 0.40), r * 0.27)
            }
            fill(RGBA(255, 255, 255, 205)) {
                circle(Point(cx - r * 0.42, cy - r * 0.46), r * 0.085)
            }
        }

        // Embers: small dots recycled as they rise off the spark and fade.
        for (i in 0 until EMBER_COUNT) {
            val ember = graphics {
                fill(emberColor) { circle(Point(0.0, 0.0), s * 0.025) }
            }
            ember.visible = false
            embers.add(ember)
            respawnEmber(i)
        }

        addUpdater { dt -> update(dt.seconds) }
    }

    private fun respawnEmber(i: Int) {
        embers[i].xy(
            sparkX + Random.nextDouble(-1.0, 1.0) * s * 0.03,
            sparkY + Random.nextDouble(-1.0, 1.0) * s * 0.02,
        )
        emberMaxLife[i] = Random.nextDouble(0.4, 0.9)
        emberLife[i] = emberMaxLife[i]
        emberVX[i] = Random.nextDouble(-1.0, 1.0) * s * 0.25
        emberVY[i] = -Random.nextDouble(s * 0.6, s * 1.3)
    }

    private fun update(rawDt: Double) {
        val dt = rawDt.coerceAtMost(0.05)
        val on = loaded && isActive()
        spark.visible = on
        time += dt

        // Fuse wave: ease the sway amplitude in when lit and out when not, so the
        // fuse rocks gently back and forth.
        val targetAmp = if (on) 9.0 else 0.0
        swayAmp += (targetAmp - swayAmp) * (1.0 - exp(-9.0 * dt))
        val fuseAngle = swayAmp * sin(time * 6.0)
        fuseGroup.rotation = fuseAngle.degrees
        // The flame rides the fuse tip but tilts only partway with it — a real flame
        // tends to stay upright — so counter-rotate the spark against its parent.
        spark.rotation = (-0.6 * fuseAngle).degrees

        if (!on) {
            wasOn = false
            embers.forEach { it.visible = false }
            return
        }
        // On the rising edge stagger every ember so they do not all spawn at once.
        if (!wasOn) {
            wasOn = true
            for (i in embers.indices) {
                respawnEmber(i)
                emberLife[i] = Random.nextDouble(0.0, emberMaxLife[i])
            }
        }

        // Flicker: a fast pulse plus per-frame jitter in scale, brightness and position.
        spark.scale = 1.0 + 0.16 * sin(time * 22.0) + Random.nextDouble(-0.07, 0.07)
        spark.alpha = 0.82 + 0.18 * (0.5 + 0.5 * sin(time * 13.0))
        spark.xy(
            tipLocalX + Random.nextDouble(-1.0, 1.0) * s * 0.012,
            tipLocalY + Random.nextDouble(-1.0, 1.0) * s * 0.012,
        )

        for (i in embers.indices) {
            emberLife[i] -= dt
            if (emberLife[i] <= 0.0) respawnEmber(i)
            val ember = embers[i]
            ember.visible = true
            ember.x += emberVX[i] * dt
            ember.y += emberVY[i] * dt
            val lifeFrac = (emberLife[i] / emberMaxLife[i]).coerceIn(0.0, 1.0)
            ember.alpha = lifeFrac
            ember.scale = 0.35 + 0.65 * lifeFrac
        }
    }
}

/**
 * Rocket power-up icon. It sits centred in its cell at rest; when loaded and
 * [isActive] the engine ignites — the rocket lifts up, billows a flame, sprays
 * offshoot exhaust flames and the chassis vibrates faintly.
 */
class RocketIcon(
    private val s: Double,
    private val loaded: Boolean,
    // Themed red accent for the nose cone + fins; defaults to the Basic theme's mid-red so the
    // un-themed look is unchanged. Light/dark stops are derived from this via [mix].
    private val accent: RGBA = Colors["#e8503a"],
    private val isActive: () -> Boolean,
) : Container() {

    // The chassis (everything but the exhaust) is grouped so the engine
    // vibration and the lift-off offset can move the whole rocket at once.
    private val chassis: Container
    private val flame: View

    // Offshoot exhaust flames — small dots sprayed out of the nozzle. They live on
    // the icon (not the chassis) so they trail behind as the rocket climbs away.
    private val exhaust = ArrayList<View>(EXHAUST_COUNT)
    private val exhaustLife = DoubleArray(EXHAUST_COUNT)
    private val exhaustMaxLife = DoubleArray(EXHAUST_COUNT)
    private val exhaustVX = DoubleArray(EXHAUST_COUNT)
    private val exhaustVY = DoubleArray(EXHAUST_COUNT)

    private val nozzleX = s * 0.5
    private val nozzleY = s * 0.715
    private val liftAmount = s * 0.17
    // Resting offset that drops the chassis so the rocket sits centred in its cell;
    // the engine lifts it up from here when fired.
    private val restY = s * 0.18

    private var time = 0.0
    private var liftCurrent = 0.0
    private var wasOn = false

    init {
        if (!loaded) alpha = 0.6

        // Invisible square pins the bounds to an s x s box for a stable centerOn().
        solidRect(s, s, RGBA(0, 0, 0, 0))

        // Cylindrical shading: a left-to-right gradient down each part.
        val shellPaint = LinearGradientPaint(s * 0.335, 0.0, s * 0.665, 0.0) {
            if (loaded) {
                addColorStop(0.0, Colors["#ffffff"])
                addColorStop(0.42, Colors["#eaecf1"])
                addColorStop(1.0, Colors["#b4b7c2"])
            } else {
                addColorStop(0.0, Colors["#d6d6da"])
                addColorStop(0.42, Colors["#c3c3c8"])
                addColorStop(1.0, Colors["#a0a0a6"])
            }
        }
        val accentPaint = LinearGradientPaint(s * 0.335, 0.0, s * 0.665, 0.0) {
            if (loaded) {
                addColorStop(0.0, accent.mix(Colors.WHITE, 0.35))
                addColorStop(0.45, accent)
                addColorStop(1.0, accent.mix(Colors.BLACK, 0.30))
            } else {
                addColorStop(0.0, Colors["#bdbdc2"])
                addColorStop(0.45, Colors["#a9a9ae"])
                addColorStop(1.0, Colors["#86868b"])
            }
        }
        val nozzlePaint = LinearGradientPaint(s * 0.42, 0.0, s * 0.58, 0.0) {
            if (loaded) {
                addColorStop(0.0, Colors["#7a7a88"])
                addColorStop(0.5, Colors["#4a4a55"])
                addColorStop(1.0, Colors["#26262d"])
            } else {
                addColorStop(0.0, Colors["#b0b0b5"])
                addColorStop(0.5, Colors["#8e8e94"])
                addColorStop(1.0, Colors["#6c6c72"])
            }
        }
        val finColor = if (loaded) accent.mix(Colors.BLACK, 0.18) else Colors["#94949a"]
        val glassPaint = RadialGradientPaint(s * 0.478, s * 0.392, 0.0, s * 0.5, s * 0.415, s * 0.085) {
            if (loaded) {
                addColorStop(0.0, Colors["#e4f5fc"])
                addColorStop(0.55, Colors["#5fb6dd"])
                addColorStop(1.0, Colors["#2c7ba6"])
            } else {
                addColorStop(0.0, Colors["#dadadd"])
                addColorStop(0.55, Colors["#bcbcc1"])
                addColorStop(1.0, Colors["#9a9aa0"])
            }
        }
        val ringColor = if (loaded) Colors["#ffffff"] else Colors["#dededf"]

        chassis = container()
        chassis.y = restY

        // Flame: stacked lobes pointing down, drawn around the origin so the
        // flicker scaling pivots on the nozzle. Behind the body so its top lobe
        // tucks under the nozzle.
        flame = chassis.graphics {
            fill(fireOuter) { flameShape(0.0, 0.0, s * 0.135, s * 0.30, 1.0) }
            fill(fireMid) { flameShape(0.0, 0.0, s * 0.092, s * 0.22, 1.0) }
            fill(fireCore) { flameShape(0.0, 0.0, s * 0.05, s * 0.13, 1.0) }
        }
        flame.xy(nozzleX, nozzleY)
        flame.visible = false

        chassis.graphics {
            // Fins (behind the body).
            fill(finColor) {
                moveTo(s * 0.335, s * 0.47)
                lineTo(s * 0.225, s * 0.675)
                lineTo(s * 0.335, s * 0.605)
                close()
                moveTo(s * 0.665, s * 0.47)
                lineTo(s * 0.775, s * 0.675)
                lineTo(s * 0.665, s * 0.605)
                close()
            }
            // Exhaust nozzle (behind the body).
            fill(nozzlePaint) {
                moveTo(s * 0.42, s * 0.625)
                lineTo(s * 0.58, s * 0.625)
                lineTo(s * 0.545, s * 0.715)
                lineTo(s * 0.455, s * 0.715)
                close()
            }
            // Body shell.
            fill(shellPaint) {
                roundRect(s * 0.335, s * 0.30, s * 0.33, s * 0.33, s * 0.045, s * 0.045)
            }
            // Nose cone.
            fill(accentPaint) {
                moveTo(s * 0.5, s * 0.045)
                quadTo(s * 0.665, s * 0.13, s * 0.665, s * 0.34)
                lineTo(s * 0.335, s * 0.34)
                quadTo(s * 0.335, s * 0.13, s * 0.5, s * 0.045)
                close()
            }
            // Lower colour band.
            fill(accentPaint) {
                roundRect(s * 0.335, s * 0.515, s * 0.33, s * 0.105, s * 0.03, s * 0.03)
            }
            // Window: glass, white ring and a small glint.
            fill(glassPaint) { circle(Point(s * 0.5, s * 0.415), s * 0.072) }
            stroke(ringColor, lineWidth = s * 0.024) {
                circle(Point(s * 0.5, s * 0.415), s * 0.072)
            }
            fill(RGBA(255, 255, 255, 180)) {
                circle(Point(s * 0.472, s * 0.388), s * 0.022)
            }
        }

        // Offshoot exhaust flames.
        for (i in 0 until EXHAUST_COUNT) {
            val color = if (i % 2 == 0) fireMid else emberColor
            val particle = graphics {
                fill(color) { circle(Point(0.0, 0.0), s * 0.03) }
            }
            particle.visible = false
            exhaust.add(particle)
            respawnExhaust(i)
        }

        addUpdater { dt -> update(dt.seconds) }
    }

    private fun respawnExhaust(i: Int) {
        // Emit from the current nozzle position so particles trail as the rocket climbs.
        exhaust[i].xy(
            nozzleX + chassis.x + Random.nextDouble(-1.0, 1.0) * s * 0.05,
            nozzleY + chassis.y + Random.nextDouble(0.0, s * 0.04),
        )
        exhaustMaxLife[i] = Random.nextDouble(0.25, 0.6)
        exhaustLife[i] = exhaustMaxLife[i]
        exhaustVX[i] = Random.nextDouble(-1.0, 1.0) * s * 0.55
        exhaustVY[i] = Random.nextDouble(s * 0.5, s * 1.2)
    }

    private fun update(rawDt: Double) {
        val dt = rawDt.coerceAtMost(0.05)
        val on = loaded && isActive()
        flame.visible = on
        time += dt

        // Lift-off: ease the chassis up off its resting (centred) position when fired
        // and let it settle back down when deselected.
        val targetLift = if (on) -liftAmount else 0.0
        liftCurrent += (targetLift - liftCurrent) * (1.0 - exp(-11.0 * dt))
        val vibX = if (on) Random.nextDouble(-1.0, 1.0) * s * 0.014 else 0.0
        val vibY = if (on) Random.nextDouble(-1.0, 1.0) * s * 0.010 else 0.0
        chassis.xy(vibX, restY + liftCurrent + vibY)

        if (on) {
            // Billowing flame: a fast length pulse with a slower width sway, plus jitter.
            flame.scaleY = 1.0 + 0.30 * sin(time * 30.0) + Random.nextDouble(-0.18, 0.18)
            flame.scaleX = 1.0 + 0.12 * sin(time * 19.0) + Random.nextDouble(-0.06, 0.06)
            flame.alpha = 0.78 + 0.22 * (0.5 + 0.5 * sin(time * 24.0))
        }

        // On the rising edge stagger every particle so they do not all spawn at once.
        if (on && !wasOn) {
            for (i in exhaust.indices) {
                respawnExhaust(i)
                exhaustLife[i] = Random.nextDouble(0.0, exhaustMaxLife[i])
            }
        }
        wasOn = on

        for (i in exhaust.indices) {
            exhaustLife[i] -= dt
            if (exhaustLife[i] <= 0.0) {
                if (on) {
                    respawnExhaust(i)
                } else {
                    exhaust[i].visible = false
                    continue
                }
            }
            val particle = exhaust[i]
            particle.visible = true
            particle.x += exhaustVX[i] * dt
            particle.y += exhaustVY[i] * dt
            exhaustVX[i] *= 0.95 // slight drag, so the spray narrows as it falls
            val lifeFrac = (exhaustLife[i] / exhaustMaxLife[i]).coerceIn(0.0, 1.0)
            particle.alpha = lifeFrac
            particle.scale = 0.3 + 0.7 * lifeFrac
        }
    }
}

fun Container.bombIcon(
    s: Double,
    loaded: Boolean,
    isActive: () -> Boolean,
): BombIcon = BombIcon(s, loaded, isActive).addTo(this)

fun Container.rocketIcon(
    s: Double,
    loaded: Boolean,
    accent: RGBA = currentPalette().rocketAccent,
    isActive: () -> Boolean,
): RocketIcon = RocketIcon(s, loaded, accent, isActive).addTo(this)
