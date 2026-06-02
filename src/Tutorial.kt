import Rank.*
import korlibs.korge.input.*
import korlibs.korge.view.*
import korlibs.korge.view.align.*
import korlibs.image.color.*
import korlibs.image.text.TextAlignment
import korlibs.io.async.launchImmediately
import korlibs.math.geom.*
import korlibs.time.*
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * First-launch interactive tutorial and the static "How to play" recap.
 *
 * The interactive tutorial runs once (gated by the "tutorialSeen" storage flag): it scripts cells
 * on the real game board, locks input to one action at a time, and walks the player through a
 * real merge, a line merge, a square merge, a bomb and a rocket. The same explanatory content is
 * available any time from the pause menu as a read-only swipeable guide ([showHowToPlay]).
 *
 * Input gating: while [tutorialActive] the game's input handlers consult the query functions
 * below ([tutorialBoardEnabled], [tutorialAllowedPositions], [tutorialAllowsBombTap], ...) so the
 * player can only perform the step's scripted action. Completion is reported back by the
 * animation code via [onTutorialMerge] / [onTutorialBomb] / [onTutorialRocket].
 */

const val tutorialSeenKey = "tutorialSeen"

// True for the whole duration of the interactive first-launch tutorial.
var tutorialActive = false

// When non-null, only these board cells accept block selection. An empty set blocks all block
// selection (used by the bomb/rocket steps, whose power-up flows do not go through this gate).
var tutorialAllowedPositions: Set<Position>? = null

private enum class TStep { WELCOME, MERGE, LINE, SQUARE, BOMB, ROCKET, UNDO, GAMEON }

private val tStepOrder = TStep.values()
private val mergeSteps = setOf(TStep.MERGE, TStep.LINE, TStep.SQUARE)
private var tutorialStepIndex = 0
private val currentTStep get() = tStepOrder[tutorialStepIndex]

private var tutorialOnFinish: () -> Unit = {}
private var tutorialContainer: Container = Container()

// Scripted block layouts for the three merge steps. The line sits on row 5 and the square on
// rows 6-7 so the three steps occupy distinct, clearly separated parts of the board.
private fun mergeTargets() = listOf(Position(2, 3), Position(3, 3), Position(4, 3))
private fun lineTargets() = listOf(Position(1, 4), Position(2, 4), Position(3, 4), Position(4, 4), Position(5, 4))
private fun squareTargets() = listOf(Position(3, 5), Position(4, 5), Position(3, 6), Position(4, 6))

// ---- Colours -------------------------------------------------------------------------------

private val overlayDim = RGBA(10, 12, 20, 232)
private val cardBg = RGBA(30, 32, 46, 250)
private val coachBg = RGBA(20, 22, 34, 244)
private val cardAccent = Colors["#a3d6b8"]
private val cardBody = RGBA(232, 232, 238)
private val cardMuted = RGBA(160, 162, 176)
// A glaring red wash pulsed over the backgrounds of the blocks the player must select.
private val highlightColor = Colors["#FF2D2D"]
private val footerColor = Colors["#FFD23F"]

// ---- Gating queries consulted by the input / animation code --------------------------------

/** Whether the board accepts touches at all in the current tutorial step. */
fun tutorialBoardEnabled(): Boolean =
    !tutorialActive ||
        (currentTStep != TStep.WELCOME && currentTStep != TStep.UNDO && currentTStep != TStep.GAMEON)

/** Whether tapping the bomb power-up icon is allowed right now. */
fun tutorialAllowsBombTap(): Boolean = !tutorialActive || currentTStep == TStep.BOMB

/** Whether tapping the rocket power-up icon is allowed right now. */
fun tutorialAllowsRocketTap(): Boolean = !tutorialActive || currentTStep == TStep.ROCKET

/** Whether the pause button is allowed right now. */
fun tutorialAllowsPause(): Boolean = !tutorialActive

