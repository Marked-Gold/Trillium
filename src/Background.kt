import korlibs.image.color.*
import korlibs.korge.view.*
import korlibs.math.geom.*
import korlibs.math.geom.vector.*
import korlibs.time.*
import kotlin.math.*
import kotlin.random.Random

// --- Low-poly triangle background --------------------------------------------
//
// The board sits on a procedurally generated low-poly field: a jittered point
// grid split into ~270 triangles of varied size and orientation. Each triangle
// is drawn once as a plain white shape; its real colour is applied through
// colorMul, so the pulse can re-tint it without rebuilding the shape.
//
// When a high-tier block (81+) is forged, triggerBackgroundPulse lights a colour
// wave that propagates facet-to-facet through the mesh from the triangle the
// block landed on: the wave spreads along triangle adjacency (Dijkstra over
// shared edges), so it genuinely travels through the triangles rather than
// sweeping the screen. The colour also weakens the further it travels, so the
// edges glow only faintly.
//
// A faint wireframe traced along the triangle edges — the "veins" — carries a
// subtle, always-on shimmer: a soft band of tier-coloured light sweeps across the
// mesh, brightening each vein as it passes so the glow appears to pulse through the
// field. The light's colour tracks the gradient's bottom tier, so it climbs the
// block tiers with the player's progress alongside the facets.
//
// (Originally the field was wrapped in a CachedContainer to bake the static
// triangles into one draw call; that path rendered an empty framebuffer on
// iOS Metal — black until a pulse momentarily disabled caching — and the
// recovery hooks for Android surface loss were already fragile, so the cache
// is gone. 270 small triangles is trivial work on any modern GPU.)

private class Tri(
    val view: View,
    val cx: Double,
    val cy: Double,
    val vertexIds: IntArray,
    // Vertical position 0..1 down the field and the per-facet brightness jitter:
    // together they let the base colour be recomputed when the gradient shifts.
    val ny: Double,
    val jitter: Double,
) {
    // The facet's resting colour, sampled from the vertical gradient. It changes
    // when a high-tier block raises the gradient's bottom colour.
    var baseColor: RGBA = Colors.WHITE
    // Indices (into `tris`) of the triangles sharing an edge with this one.
    val neighbors = mutableListOf<Int>()
    // Graph distance from the current pulse's origin facet, in pixels.
    var pulseDist = 0.0
}

private val tris = mutableListOf<Tri>()
private var elapsed = 0.0

// --- Vein shimmer ------------------------------------------------------------
// A faint wireframe traced along the triangle edges ("veins"). A subtle band of
// tier-coloured light travels across the field along a fixed diagonal, brightening
// each vein as it passes — so the glow appears to pulse through the mesh. Runs
// every idle frame (cheap: ~one exp + lerp per vein, colour-only, no re-tessellation).
private class Vein(
    val view: View,
    // Normalised centre position 0..1 across the field; each sweep projects these onto its
    // (randomised) travel direction, so the light can arrive from any angle.
    val nx: Double,
    val ny: Double,
    // One of the two facets this vein borders; the vein borrows that facet's pulse arrival
    // distance so its colour shifts in step with the wave front, not snapping when the pulse ends.
    val triIndex: Int,
    // Small offset folded into the sweep position so the light front reads as organic, not a ruler line.
    val jitter: Double,
) {
    // Faint resting colour (near the gradient fill it sits on); recomputed when the tier shifts.
    var restColor: RGBA = Colors.WHITE
}
private val veins = mutableListOf<Vein>()
// The luminous tier-tinted colour the shimmer peaks at; tracks the gradient bottom.
private var veinGlow: RGBA = Colors.WHITE

// Each sweep sends one band of light across the mesh; when it has crossed, a fresh random direction
// and duration are chosen for the next, so the shimmer never settles into a single predictable
// cadence. Seeded for reproducibility — the sequence still feels varied frame to frame.
private val shimmerRng = Random(98765L)
private var sweepDirX = 0.80
private var sweepDirY = 0.60
private var sweepStart = -1000.0   // far in the past so the first frame kicks off a fresh sweep
private var sweepTravel = 4.5      // sec the current sweep's front takes to cross the field
private var projMin = 0.0          // min projection along the current direction (for normalising)
private var projSpan = 1.0         // projection span along the current direction

