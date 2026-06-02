import korlibs.korge.view.*
import korlibs.image.color.*
import korlibs.math.geom.*
import korlibs.math.geom.vector.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Vector-drawn UI icons (pause, restart, share).
 *
 * Every icon is built from KorGE vector shapes so it stays crisp at any resolution and shares a
 * single, consistent visual language (flat fills, rounded ends, no external image assets).
 *
 * Each builder returns a [Container] sized exactly `s x s` (an invisible bounds rect keeps the box
 * square) so callers can `centerOn` / `align*` it like a plain image.
 */

private val iconInk: RGBA get() = currentPalette().iconInk

private fun Container.iconBox(
    s: Double,
    draw: Container.() -> Unit,
): Container =
    container {
        // Transparent rect pins the container bounds to a square s x s box.
        solidRect(s, s, RGBA(0, 0, 0, 0))
        draw()
    }

/** Pause / menu icon — two rounded vertical bars. */
fun Container.pauseIcon(
    s: Double,
    color: RGBA = iconInk,
): Container =
    iconBox(s) {
        graphics {
            fill(color) {
                val barW = s * 0.24
                val barH = s * 0.72
                val top = (s - barH) / 2.0
                val gap = s * 0.16
                val r = barW * 0.5
                roundRect(s / 2.0 - gap / 2.0 - barW, top, barW, barH, r, r)
                roundRect(s / 2.0 + gap / 2.0, top, barW, barH, r, r)
            }
        }
    }

/** Restart icon — a circular arrow (open ring with an arrowhead). */
fun Container.restartIcon(
    s: Double,
    color: RGBA = iconInk,
): Container =
    iconBox(s) {
        graphics {
            val cx = s / 2.0
            val cy = s / 2.0
            val r = s * 0.30
            val lw = s * 0.155
            // Ring sweeping clockwise, leaving a gap at the top for the arrowhead.
            stroke(color, lineWidth = lw, lineCap = LineCap.ROUND) {
                arc(Point(cx, cy), r, (-58).degrees, 232.degrees, false)
            }
            // Arrowhead at the end of the arc, pointing along the direction of travel.
            val a = 232.0 * PI / 180.0
            val ex = cx + r * cos(a)
            val ey = cy + r * sin(a)
            val tx = -sin(a) // clockwise tangent
            val ty = cos(a)
            val px = -ty // perpendicular
            val py = tx
            val hl = s * 0.21
            val hw = s * 0.16
            fill(color) {
                moveTo(ex + tx * hl, ey + ty * hl)
                lineTo(ex - tx * hl * 0.5 + px * hw, ey - ty * hl * 0.5 + py * hw)
                lineTo(ex - tx * hl * 0.5 - px * hw, ey - ty * hl * 0.5 - py * hw)
                close()
            }
        }
    }

/** Undo icon — the [restartIcon] mirrored along the x axis. Same arc, line weight, and arrowhead
 * geometry as restart, just reflected so the arrowhead lands on the opposite side and the sweep
 * reads as counter-clockwise. Building it from the same primitives keeps the two icons visually
 * cohesive while still distinguishable at a glance. */
fun Container.undoIcon(
    s: Double,
    color: RGBA = iconInk,
): Container =
    iconBox(s) {
        // Inner container flipped along x; everything drawn inside renders mirrored.
        container {
            scaleX = -1.0
            x = s
            graphics {
                val cx = s / 2.0
                val cy = s / 2.0
                val r = s * 0.30
                val lw = s * 0.155
                stroke(color, lineWidth = lw, lineCap = LineCap.ROUND) {
                    arc(Point(cx, cy), r, (-58).degrees, 232.degrees, false)
                }
                val a = 232.0 * PI / 180.0
                val ex = cx + r * cos(a)
                val ey = cy + r * sin(a)
                val tx = -sin(a)
                val ty = cos(a)
                val px = -ty
                val py = tx
                val hl = s * 0.21
                val hw = s * 0.16
                fill(color) {
                    moveTo(ex + tx * hl, ey + ty * hl)
                    lineTo(ex - tx * hl * 0.5 + px * hw, ey - ty * hl * 0.5 + py * hw)
                    lineTo(ex - tx * hl * 0.5 - px * hw, ey - ty * hl * 0.5 - py * hw)
                    close()
                }
            }
        }
    }