/** True if [position] may not be selected because of tutorial gating. */
fun tutorialBlocksPosition(position: Position): Boolean {
    val allowed = tutorialAllowedPositions ?: return false
    return tutorialActive && position !in allowed
}

// ---- Step-completion callbacks (called by the animation code) ------------------------------

fun Stage.onTutorialMerge() {
    if (tutorialActive && currentTStep in mergeSteps) advanceTutorial()
}

fun Stage.onTutorialBomb() {
    if (tutorialActive && currentTStep == TStep.BOMB) advanceTutorial()
}

fun Stage.onTutorialRocket() {
    if (tutorialActive && currentTStep == TStep.ROCKET) advanceTutorial()
}

// ---- Driver --------------------------------------------------------------------------------

/** Starts the scripted first-launch tutorial. [onFinish] runs once it completes or is skipped. */
fun Stage.startInteractiveTutorial(onFinish: () -> Unit) {
    Napier.d("Starting interactive tutorial")
    tutorialActive = true
    tutorialOnFinish = onFinish
    tutorialStepIndex = 0
    showCurrentTutorialStep()
}

private fun Stage.advanceTutorial() {
    // A skip tap can bubble a stale advance after the tutorial already ended; ignore it.
    if (!tutorialActive) return
    if (tutorialStepIndex >= tStepOrder.lastIndex) {
        finishTutorial()
    } else {
        tutorialStepIndex++
        showCurrentTutorialStep()
    }
}

private fun Stage.finishTutorial() {
    if (!tutorialActive) return
    Napier.d("Finishing interactive tutorial")
    tutorialContainer.removeFromParent()
    tutorialActive = false
    tutorialAllowedPositions = null
    tutorialOnFinish()
}

private fun Stage.showCurrentTutorialStep() {
    tutorialContainer.removeFromParent()
    when (currentTStep) {
        TStep.WELCOME -> {
            tutorialAllowedPositions = null
            showPageStep(welcomePage(), "TAP TO BEGIN", showSkip = true)
        }
        TStep.MERGE ->
            showMergeStep(
                mergeTargets(),
                ZERO,
                "MERGE BLOCKS",
                "Drag across all 3 flashing blocks",
            )
        TStep.LINE ->
            showMergeStep(
                lineTargets(),
                ONE,
                "LINE MERGE",
                "Drag across all 5 flashing blocks",
                "A longer line climbs to a higher tier",
            )
        TStep.SQUARE ->
            showMergeStep(
                squareTargets(),
                ZERO,
                "SQUARE MERGE",
                "Drag across all 4 flashing blocks",
                "A filled square forges several blocks",
            )
        TStep.BOMB -> {
            tutorialAllowedPositions = emptySet()
            showActionStep(
                "BOMBS",
                "Tap the bomb, then tap a block",
                "Blasts a 3x3 area. Earned from 243+ merges.",
                bombContainer,
                highlightActive = { !bombSelected },
            )
        }
        TStep.ROCKET -> {
            tutorialAllowedPositions = emptySet()
            showActionStep(
                "ROCKETS",
                "Tap the rocket, then two blocks",
                "Swaps two blocks. Earned from chains of 8 or more.",
                rocketContainer,
                highlightActive = { !rocketSelection.selected },
            )
        }
        TStep.UNDO -> {
            tutorialAllowedPositions = null
            showPageStep(undoPage(), "TAP TO CONTINUE")
        }
        TStep.GAMEON -> showGameOnStep()
    }
}

// ---- Step: full-screen explanatory page (welcome / done) -----------------------------------