private var pulseActive = false
private var pulseStart = 0.0
private var pulseColor = Colors.WHITE
private var pulseMaxDist = 0.0

// The vertical gradient's bottom colour tracks the highest block tier forged so
// far: it opens green (the 27 tier) and climbs to purple (81), red (243), and
// beyond. Each high-tier merge hands its tier to the pulse wave, which carries
// the gradient shift facet-to-facet as it travels.
private var gradBotTier = Rank.THREE
private var gradBotColor = Rank.THREE.color

// While a pulse runs, these hold the gradient bottom colour before and after the
// shift; pulseChangesGrad is false when the merge did not out-rank the gradient.
private var pulseGradFrom = gradBotColor
private var pulseGradTo = gradBotColor
private var pulseChangesGrad = false

private const val waveSpeed = 812.5    // px/sec the colour wave travels through the mesh
private const val pulseRise = 0.256    // sec a facet takes to reach full colour
private const val pulseFall = 0.8      // sec it takes to settle back afterward
private const val pulseStrength = 0.9  // how far toward the pulse colour a facet goes
private const val edgeFadeMin = 0.45   // colour strength left once the wave reaches the edge

// Vein shimmer tuning. Each sweep sends one band of light across the mesh in a random direction;
// once it has crossed (plus a short gap) a new direction and duration are chosen for the next.
private const val shimmerTravelMin = 3.5 // sec — fastest a sweep crosses the field
private const val shimmerTravelMax = 5.5 // sec — slowest a sweep crosses the field
private const val shimmerGap = 0.3       // sec of calm between one sweep finishing and the next
private const val shimmerPad = 0.25      // how far past the field edges the front starts/ends (clean fade in/out)
private const val shimmerSigma = 0.08    // band half-width (in normalised sweep units) — smaller = tighter
private const val shimmerPeak = 0.80     // peak brightness toward veinGlow as the band passes
private const val veinGlowWhiten = 0.45  // how far the tier colour is lifted toward white for the glow
private const val veinRestLift = 0.10    // faint always-on wireframe strength
private const val veinWidth = 1.60       // px stroke width of each vein

// Vertical gradient the facet base colours are sampled from: a calm dusty blue
// at the top easing through warm cream into gradBotColor at the bottom — that
// bottom colour climbs the block tiers as the game progresses. The top + middle
// stops come from the active theme; the bottom tracks the highest forged tier.
private val gradTop: RGBA get() = currentPalette().gradTop
private val gradMid: RGBA get() = currentPalette().gradMid

private fun RGBA.scaledRGB(f: Double): RGBA =
    RGBA(
        (r * f).roundToInt().coerceIn(0, 255),
        (g * f).roundToInt().coerceIn(0, 255),
        (b * f).roundToInt().coerceIn(0, 255),
        255,
    )

private fun lerpColor(a: RGBA, b: RGBA, t: Double): RGBA {
    val u = t.coerceIn(0.0, 1.0)
    return RGBA(
        (a.r + (b.r - a.r) * u).roundToInt(),
        (a.g + (b.g - a.g) * u).roundToInt(),
        (a.b + (b.b - a.b) * u).roundToInt(),
        255,
    )
}

private fun baseColorAt(ny: Double, jitter: Double, gradBot: RGBA): RGBA {
    val g =
        if (ny < 0.5) lerpColor(gradTop, gradMid, ny * 2.0)
        else lerpColor(gradMid, gradBot, (ny - 0.5) * 2.0)
    // Per-facet brightness jitter is what gives the low-poly faceted look.
    return g.scaledRGB(jitter)
}

private fun pulseEnvelope(lp: Double): Double =
    when {
        lp <= 0.0 -> 0.0
        lp < pulseRise -> lp / pulseRise
        lp < pulseRise + pulseFall -> 1.0 - (lp - pulseRise) / pulseFall
        else -> 0.0
    }