/** Help icon — a question mark. */
fun Container.helpIcon(
    s: Double,
    color: RGBA = iconInk,
): Container =
    iconBox(s) {
        graphics {
            // Hook and stem.
            stroke(color, lineWidth = s * 0.135, lineCap = LineCap.ROUND) {
                moveTo(s * 0.29, s * 0.36)
                quadTo(s * 0.29, s * 0.13, s * 0.5, s * 0.13)
                quadTo(s * 0.71, s * 0.13, s * 0.71, s * 0.35)
                quadTo(s * 0.71, s * 0.52, s * 0.5, s * 0.56)
                lineTo(s * 0.5, s * 0.64)
            }
            // Dot.
            fill(color) {
                circle(Point(s * 0.5, s * 0.83), s * 0.085)
            }
        }
    }

/** Haptics icon — a phone silhouette flanked by vibration lines. */
fun Container.hapticsIcon(
    s: Double,
    color: RGBA = iconInk,
): Container =
    iconBox(s) {
        graphics {
            val cx = s / 2.0
            val cy = s / 2.0
            val pw = s * 0.30
            val ph = s * 0.54
            // Phone body.
            fill(color) {
                roundRect(cx - pw / 2.0, cy - ph / 2.0, pw, ph, s * 0.07, s * 0.07)
            }
            // Vibration lines: a tall inner pair and a short outer pair, mirrored on each side.
            stroke(color, lineWidth = s * 0.085, lineCap = LineCap.ROUND) {
                for (side in listOf(-1.0, 1.0)) {
                    val innerX = cx + side * (pw / 2.0 + s * 0.12)
                    val outerX = cx + side * (pw / 2.0 + s * 0.24)
                    moveTo(innerX, cy - s * 0.17)
                    lineTo(innerX, cy + s * 0.17)
                    moveTo(outerX, cy - s * 0.10)
                    lineTo(outerX, cy + s * 0.10)
                }
            }
        }
    }

/** Sound icon — a speaker silhouette with two sound waves radiating to the right. Dimmed by the
 * caller (alpha = 0.3) to indicate muted, matching the haptics-toggle pattern; no separate "muted"
 * variant. */
fun Container.speakerIcon(
    s: Double,
    color: RGBA = iconInk,
): Container =
    iconBox(s) {
        graphics {
            // Magnet rectangle on the left, fused to a trapezoidal cone widening to the right.
            // Drawn as one closed path so they share a single fill with no seam between them.
            fill(color) {
                moveTo(s * 0.16, s * 0.42)
                lineTo(s * 0.30, s * 0.42)
                lineTo(s * 0.50, s * 0.27)
                lineTo(s * 0.50, s * 0.73)
                lineTo(s * 0.30, s * 0.58)
                lineTo(s * 0.16, s * 0.58)
                close()
            }
            // Two arcs to the right of the cone — the inner one closer in, the outer slightly past.
            stroke(color, lineWidth = s * 0.085, lineCap = LineCap.ROUND) {
                arc(Point(s * 0.45, s * 0.50), s * 0.18, (-40).degrees, 40.degrees, false)
                arc(Point(s * 0.45, s * 0.50), s * 0.30, (-40).degrees, 40.degrees, false)
            }
        }
    }

/** Settings icon — three horizontal sliders with offset round handles, the universal
 * "preferences / tune-able controls" glyph. */