private fun Stage.showPageStep(page: InfoPage, footer: String, showSkip: Boolean = false) {
    tutorialContainer =
        container {
            // Dark backdrop large enough to cover any phone aspect ratio.
            solidRect(views.virtualWidth * 6.0, views.virtualHeight * 6.0, overlayDim) {
                xy(-views.virtualWidth * 2.5, -views.virtualHeight * 2.5)
            }

            val cardWidth = 316.0
            val content = buildInfoCard(cardWidth, page)
            val pad = 20.0
            val footerH = 48.0
            val card =
                container {
                    roundRect(
                        Size(cardWidth + pad * 2, content.height + pad * 2 + footerH),
                        RectCorners(20.0),
                        fill = cardBg,
                    )
                    content.addTo(this).xy(pad, pad)
                    text(footer, 18.0, footerColor, font) {
                        setTextBounds(Rectangle(0.0, 0.0, cardWidth + pad * 2, footerH))
                        alignment = TextAlignment.MIDDLE_CENTER
                        y = content.height + pad
                    }
                }
            card.centerXOn(gameField)
            card.centerYOn(gameField)

            if (showSkip) {
                text("Skip tutorial", 14.0, RGBA(150, 150, 160), font) {
                    setTextBounds(Rectangle(0.0, 0.0, 140.0, 22.0))
                    alignment = TextAlignment.MIDDLE_CENTER
                    centerXOn(card)
                    alignTopToBottomOf(card, 16.0)
                    onClick { finishTutorial() }
                }
            }

            // Tapping anywhere on the backdrop advances to the next step.
            onClick { advanceTutorial() }
        }
}

// ---- Step: scripted merge (merge / line / square) ------------------------------------------

private fun Stage.showMergeStep(
    targets: List<Position>,
    number: Rank,
    title: String,
    line: String,
    note: String? = null,
) {
    // Re-script the target cells so the step always has the exact layout it teaches.
    scriptTutorialCells(targets, number)
    tutorialAllowedPositions = targets.toSet()
    tutorialContainer =
        container {
            val width = fieldWidth.toDouble()
            // Hover the banner just above the flashing blocks with a downward arrow pointing right
            // at them (the same pointer the bomb/rocket banners use), so it is obvious which blocks
            // to drag across. The banner always sits above the targets, never over them.
            val minX = targets.minOf { getXFromPosition(it) }
            val maxX = targets.maxOf { getXFromPosition(it) }
            val pointerX = (minX + maxX) / 2.0 + cellSize / 2.0
            val topY = targets.minOf { getYFromPosition(it) }.toDouble()
            val panelY = (topY - coachHeight(line, note, width) - 16.0).coerceAtLeast(topIndent + 4.0)
            coachPanel(title, line, note, leftIndent.toDouble(), panelY, width, pointerX = pointerX)
            targets.forEach { position ->
                pulseHighlight(
                    getXFromPosition(position).toDouble(),
                    getYFromPosition(position).toDouble(),
                    cellSize.toDouble(),
                    cellSize.toDouble(),
                    5.0,
                )
            }
        }
}

// ---- Step: scripted power-up use (bomb / rocket) -------------------------------------------

private fun Stage.showActionStep(
    title: String,
    line: String,
    note: String,
    target: Container,
    highlightActive: () -> Boolean,
) {
    tutorialContainer =
        container {
            val iconSize = cellSize * 2.5
            val centerX = target.x + iconSize / 2.0
            val width = fieldWidth.toDouble()
            // The coach banner sits just above the power-up it points at, not over the board.
            coachPanel(
                title, line, note,
                x = leftIndent.toDouble(),
                y = target.y - coachHeight(line, note, width) - 16.0,
                width = width,
                pointerX = centerX,
            )
            // The wash stops once the power-up is selected.
            pulseHighlight(target.x, target.y, iconSize, iconSize, 10.0, highlightActive)
        }
}

// ---- Step: closing "GAME ON" splash --------------------------------------------------------