fun Stage.setupBackground() {
    // Honour the theme loaded at boot: re-read the gradient bottom from the current tier color so
    // the very first frame is themed correctly (gradBotColor may have initialised under BASIC).
    gradBotColor = gradBotTier.color

    val vw = views.virtualWidth.toDouble()
    val vh = views.virtualHeight.toDouble()

    // Cover 2x the virtual area, centred, so the letterbox margins outside the
    // 360x640 virtual area are filled on every phone aspect ratio (issue 5).
    val fieldW = vw * 2.0
    val fieldH = vh * 2.0
    val originX = (vw - fieldW) / 2.0
    val originY = (vh - fieldH) / 2.0

    val cols = 9
    val rows = 15
    val cellW = fieldW / cols
    val cellH = fieldH / rows
    val rng = Random(20260517L)

    // Jittered point grid. Interior points are nudged so the triangles vary in
    // size and shape; edge points stay put so the field border has no gaps.
    val pts = Array(rows + 1) { r ->
        Array(cols + 1) { c ->
            val edge = r == 0 || c == 0 || r == rows || c == cols
            val jx = if (edge) 0.0 else (rng.nextDouble() * 2.0 - 1.0) * cellW * 0.40
            val jy = if (edge) 0.0 else (rng.nextDouble() * 2.0 - 1.0) * cellH * 0.40
            Point(originX + c * cellW + jx, originY + r * cellH + jy)
        }
    }

    val triLayer = container { }
    val vStride = cols + 1
    fun vertexPoint(id: Int): Point = pts[id / vStride][id % vStride]

    fun addTri(i0: Int, i1: Int, i2: Int) {
        val a = vertexPoint(i0)
        val b = vertexPoint(i1)
        val c = vertexPoint(i2)
        val gx = (a.x + b.x + c.x) / 3.0
        val gy = (a.y + b.y + c.y) / 3.0
        // Inflate each vertex ~1px outward from the centroid so neighbouring
        // triangles overlap a hair and anti-aliased edges leave no seams.
        fun push(p: Point): Point {
            val dx = p.x - gx
            val dy = p.y - gy
            val len = hypot(dx, dy).coerceAtLeast(0.0001)
            val f = (len + 1.0) / len
            return Point(gx + dx * f, gy + dy * f)
        }
        val pa = push(a)
        val pb = push(b)
        val pc = push(c)
        val minX = minOf(pa.x, pb.x, pc.x).toDouble()
        val minY = minOf(pa.y, pb.y, pc.y).toDouble()
        // Drawn white in local coords; the colour lives in colorMul so it can be
        // tweened every frame without rebuilding the shape.
        val view =
            triLayer.graphics {
                fill(Colors.WHITE) {
                    moveTo(Point(pa.x - minX, pa.y - minY))
                    lineTo(Point(pb.x - minX, pb.y - minY))
                    lineTo(Point(pc.x - minX, pc.y - minY))
                    close()
                }
            }.xy(minX, minY)

        val ny = ((gy - originY) / fieldH).coerceIn(0.0, 1.0)
        val jitter = 1.0 + (rng.nextDouble() * 2.0 - 1.0) * 0.14
        val tri = Tri(view, gx, gy, intArrayOf(i0, i1, i2), ny, jitter)
        tri.baseColor = baseColorAt(ny, jitter, gradBotColor)
        view.colorMul = tri.baseColor
        tris += tri
    }

    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val tl = r * vStride + c
            val tr = r * vStride + c + 1
            val bl = (r + 1) * vStride + c
            val br = (r + 1) * vStride + c + 1
            // Alternate the split diagonal so facet orientation varies too.
            if ((r + c) % 2 == 0) {
                addTri(tl, tr, br)
                addTri(tl, br, bl)
            } else {
                addTri(tl, tr, bl)
                addTri(tr, br, bl)
            }
        }
    }

    buildAdjacency()

    // --- Trace the veins: one thin line per unique triangle edge ----------------
    // Only the edges in the visible region are traced: the field covers 2x the
    // screen to fill letterbox margins, but the shimmer is only ever seen near centre.
    val cullMinX = -0.30 * vw; val cullMaxX = 1.30 * vw
    val cullMinY = -0.30 * vh; val cullMaxY = 1.30 * vh
    veins.clear()   // idempotent if setup re-runs (tests / restart)
    val edgeLayer = container { }
    val seenEdges = HashSet<Long>()
    fun edgeKeyOf(a: Int, b: Int): Long {
        val lo = minOf(a, b).toLong(); val hi = maxOf(a, b).toLong()
        return lo * 100_000L + hi
    }
    // Create one line view per unique, on-screen edge, storing each vein's normalised centre so the
    // sweep can re-project it onto a fresh random direction every pass.
    for (ti in tris.indices) {
        val vids = tris[ti].vertexIds
        for (e in 0 until 3) {
            val ia = vids[e]; val ib = vids[(e + 1) % 3]
            if (!seenEdges.add(edgeKeyOf(ia, ib))) continue
            val pa = vertexPoint(ia); val pb = vertexPoint(ib)
            val mx = (pa.x + pb.x) / 2.0; val my = (pa.y + pb.y) / 2.0
            if (mx < cullMinX || mx > cullMaxX || my < cullMinY || my > cullMaxY) continue
            val nx = ((mx - originX) / fieldW).coerceIn(0.0, 1.0)
            val ny = ((my - originY) / fieldH).coerceIn(0.0, 1.0)
            // Drawn white; the colour lives in colorMul so the shimmer can re-tint it
            // every frame without rebuilding the stroke (same trick as the facets).
            val view =
                edgeLayer.graphics {
                    stroke(Colors.WHITE, lineWidth = veinWidth, lineCap = LineCap.ROUND) {
                        moveTo(pa)
                        lineTo(pb)
                    }
                }
            val jitter = (rng.nextDouble() * 2.0 - 1.0) * 0.03
            veins += Vein(view, nx, ny, ti, jitter)
        }
    }
    rebaseVeins()

    // Re-tint every facet when the player switches color theme, keeping the tier progress.
    colorTheme.observe { refreshBackgroundColors() }

    // Idle frames are a no-op: the triangles hold their base colours and the
    // renderer redraws them as-is. Only an active pulse re-tints facets.
    addUpdater { dt ->
        elapsed += dt.seconds
        updateShimmer()
        if (!pulseActive) return@addUpdater

        val pulseT = elapsed - pulseStart
        if (pulseT > pulseMaxDist / waveSpeed + pulseRise + pulseFall) {
            // Pulse finished: commit any gradient shift it carried and settle
            // every facet onto its (possibly new) base colour.
            pulseActive = false
            if (pulseChangesGrad) {
                gradBotColor = pulseGradTo
                pulseChangesGrad = false
            }
            rebaseVeins()
            for (t in tris) {
                t.baseColor = baseColorAt(t.ny, t.jitter, gradBotColor)
                t.view.colorMul = t.baseColor
            }
            return@addUpdater
        }

        for (t in tris) {
            val lp = pulseT - t.pulseDist / waveSpeed
            // The wave front carries the new gradient bottom colour: once it
            // reaches a facet, that facet's base eases from the old gradient to
            // the new one over the same window as the colour flash.
            val curBase =
                if (!pulseChangesGrad) t.baseColor
                else lerpColor(
                    baseColorAt(t.ny, t.jitter, pulseGradFrom),
                    baseColorAt(t.ny, t.jitter, pulseGradTo),
                    (lp / (pulseRise + pulseFall)).coerceIn(0.0, 1.0),
                )
            var color = curBase
            val env = pulseEnvelope(lp)
            if (env > 0.0) {
                // The wave loses colour the further it has travelled, so the
                // facets near the merge glow brightest and the edges only faintly.
                val travelled = if (pulseMaxDist > 0.0) t.pulseDist / pulseMaxDist else 0.0
                val fade = 1.0 - travelled * (1.0 - edgeFadeMin)
                color = lerpColor(curBase, pulseColor, env * pulseStrength * fade)
            }
            t.view.colorMul = color
        }
    }
}