fun Container.settingsIcon(
    s: Double,
    color: RGBA = iconInk,
): Container =
    iconBox(s) {
        graphics {
            val lineWidth = s * 0.085
            val knobR = s * 0.10
            val laneStart = s * 0.18
            val laneEnd = s * 0.82
            // Three horizontal lanes evenly spaced top-to-bottom.
            val lanes = listOf(s * 0.28, s * 0.50, s * 0.72)
            // Knob X positions vary per lane so the icon reads as adjustable sliders.
            val knobs = listOf(s * 0.62, s * 0.34, s * 0.70)
            stroke(color, lineWidth = lineWidth, lineCap = LineCap.ROUND) {
                for (laneY in lanes) {
                    moveTo(laneStart, laneY)
                    lineTo(laneEnd, laneY)
                }
            }
            fill(color) {
                for (i in lanes.indices) {
                    circle(Point(knobs[i], lanes[i]), knobR)
                }
            }
        }
    }

/** Shield icon — a flat-topped shield with a checkmark inside. Used to label the AD PERMISSIONS
 * row (the UMP privacy-options form) in the settings sub-menu. */
fun Container.shieldIcon(
    s: Double,
    color: RGBA = iconInk,
): Container =
    iconBox(s) {
        graphics {
            stroke(color, lineWidth = s * 0.10, lineCap = LineCap.ROUND) {
                // Shield outline: flat top, straight sides, rounded point at the bottom.
                moveTo(s * 0.22, s * 0.22)
                lineTo(s * 0.78, s * 0.22)
                lineTo(s * 0.78, s * 0.55)
                quadTo(s * 0.78, s * 0.78, s * 0.50, s * 0.86)
                quadTo(s * 0.22, s * 0.78, s * 0.22, s * 0.55)
                close()
                // Checkmark.
                moveTo(s * 0.36, s * 0.50)
                lineTo(s * 0.46, s * 0.61)
                lineTo(s * 0.66, s * 0.40)
            }
        }
    }

/** Gravity icon — a downward arrow, marking gravity mode (pieces fall, new ones drop from the
 * top). Reused for the settings toggle row and the small in-game BEST-box mode indicator. */
fun Container.gravityIcon(
    s: Double,
    color: RGBA = iconInk,
): Container =
    iconBox(s) {
        graphics {
            // Vertical stem.
            stroke(color, lineWidth = s * 0.135, lineCap = LineCap.ROUND) {
                moveTo(s * 0.5, s * 0.16)
                lineTo(s * 0.5, s * 0.60)
            }
            // Arrowhead pointing down.
            fill(color) {
                moveTo(s * 0.5, s * 0.86)
                lineTo(s * 0.28, s * 0.55)
                lineTo(s * 0.72, s * 0.55)
                close()
            }
        }
    }

/** Themes icon — three overlapping rings, the classic "colors / palette" glyph. Labels the THEMES
 * row in the settings sub-menu (the color-theme picker). */
fun Container.paletteIcon(
    s: Double,
    color: RGBA = iconInk,
): Container =
    iconBox(s) {
        graphics {
            stroke(color, lineWidth = s * 0.075, lineCap = LineCap.ROUND) {
                val r = s * 0.20
                circle(Point(s * 0.40, s * 0.40), r)
                circle(Point(s * 0.60, s * 0.40), r)
                circle(Point(s * 0.50, s * 0.61), r)
            }
        }
    }

/** Share icon — three connected nodes. */
fun Container.shareIcon(
    s: Double,
    color: RGBA = iconInk,
): Container =
    iconBox(s) {
        graphics {
            val dotR = s * 0.15
            val left = Point(s * 0.27, s * 0.50)
            val topRight = Point(s * 0.73, s * 0.23)
            val botRight = Point(s * 0.73, s * 0.77)
            stroke(color, lineWidth = s * 0.085, lineCap = LineCap.ROUND) {
                moveTo(left)
                lineTo(topRight)
                moveTo(left)
                lineTo(botRight)
            }
            fill(color) {
                circle(left, dotR)
                circle(topRight, dotR)
                circle(botRight, dotR)
            }
        }
    }