/** Types out "GAME ON" (the same effect as the game-over screen), then ends the tutorial. */
private fun Stage.showGameOnStep() {
    tutorialAllowedPositions = null
    val headingText = "GAME ON"
    val glyphs = mutableListOf<Text>()
    tutorialContainer =
        container {
            solidRect(views.virtualWidth * 6.0, views.virtualHeight * 6.0, overlayDim) {
                xy(-views.virtualWidth * 2.5, -views.virtualHeight * 2.5)
            }
            val heading =
                container {
                    // Faux-bold: stamp the text twice with a small offset.
                    for (offset in listOf(0.0, 1.8)) {
                        glyphs += text(headingText, 44.0, footerColor, font) {
                            alignment = TextAlignment.MIDDLE_CENTER
                            x = offset
                        }
                    }
                }
            heading.centerXOn(gameField)
            heading.centerYOn(gameField)
        }
    glyphs.forEach { it.text = "" }
    launchImmediately {
        delay(140L)
        for (i in 1..headingText.length) {
            val visible = headingText.substring(0, i)
            glyphs.forEach { it.text = visible }
            delay(80L)
        }
        delay(750L)
        finishTutorial()
    }
}

// ---- Shared UI building blocks -------------------------------------------------------------

private fun coachLineLines(line: String, width: Double) = wrap(line, maxOf(12, (width / 10.0).toInt()))

private fun coachNoteLines(note: String, width: Double) = wrap(note, maxOf(12, (width / 7.5).toInt()))

/** The height a coach banner needs for the given instruction [line] and optional [note]. */
private fun coachHeight(line: String, note: String?, width: Double): Double {
    var h = 14.0 + 28.0 + coachLineLines(line, width).size * 26.0
    if (note != null) h += 6.0 + coachNoteLines(note, width).size * 19.0
    return h + 14.0
}

/**
 * An instruction banner: a gold title, the instruction [line], and an optional muted [note].
 * Both the line and the note wrap to [width], and the banner sizes itself to fit. With
 * [pointerX] set it grows a downward arrow toward that x (used to point at a power-up).
 */
private fun Container.coachPanel(
    title: String,
    line: String,
    note: String?,
    x: Double,
    y: Double,
    width: Double,
    pointerX: Double? = null,
) {
    val lineLines = coachLineLines(line, width)
    val h = coachHeight(line, note, width)
    container {
        xy(x, y)
        roundRect(Size(width, h), RectCorners(14.0), fill = coachBg)
        pointerX?.let { px ->
            val tx = px - x
            graphics {
                fill(coachBg) {
                    moveTo(tx - 13.0, h - 1.0)
                    lineTo(tx + 13.0, h - 1.0)
                    lineTo(tx, h + 16.0)
                    close()
                }
            }
        }
        text(title, 21.0, footerColor, font) {
            setTextBounds(Rectangle(0.0, 0.0, width, 28.0))
            alignment = TextAlignment.MIDDLE_CENTER
            this.y = 14.0
        }
        lineLines.forEachIndexed { i, ln ->
            text(ln, 19.0, cardBody, font) {
                setTextBounds(Rectangle(0.0, 0.0, width, 26.0))
                alignment = TextAlignment.MIDDLE_CENTER
                this.y = 46.0 + i * 26.0
            }
        }
        if (note != null) {
            val noteY = 46.0 + lineLines.size * 26.0 + 6.0
            coachNoteLines(note, width).forEachIndexed { i, ln ->
                text(ln, 14.0, cardMuted, font) {
                    setTextBounds(Rectangle(0.0, 0.0, width, 19.0))
                    alignment = TextAlignment.MIDDLE_CENTER
                    this.y = noteY + i * 19.0
                }
            }
        }
    }
}

/**
 * Pulses a translucent red wash over the rectangle at ([x], [y]) sized [w] x [h] — used to make
 * the target blocks' backgrounds throb. The wash is confined to exactly that rectangle, so
 * adjacent highlights never overlap or bleed into one another. It stops (and hides) as soon as
 * [active] returns false — e.g. once a highlighted power-up has been selected.
 */
private fun Container.pulseHighlight(
    x: Double,
    y: Double,
    w: Double,
    h: Double,
    corner: Double,
    active: () -> Boolean = { true },
) {
    container {
        xy(x, y)
        val wash = roundRect(Size(w, h), RectCorners(corner), fill = highlightColor)
        var t = 0.0
        addUpdater { dt ->
            if (!active()) {
                wash.alpha = 0.0
                return@addUpdater
            }
            t += dt.seconds
            // Quick, pronounced pulse so it is obvious these are the blocks to select.
            wash.alpha = 0.10 + 0.58 * (0.5 + 0.5 * sin(t * 8.0))
        }
    }
}