// Links every triangle to the ones it shares an edge with. An edge belongs to
// exactly two triangles inside the mesh and one on the border; the interior
// edges are what the colour wave hops across.
private fun buildAdjacency() {
    val edgeMap = mutableMapOf<Long, MutableList<Int>>()
    fun edgeKey(a: Int, b: Int): Long {
        val lo = minOf(a, b).toLong()
        val hi = maxOf(a, b).toLong()
        return lo * 100_000L + hi
    }
    tris.forEachIndexed { ti, t ->
        val v = t.vertexIds
        for (e in 0 until 3) {
            edgeMap.getOrPut(edgeKey(v[e], v[(e + 1) % 3])) { mutableListOf() }.add(ti)
        }
    }
    for (sharing in edgeMap.values) {
        if (sharing.size == 2) {
            val x = sharing[0]
            val y = sharing[1]
            tris[x].neighbors.add(y)
            tris[y].neighbors.add(x)
        }
    }
}

// Called when a merge forges an 81-tier (or higher) block. Lights a colour wave
// that travels facet-to-facet through the mesh, starting from the triangle
// nearest (centerX, centerY) — the spot the block was made. When the forged
// tier out-ranks the gradient's current bottom tier, the wave also carries the
// gradient's bottom colour up to that tier as it travels.
fun triggerBackgroundPulse(tier: Rank, centerX: Double, centerY: Double) {
    if (tris.isEmpty()) return

    // A merge landed before the previous pulse settled: commit whatever gradient
    // shift it was still carrying so this pulse starts from the right base.
    if (pulseActive && pulseChangesGrad) {
        gradBotColor = pulseGradTo
        for (t in tris) t.baseColor = baseColorAt(t.ny, t.jitter, gradBotColor)
    }

    pulseColor = tier.color
    pulseStart = elapsed
    pulseActive = true

    // The gradient bottom only ever climbs: a merge shifts it only when its tier
    // out-ranks the tier the gradient currently shows.
    pulseGradFrom = gradBotColor
    if (tier.ordinal > gradBotTier.ordinal) {
        gradBotTier = tier
        pulseGradTo = tier.color
        pulseChangesGrad = true
    } else {
        pulseGradTo = gradBotColor
        pulseChangesGrad = false
    }

    // The wave's origin facet: the triangle closest to where the block landed.
    var source = 0
    var bestDist = Double.MAX_VALUE
    tris.forEachIndexed { i, t ->
        val d = hypot(t.cx - centerX, t.cy - centerY)
        if (d < bestDist) {
            bestDist = d
            source = i
        }
    }

    // Dijkstra over the facet adjacency graph (edge weight = centroid spacing)
    // gives each triangle its distance along the mesh from the origin facet.
    val n = tris.size
    val dist = DoubleArray(n) { Double.MAX_VALUE }
    val settled = BooleanArray(n)
    dist[source] = 0.0
    for (step in 0 until n) {
        var u = -1
        var ud = Double.MAX_VALUE
        for (i in 0 until n) {
            if (!settled[i] && dist[i] < ud) {
                ud = dist[i]
                u = i
            }
        }
        if (u < 0) break
        settled[u] = true
        val tu = tris[u]
        for (v in tu.neighbors) {
            if (settled[v]) continue
            val w = hypot(tu.cx - tris[v].cx, tu.cy - tris[v].cy)
            if (dist[u] + w < dist[v]) dist[v] = dist[u] + w
        }
    }

    var maxDist = 0.0
    for (i in 0 until n) {
        val d = if (dist[i] == Double.MAX_VALUE) 0.0 else dist[i]
        tris[i].pulseDist = d
        if (d > maxDist) maxDist = d
    }
    pulseMaxDist = maxDist
}