/** Replaces the blocks at [positions] with fresh blocks of [number], redrawing them in place. */
private fun Stage.scriptTutorialCells(positions: List<Position>, number: Rank) {
    positions.forEach { position ->
        blocksMap[position]?.let { deleteBlock(it) }
        val block = Block(nextBlockId, number)
        nextBlockId++
        drawBlock(block, position)
    }
}

// ---- Static read-only "How to play" guide (opened from the pause menu) ---------------------

/**
 * Builds the swipeable read-only how-to guide. Closed only via its own buttons; [onClose] runs
 * on close so the caller can restore whatever it hid behind the guide. The card sizes itself to
 * each page's content, so it is rebuilt from scratch on every page change.
 */
fun Stage.showHowToPlay(onClose: () -> Unit = {}): Container {
    val pages = infoPages()
    var index = 0

    val pad = 18.0
    val navH = 54.0
    val contentWidth = 316.0

    return container {
        // Backdrop: no onClick, so it cannot accidentally dismiss the pause menu underneath.
        solidRect(views.virtualWidth * 6.0, views.virtualHeight * 6.0, overlayDim) {
            xy(-views.virtualWidth * 2.5, -views.virtualHeight * 2.5)
        }

        fun close() {
            this@container.removeFromParent()
            onClose()
        }

        val cardRoot = container { }

        fun render() {
            cardRoot.removeChildren()
            cardRoot.apply {
                val content = buildInfoCard(contentWidth, pages[index])
                val cardW = contentWidth + pad * 2
                val cardH = pad + content.height + navH

                val card =
                    roundRect(Size(cardW, cardH), RectCorners(20.0), fill = cardBg) {
                        centerXOn(gameField)
                        centerYOn(gameField)
                    }
                content.addTo(this).xy(card.x + pad, card.y + pad)

                // Close (X).
                text("X", 22.0, cardBody, font) {
                    setTextBounds(Rectangle(0.0, 0.0, 38.0, 38.0))
                    alignment = TextAlignment.MIDDLE_CENTER
                    xy(card.x + cardW - 42.0, card.y + 6.0)
                    onClick { close() }
                }

                val navTop = card.y + cardH - navH
                text("${index + 1} / ${pages.size}", 13.0, cardMuted, font) {
                    setTextBounds(Rectangle(0.0, 0.0, cardW, 22.0))
                    alignment = TextAlignment.MIDDLE_CENTER
                    xy(card.x, navTop + 17.0)
                }
                if (index > 0) {
                    text("< BACK", 17.0, cardAccent, font) {
                        setTextBounds(Rectangle(0.0, 0.0, 110.0, navH))
                        alignment = TextAlignment.MIDDLE_CENTER
                        xy(card.x + pad, navTop)
                        onClick {
                            index--
                            render()
                        }
                    }
                }
                text(if (index == pages.lastIndex) "DONE" else "NEXT >", 17.0, cardAccent, font) {
                    setTextBounds(Rectangle(0.0, 0.0, 110.0, navH))
                    alignment = TextAlignment.MIDDLE_CENTER
                    xy(card.x + cardW - pad - 110.0, navTop)
                    onClick {
                        if (index < pages.lastIndex) {
                            index++
                            render()
                        } else {
                            close()
                        }
                    }
                }
            }
        }

        render()
    }
}

// ---- Page content --------------------------------------------------------------------------

private class InfoPage(
    val title: String,
    val body: List<String>,
    val diagram: (Container.() -> Unit)? = null,
)

private fun welcomePage() =
    InfoPage("WELCOME TO TRIPLO", listOf("Merge matching blocks to build bigger numbers"))