// Re-tints the whole mesh for a new color theme: recomputes the gradient bottom from the current
// tier (under the new palette) and re-bases every facet, *without* resetting the tier — so the
// player's progress glow is preserved across a live theme switch.
fun refreshBackgroundColors() {
    gradBotColor = gradBotTier.color
    pulseGradFrom = gradBotColor
    pulseGradTo = gradBotColor
    for (t in tris) {
        t.baseColor = baseColorAt(t.ny, t.jitter, gradBotColor)
        t.view.colorMul = t.baseColor
    }
    rebaseVeins()
}

// Advances the vein shimmer one frame: a soft band of tier-coloured light sweeps the
// mesh along the fixed diagonal, brightening each vein toward veinGlow as it passes.
//
// While a tier-raising pulse is travelling, a vein's rest + glow colours are not the cached
// values (those are still the old tier) — they're eased from the old tier to the new one in step
// with the wave front, keyed by the arrival distance of the facet the vein borders. That keeps the
// veins recolouring alongside the facets instead of snapping to the new tier when the pulse ends.
private fun updateShimmer() {
    if (veins.isEmpty()) return
    // When the current sweep (plus its trailing gap) is spent, choose a new random direction + speed.
    if (elapsed - sweepStart > sweepTravel + shimmerGap) startNewSweep()
    // The front travels from just past one field edge to just past the other, so the band fades fully
    // in and out instead of popping on at the boundary.
    val front = -shimmerPad + ((elapsed - sweepStart) / sweepTravel) * (1.0 + 2.0 * shimmerPad)
    val recolouring = pulseActive && pulseChangesGrad
    val pulseT = elapsed - pulseStart
    for (v in veins) {
        val pos = (v.nx * sweepDirX + v.ny * sweepDirY - projMin) / projSpan + v.jitter
        val d = pos - front
        val band = exp(-(d * d) / (2.0 * shimmerSigma * shimmerSigma))

        val rest: RGBA
        val glow: RGBA
        if (recolouring) {
            // Ease the gradient bottom old -> new over the same window the facet uses, offset by
            // when the wave reaches the facet this vein borders, so the recolour ripples with it.
            val lp = pulseT - tris[v.triIndex].pulseDist / waveSpeed
            val frac = (lp / (pulseRise + pulseFall)).coerceIn(0.0, 1.0)
            val gradC = lerpColor(pulseGradFrom, pulseGradTo, frac)
            glow = lerpColor(gradC, Colors.WHITE, veinGlowWhiten)
            rest = lerpColor(baseColorAt(v.ny, 1.0, gradC), glow, veinRestLift)
        } else {
            rest = v.restColor
            glow = veinGlow
        }
        v.view.colorMul = lerpColor(rest, glow, band * shimmerPeak)
    }
}