private fun undoPage() =
    InfoPage(
        "UNDO",
        listOf(
            "Made a mistake? Open the pause menu and tap " +
                "UNDO to take back your last move.",
            "You get ${Undo.maxUndosPerRound} undos each round.",
        ),
    )

private fun infoPages(): List<InfoPage> =
    listOf(
        InfoPage(
            "MERGING",
            listOf(
                "Drag across 3 or more touching blocks of the " +
                    "same number to merge them one tier higher.",
                "Moves go up, down, left and right - never " +
                    "diagonally.",
            ),
            diagram = { mergeDiagram() },
        ),
        InfoPage(
            "MERGE RESULTS",
            listOf(
                "Bigger chains climb higher.",
                "Lines and boxes follow special rules - shown " +
                    "on the next pages.",
            ),
            diagram = { mergeResultsDiagram() },
        ),
        InfoPage(
            "LINES",
            listOf(
                "Longer chains climb faster: a chain of 6+ jumps " +
                    "two tiers, 18+ jumps three.",
                "A straight line splits into several upgraded " +
                    "blocks at once.",
            ),
            diagram = { lineDiagram() },
        ),
        InfoPage(
            "SQUARES",
            listOf(
                "Fill a square or rectangle and merge it all at " +
                    "once.",
                "The longer its shorter side, the higher it " +
                    "climbs - and it forges several blocks.",
            ),
            diagram = { squareDiagram() },
        ),
        InfoPage(
            "BOMBS",
            listOf(
                "Forge a 243 block or higher to earn a bomb " +
                    "(hold up to 5).",
                "Tap the bomb, then a block, to blast that block " +
                    "and the 8 around it.",
            ),
            diagram = { bombDiagram() },
        ),
        InfoPage(
            "ROCKETS",
            listOf(
                "Select a chain of 8 or more blocks in one merge " +
                    "to earn a rocket (hold up to 5).",
                "Tap the rocket, then two blocks, to swap their " +
                    "positions.",
            ),
        ),
        undoPage(),
        InfoPage(
            "STAYING ALIVE",
            listOf(
                "The game ends only when no merges remain and you " +
                    "have no bombs or rockets left.",
                "If you get stuck, a held power-up jiggles to " +
                    "remind you.",
            ),
        ),
    )

// ---- Page rendering ------------------------------------------------------------------------

/** Lays a page's title, optional diagram and body into a fresh container [width] wide. */
private fun buildInfoCard(width: Double, page: InfoPage): Container =
    Container().apply {
        var y = 0.0
        for (titleLine in wrap(page.title, 22)) {
            text(titleLine, 28.0, cardAccent, font) {
                setTextBounds(Rectangle(0.0, 0.0, width, 36.0))
                alignment = TextAlignment.MIDDLE_CENTER
                this.y = y
            }
            y += 39.0
        }
        if (y > 0.0) y += 12.0
        page.diagram?.let { draw ->
            val dia = container { draw() }
            dia.x = (width - dia.width) / 2.0
            dia.y = y
            y += dia.height + 18.0
        }
        // Wrap the body so each line fills the available width at the body font size.
        val bodyChars = maxOf(16, (width / 10.0).toInt())
        for (paragraph in page.body) {
            for (line in wrap(paragraph, bodyChars)) {
                text(line, 20.0, cardBody, font) {
                    setTextBounds(Rectangle(0.0, 0.0, width, 27.0))
                    alignment = TextAlignment.MIDDLE_CENTER
                    this.y = y
                }
                y += 27.0
            }
            y += 11.0
        }
    }

/** Greedy word-wrap to at most [maxChars] characters per line. */
private fun wrap(text: String, maxChars: Int): List<String> {
    val lines = mutableListOf<String>()
    var current = ""
    for (word in text.split(" ")) {
        current =
            when {
                current.isEmpty() -> word
                current.length + 1 + word.length <= maxChars -> "$current $word"
                else -> {
                    lines.add(current)
                    word
                }
            }
    }
    if (current.isNotEmpty()) lines.add(current)
    return lines
}