// Picks a fresh random sweep direction and crossing time, and renormalises every vein's projection
// onto that direction so the band still enters one side of the field and exits the other.
private fun startNewSweep() {
    val ang = shimmerRng.nextDouble() * 2.0 * PI
    sweepDirX = cos(ang)
    sweepDirY = sin(ang)
    sweepTravel = shimmerTravelMin + shimmerRng.nextDouble() * (shimmerTravelMax - shimmerTravelMin)
    sweepStart = elapsed
    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    for (v in veins) {
        val p = v.nx * sweepDirX + v.ny * sweepDirY
        if (p < lo) lo = p
        if (p > hi) hi = p
    }
    projMin = lo
    projSpan = (hi - lo).coerceAtLeast(1e-6)
}

// Recomputes the veins' resting colours and the glow target when the tier (and thus
// gradBotColor) changes. The glow is the tier colour lifted toward white so the shimmer
// reads as light catching the veins even when the tier colour itself is dark.
private fun rebaseVeins() {
    veinGlow = lerpColor(gradBotColor, Colors.WHITE, veinGlowWhiten)
    for (v in veins) {
        val fill = baseColorAt(v.ny, 1.0, gradBotColor)
        v.restColor = lerpColor(fill, veinGlow, veinRestLift)
        v.view.colorMul = v.restColor
    }
}

// Restores the gradient to its opening state (gray -> green) for a new game.
fun resetBackgroundGradient() {
    gradBotTier = Rank.THREE
    gradBotColor = Rank.THREE.color
    pulseActive = false
    pulseChangesGrad = false
    pulseGradFrom = gradBotColor
    pulseGradTo = gradBotColor
    for (t in tris) {
        t.baseColor = baseColorAt(t.ny, t.jitter, gradBotColor)
        t.view.colorMul = t.baseColor
    }
    rebaseVeins()
}