private fun Container.miniBlock(number: Rank, size: Double) =
    container {
        roundRect(Size(size, size), RectCorners(4.0), fill = number.color)
        // Centre on the glyph's actual ink (like Block.drawRank), since KorGE's text
        // alignment centres the advance box and leaves "1" looking off-centre.
        val label = text(number.display, size * 0.42, number.TextColor, font)
        val bounds = label.getLocalBounds()
        label.xy(
            size / 2.0 - bounds.x - bounds.width / 2.0,
            size / 2.0 - bounds.y - bounds.height / 2.0,
        )
    }

private fun Container.diagramArrow(x: Double, cy: Double) {
    text(">", 24.0, footerColor, font) {
        setTextBounds(Rectangle(0.0, 0.0, 24.0, 32.0))
        alignment = TextAlignment.MIDDLE_CENTER
        xy(x, cy - 16.0)
    }
}

/** Three tier-1 blocks merging into one tier-2 block. */
private fun Container.mergeDiagram() {
    val s = 32.0
    val gap = 6.0
    var x = 0.0
    repeat(3) {
        miniBlock(ZERO, s).xy(x, 0.0)
        x += s + gap
    }
    diagramArrow(x + 2.0, s / 2.0)
    x += 30.0
    miniBlock(ONE, s).xy(x, 0.0)
}

/** Three rows showing how chain size maps to the resulting tier (+1 / +2 / +3). */
private fun Container.mergeResultsDiagram() {
    val s = 30.0
    val rowGap = 8.0
    val labelW = 72.0
    val arrowW = 28.0
    val rows =
        listOf(
            "3-5" to ONE,
            "6-17" to TWO,
            "18+" to THREE,
        )
    var y = 0.0
    for ((label, rank) in rows) {
        miniBlock(ZERO, s).xy(0.0, y)
        text(label, 17.0, cardBody, font) {
            setTextBounds(Rectangle(0.0, 0.0, labelW, s))
            alignment = TextAlignment.MIDDLE_CENTER
            xy(s + 6.0, y)
        }
        diagramArrow(s + 6.0 + labelW, y + s / 2.0)
        miniBlock(rank, s).xy(s + 6.0 + labelW + arrowW, y)
        y += s + rowGap
    }
}

/** A line of five tier-1 blocks merging up. */
private fun Container.lineDiagram() {
    val s = 26.0
    val gap = 5.0
    var x = 0.0
    repeat(5) {
        miniBlock(ZERO, s).xy(x, 0.0)
        x += s + gap
    }
    diagramArrow(x + 2.0, s / 2.0)
    x += 26.0
    miniBlock(TWO, s).xy(x, 0.0)
}

/** A 2x2 square of tier-1 blocks merging into two tier-2 blocks. */
private fun Container.squareDiagram() {
    val s = 30.0
    val gap = 5.0
    for (j in 0 until 2) {
        for (i in 0 until 2) {
            miniBlock(ZERO, s).xy(i * (s + gap), j * (s + gap))
        }
    }
    diagramArrow(2 * (s + gap) + 4.0, s + gap / 2.0)
    // A 2x2 merge forges two upgraded blocks, not one.
    val rx = 2 * (s + gap) + 30.0
    miniBlock(ONE, s).xy(rx, 0.0)
    miniBlock(ONE, s).xy(rx, s + gap)
}

/** A 3x3 grid with the blasted centre highlighted. */
private fun Container.bombDiagram() {
    val s = 26.0
    val gap = 4.0
    for (j in 0 until 3) {
        for (i in 0 until 3) {
            val isCentre = i == 1 && j == 1
            roundRect(
                Size(s, s),
                RectCorners(4.0),
                fill = if (isCentre) RGBA(167, 29, 49, 255) else RGBA(70, 72, 86, 255),
                stroke = if (isCentre) highlightColor else Colors.TRANSPARENT,
                strokeThickness = 3.0,
            ) {
                xy(i * (s + gap), j * (s + gap))
            }
        }
    }
}
