import korlibs.korge.*
import korlibs.korge.input.*
import korlibs.korge.service.storage.storage
import korlibs.korge.view.*
import korlibs.korge.view.align.*
import korlibs.image.color.*
import korlibs.image.font.*
import korlibs.image.bitmap.*
import korlibs.image.format.*
import korlibs.image.text.TextAlignment
import korlibs.io.async.ObservableProperty
import korlibs.io.async.launchImmediately
import korlibs.io.file.std.*
import kotlinx.coroutines.delay
import korlibs.time.*
import korlibs.math.geom.*
import korlibs.math.geom.vector.*
import kotlin.properties.Delegates
import kotlin.random.Random

val score = ObservableProperty(0)
val best = ObservableProperty(0)

// Gravity mode is a distinct game mode with its own high score; both the toggle and the gravity
// best are persisted, and a score only ever counts toward the mode that was active when it was set.
val gravityBest = ObservableProperty(0)
val gravityModeEnabled = ObservableProperty(false)

// The shareable emoji layout captured when the best score was last set. Loaded from
// storage at boot and refreshed by maybeCaptureBestShare(); tapping the BEST box copies it.
// Gravity mode keeps its own captured layout so the two modes never copy each other's record.
var bestShareMessage: String? = null
var gravityBestShareMessage: String? = null

/** The captured record-layout share string for whichever mode is currently active. */
fun activeBestShareMessage(): String? = if (gravityModeEnabled.value) gravityBestShareMessage else bestShareMessage

var gridColumns: Int = 7
var gridRows: Int = 7

val random27ID = Random.nextInt(0, gridRows * gridColumns - 1)

var cellIndentSize: Int = 8
var cellSize: Int = 0
var fieldWidth: Int = 0
var fieldHeight: Int = 0
var leftIndent: Int = 0
var topIndent: Int = 0
var nextBlockId = 0

var fieldSize: Double = 0.0

var isPressed = false
var isBombPressed = false

var font: BitmapFont by Delegates.notNull()

var blocksMap: MutableMap<Position, Block> = mutableMapOf()

var hoveredPositions: MutableList<Position> = mutableListOf()
var hoveredBombPositions: MutableList<Position> = mutableListOf()

var isAnimating: Boolean = false

fun startAnimating() {
    isAnimating = true
}

fun stopAnimating() {
    isAnimating = false
}

// Idle hint: when the board has no available moves but the player still holds a
// bomb or rocket, the held power-up(s) jiggle after idleHintDelay seconds without
// a move to signal that a power-up must be used to keep playing. The timer resets
// on every board touch and re-fires every idleHintDelay seconds while still stuck.
const val idleHintDelay = 5.0
var idleTime = 0.0
var nextIdleHintTime = idleHintDelay

fun resetIdleTimer() {
    idleTime = 0.0
    nextIdleHintTime = idleHintDelay
}

var showingRestart: Boolean = false
var restartPopupContainer: Container = Container()
// The settings sub-page floats on top of the pause menu. Holding a reference lets the pause
// button toggle it off again if it is showing.
var settingsPopupContainer: Container = Container()
var showingSettings: Boolean = false

/** Storage key for the haptics on/off preference. */
const val hapticsEnabledKey = "hapticsEnabled"

/** Storage keys for gravity mode: its on/off preference and its dedicated high score. */
const val gravityModeEnabledKey = "gravityModeEnabled"
const val gravityBestKey = "gravityBest"

/** Above this score, switching game mode warns first (it discards the current run). */
const val gravityConfirmScoreThreshold = 100

const val startingBombCount = 1
const val maxBombCount = 5
var bombsLoadedCount = ObservableProperty(startingBombCount)
var bombSelected = false
var bombContainer: Container = Container()

const val startingRocketCount = 1
const val maxRocketCount = 5
const val rocketPowerUpLength = 8
var rocketsLoadedCount = ObservableProperty(startingRocketCount)
var rocketSelection = RocketSelection()
var rocketContainer: Container = Container()

var bombScaleNormal = 0.0
var bombScaleSelected = 0.0
var rocketScaleNormal = 0.0
var rocketScaleSelected = 0.0

var blockScaleNormal = 0.0
var blockScaleSelected = 0.0

var gameField = RoundRect(Size(0, 0), RectCorners(0))

const val smallSelectionSize = 3
const val mediumSelectionSize = 6
const val largeSelectionSize = 18

// One inventory cartridge slot: a white-bordered rounded rect rendered with
// fastRoundRect. fastRoundRect is a GPU SDF shape, so it stays crisp at any
// scale or sub-pixel offset, unlike roundRect which rasterizes to a texture
// that blurs when the scene is upscaled to the device's native resolution.
// Note: fastRoundRect corners are a ratio of the size (0..1), not pixels.
fun Container.cartridgeSlot(fill: RGBA): Container =
    container {
        val w = cellSize * 0.4
        val h = cellSize * 0.4
        val border = cellSize / 24.0
        val cornerRatio = 0.3
        fastRoundRect(Size(w, h), RectCorners(cornerRatio), color = cartridgeBorderColor)
        fastRoundRect(Size(w - border * 2, h - border * 2), RectCorners(cornerRatio), color = fill) {
            xy(border, border)
        }
    }

suspend fun main() =
    Korge(
        windowSize = Size(360, 640),
        title = "Triplo",
        backgroundColor = RGBA(253, 247, 240),
        // CENTER_NO_CLIP keeps the game letterboxed but does not clip the borders, so the
        // background can bleed past the virtual area to fill any phone aspect ratio (issue 5).
        displayMode = KorgeDisplayMode.CENTER_NO_CLIP,
    ) {
        Napier.base(DebugAntilog())

        // Wire up interstitial ads (real on Android, no-op elsewhere) and start preloading one.
        views.installPlatformAds()

        // Wire up haptic feedback (real on Android/iOS, no-op elsewhere).
        views.installPlatformHaptics()

        // Game font is needed by the studio intro too, so load it before anything that uses it.
        font = resourcesVfs["clear_sans.fnt"].readBitmapFont()

        // Storage holds the best score and the haptics/SFX preferences. Read it before the intro
        // so the moo honours the player's SFX mute, and reuse the same handle for the rest of boot.
        val storage = views.storage
        // Restore the chosen color theme before any view is built, so the very first frame (and the
        // background gradient set up below) is already themed. No observers exist yet, so this only
        // sets the value.
        colorTheme.update(
            runCatching { ColorTheme.valueOf(storage.getOrNull(colorThemeKey) ?: ColorTheme.BASIC.name) }
                .getOrDefault(ColorTheme.BASIC),
        )
        Sfx.enabled = storage.getOrNull(sfxEnabledKey)?.toBoolean() ?: true
        Sfx.load(coroutineContext)

        // Studio intro: ~3s drop-in / triplicate / fade-in of "ALL MEAT GAMES" with a moo.
        // Runs before any game view is drawn so nothing else is visible underneath, and
        // suspends until complete — there is no way to skip it.
        playIntroAnimation()

        // Triangle art plus slowly drifting glow orbs; also wires up the colour
        // wash that fires when a high-tier block is forged. See Background.kt.
        setupBackground()

        best.update(storage.getOrNull("best")?.toInt() ?: 0)
        gravityBest.update(storage.getOrNull(gravityBestKey)?.toInt() ?: 0)
        // Restore the gravity-mode preference (defaults to off).
        gravityModeEnabled.update(storage.getOrNull(gravityModeEnabledKey)?.toBoolean() ?: false)

        // Restore the layout that earned the current best score so tapping the BEST box can
        // copy it even on a fresh launch (null until the player first sets a record). Each mode
        // keeps its own captured layout.
        bestShareMessage = storage.getOrNull("bestShare")
        gravityBestShareMessage = storage.getOrNull("gravityBestShare")

        // Restore the haptics on/off preference (defaults to on); toggled from the pause menu.
        Haptics.enabled = storage.getOrNull(hapticsEnabledKey)?.toBoolean() ?: true

        // A new best is routed to whichever mode is active, so classic and gravity never mix.
        score.observe {
            val modeBest = if (gravityModeEnabled.value) gravityBest else best
            if (it > modeBest.value) modeBest.update(it)
        }
        best.observe {
            storage["best"] = it.toString()
        }
        gravityBest.observe {
            storage[gravityBestKey] = it.toString()
        }

        cellSize = views.virtualWidth / (gridColumns + 2)
        Napier.d("Cell size = $cellSize")
        fieldWidth = (cellIndentSize * (gridColumns + 1)) + gridColumns * cellSize
        Napier.d("Field width = $fieldWidth")
        fieldHeight = (cellIndentSize * (gridRows + 1)) + gridRows * cellSize
        Napier.d("Field height = $fieldHeight")
        leftIndent = (views.virtualWidth - fieldWidth) / 2
        Napier.d("Left indent = $leftIndent")
        topIndent = 128

        // The grid is fixed in place; the score row and the power-up row are centred in
        // the gaps above and below it. CENTER_NO_CLIP lets the visible screen extend past
        // the 360x640 virtual area, so use actualVirtualBounds (the real screen, expressed
        // in virtual coordinates) rather than the virtual height to find the screen edges.
        val screenTop = views.actualVirtualBounds.top
        val screenBottom = views.actualVirtualBounds.bottom
        val fieldBottom = topIndent + fieldHeight

        // Score row: the pause button, SCORE and BEST boxes share a vertical centre.
        // The boxes (cellSize * 1.5 tall) are the tallest element and set the row height.
        val scoreRowCenterY = (screenTop + topIndent) / 2.0
        val scoreRowTop = scoreRowCenterY - (cellSize * 1.5) / 2.0

        // Power-up row: bomb/rocket containers (cellSize * 2.5 tall) stacked above their
        // cartridge strip (cellSize * 0.5 tall) with a 2px gap between the two.
        val powerUpRowHeight = cellSize * 3.0 + 2.0
        val powerUpRowTop = (fieldBottom + screenBottom) / 2.0 - powerUpRowHeight / 2.0

        gameField =
            roundRect(Size(fieldWidth, fieldHeight), RectCorners(5), fill = gameFieldColor) {
                this.position(leftIndent, topIndent)

                touch {
                    start { handleDown(it.global) }
                    move { handleHover(it.global) }
                }
            }
        // Re-tint the field on a theme switch.
        colorTheme.observe { gameField.fill = gameFieldColor }

        // Cells are drawn opaque-white and tinted through colorMul, so a theme switch is just a
        // colorMul update — no shape rebuild, and the z-order beneath the blocks stays intact.
        val cellsLayer =
            graphics {
                fill(Colors.WHITE) {
                    for (i in 0 until gridColumns) {
                        for (j in 0 until gridRows) {
                            roundRect(
                                (cellIndentSize + (cellIndentSize + cellSize) * i).toDouble(),
                                (cellIndentSize + (cellIndentSize + cellSize) * j).toDouble(),
                                cellSize.toDouble(),
                                cellSize.toDouble(),
                                5.0,
                            )
                        }
                    }
                }
            }.xy(leftIndent, topIndent)
        cellsLayer.colorMul = cellColor
        colorTheme.observe { cellsLayer.colorMul = cellColor }

        val btnSize = cellSize * 1.0
        val restartBlock =
            container {
                val backgroundBlock = roundRect(Size(btnSize, btnSize), RectCorners(5), fill = restartAndScoreColor)
                var pause = pauseIcon(btnSize * 0.6, scoreTextColor)
                pause.centerOn(backgroundBlock)
                colorTheme.observe {
                    backgroundBlock.fill = restartAndScoreColor
                    pause.removeFromParent()
                    pause = pauseIcon(btnSize * 0.6, scoreTextColor)
                    pause.centerOn(backgroundBlock)
                }
                alignLeftToLeftOf(gameField, cellSize * 0.5)
                // Centre the (shorter) pause button on the score row's vertical centre.
                y = scoreRowCenterY - btnSize / 2.0
                onClick {
                    if (!tutorialAllowsPause()) return@onClick
                    if (!showingRestart) {
                        unselectAllPowerUps()
                        restartPopupContainer = this@Korge.showRestart {
                            // Show an interstitial when the player restarts from the pause menu.
                            this@Korge.launchImmediately {
                                Ads.showInterstitial()
                                this@Korge.restart()
                            }
                        }
                        Napier.d("Restart Button Clicked")
                    } else
                        {
                            Napier.d("Restart Button Clicked when already showing restart")
                            showingRestart = false
                            restartPopupContainer.removeFromParent()
                        }
                }
            }

        val bgScore =
            roundRect(Size(cellSize * 2.5, cellSize * 1.5), RectCorners(5), fill = restartAndScoreColor) {
                alignLeftToRightOf(restartBlock, cellSize)
                y = scoreRowTop
            }
        val scoreLabel = text("SCORE", cellSize * 0.5, scoreTextColor, font) {
            centerXOn(bgScore)
            alignTopToTopOf(bgScore, 3.0)
        }

        val scoreValue = text(score.value.toString(), cellSize * 1.0, scoreTextColor, font) {
            setTextBounds(Rectangle(0.0, 0.0, bgScore.width, cellSize * 0.5))
            alignment = TextAlignment.MIDDLE_CENTER
            centerXOn(bgScore)
            alignTopToTopOf(bgScore, 5.0 + (cellSize * 0.5) + 5.0)
            score.observe {
                text = it.toString()
            }
        }
        colorTheme.observe {
            bgScore.fill = restartAndScoreColor
            scoreLabel.color = scoreTextColor
            scoreValue.color = scoreTextColor
        }

        val bgBest =
            roundRect(Size(cellSize * 2.5, cellSize * 1.5), RectCorners(5), fill = restartAndScoreColor) {
                alignRightToRightOf(gameField, 12.0)
                y = scoreRowTop
            }
        val bestLabel = text("BEST", cellSize * 0.5, scoreTextColor, font) {
            centerXOn(bgBest)
            alignTopToTopOf(bgBest, 3.0)
        }
        // The BEST box shows whichever mode is active; switching modes swaps the value live.
        fun activeBestValue() = if (gravityModeEnabled.value) gravityBest.value else best.value
        val bestValueText = text(activeBestValue().toString(), cellSize * 1.0, scoreTextColor, font) {
            setTextBounds(Rectangle(0.0, 0.0, bgBest.width, cellSize * 0.5))
            alignment = TextAlignment.MIDDLE_CENTER
            alignTopToTopOf(bgBest, 5.0 + (cellSize * 0.5) + 5.0)
            centerXOn(bgBest)
            fun refresh() { text = activeBestValue().toString() }
            best.observe { if (!gravityModeEnabled.value) refresh() }
            gravityBest.observe { if (gravityModeEnabled.value) refresh() }
            gravityModeEnabled.observe { refresh() }
        }
        // Flashes in place of the score number while the saved layout is being copied.
        val bestCopiedText = text("COPIED", cellSize * 0.6, scoreTextColor, font) {
            setTextBounds(Rectangle(0.0, 0.0, bgBest.width, cellSize * 0.5))
            alignment = TextAlignment.MIDDLE_CENTER
            alignTopToTopOf(bgBest, 5.0 + (cellSize * 0.5) + 5.0)
            centerXOn(bgBest)
            visible = false
        }
        // Tapping BEST copies the saved record layout to the clipboard and briefly flashes
        // "COPIED" over the score, mirroring the SHARE button's confirmation.
        var bestCopiedShowing = false
        bgBest.onClick {
            val msg = activeBestShareMessage() ?: return@onClick
            if (bestCopiedShowing) return@onClick
            bestCopiedShowing = true
            Napier.d("BEST box tapped - copying saved layout")
            stage?.views?.copyTextToClipboard(msg)
            bestValueText.visible = false
            bestCopiedText.visible = true
            bgBest.fill = pauseScreenBlockCopiedColor
            stage?.views?.launchImmediately {
                delay(1200L)
                bestCopiedText.visible = false
                bestValueText.visible = true
                bgBest.fill = restartAndScoreColor
                bestCopiedShowing = false
            }
        }
        // Small downward arrow in the corner of the BEST box, shown only while gravity mode is
        // active, so it is clear which mode's high score is on display.
        var gravityModeIcon = gravityIcon(cellSize * 0.42, scoreTextColor)
        fun placeGravityModeIcon() {
            gravityModeIcon.alignTopToTopOf(bgBest, 4.0)
            gravityModeIcon.alignLeftToLeftOf(bgBest, 4.0)
            gravityModeIcon.visible = gravityModeEnabled.value
        }
        placeGravityModeIcon()
        // The single visibility observer reads the captured var, so it keeps working after a theme
        // switch rebuilds the icon below.
        gravityModeEnabled.observe { gravityModeIcon.visible = it }
        colorTheme.observe {
            bgBest.fill = restartAndScoreColor
            bestLabel.color = scoreTextColor
            bestValueText.color = scoreTextColor
            bestCopiedText.color = scoreTextColor
            // The gravity-mode glyph bakes its color, so rebuild it in the new ink.
            gravityModeIcon.removeFromParent()
            gravityModeIcon = gravityIcon(cellSize * 0.42, scoreTextColor)
            placeGravityModeIcon()
        }

        val emptyBombImg = resourcesVfs["emptyBomb.png"].readBitmap()
        val loadedBombImg = resourcesVfs["bomb.png"].readBitmap()
        val emptyRocketImg = resourcesVfs["emptyRocket.png"].readBitmap()
        val loadedRocketImg = resourcesVfs["rocket.png"].readBitmap()

        bombContainer =
            container {
                val bombBackground = roundRect(Size(cellSize * 2.5, cellSize * 2.5), RectCorners(10), fill = bombContainerColor)
                y = powerUpRowTop
                alignLeftToLeftOf(gameField, fieldWidth / 8)
                fun redrawBombIcon() {
                    this.removeChildrenIf { index, _ -> index == 1 }
                    bombIcon(cellSize * 2.2, bombsLoadedCount.value > 0) { bombSelected }
                        .centerOn(bombBackground)
                }
                redrawBombIcon()
                onClick {
                    if (bombsLoadedCount.value > 0 && !showingRestart && tutorialAllowsBombTap()) {
                        bombSelected = !bombSelected
                        if (bombSelected) Haptics.tap()
                        // Selecting the bomb cancels an in-progress rocket selection.
                        if (bombSelected && rocketSelection.selected) {
                            animatePowerUpSelection(rocketContainer, false)
                            removeRocketSelection()
                        }
                        animatePowerUpSelection(this, bombSelected)
                    }
                }
                bombsLoadedCount.observe { redrawBombIcon() }
                colorTheme.observe {
                    bombBackground.fill = bombContainerColor
                    redrawBombIcon()
                }
            }

        container {
            fun drawCartridges() {
                var prev: View? = null
                for (i in 0 until maxBombCount) {
                    val fill = if (bombsLoadedCount.value > i) loadedBombCartridgeColor else emptyCartridgeColor
                    val slot = cartridgeSlot(fill)
                    prev?.let { slot.alignLeftToRightOf(it, cellSize * 0.16) }
                    prev = slot
                }
            }

            drawCartridges()

            // Position the row only after the cartridges exist: aligning an empty
            // container yields NaN coordinates and the row never renders.
            centerXOn(bombContainer)
            alignTopToBottomOf(bombContainer, 2)

            bombsLoadedCount.observe {
                removeChildren()
                drawCartridges()
            }
            colorTheme.observe {
                removeChildren()
                drawCartridges()
            }
        }

        rocketContainer =
            container {
                val rocketBackground = roundRect(Size(cellSize * 2.5, cellSize * 2.5), RectCorners(10), fill = rocketContainerColor)
                y = powerUpRowTop
                alignRightToRightOf(gameField, fieldWidth / 8)
                fun redrawRocketIcon() {
                    this.removeChildrenIf { index, _ -> index == 1 }
                    rocketIcon(cellSize * 2.3, rocketsLoadedCount.value > 0) { rocketSelection.selected }
                        .centerOn(rocketBackground)
                }
                redrawRocketIcon()
                onClick {
                    if (rocketsLoadedCount.value > 0 && !showingRestart && tutorialAllowsRocketTap()) {
                        rocketSelection.toggleSelect()
                        if (rocketSelection.selected) Haptics.tap()
                        // Selecting the rocket cancels a selected bomb.
                        if (rocketSelection.selected && bombSelected) {
                            bombSelected = false
                            animatePowerUpSelection(bombContainer, false)
                        }
                        animatePowerUpSelection(this, rocketSelection.selected)
                        if (!rocketSelection.selected) removeRocketSelection()
                    }
                }
                rocketsLoadedCount.observe { redrawRocketIcon() }
                colorTheme.observe {
                    rocketBackground.fill = rocketContainerColor
                    redrawRocketIcon()
                }
            }

        container {
            fun drawCartridges() {
                var prev: View? = null
                for (i in 0 until maxRocketCount) {
                    val fill = if (rocketsLoadedCount.value > i) loadedRocketCartridgeColor else emptyCartridgeColor
                    val slot = cartridgeSlot(fill)
                    prev?.let { slot.alignLeftToRightOf(it, cellSize * 0.16) }
                    prev = slot
                }
            }

            drawCartridges()

            // Position the row only after the cartridges exist: aligning an empty
            // container yields NaN coordinates and the row never renders.
            centerXOn(rocketContainer)
            alignTopToBottomOf(rocketContainer, 2)

            rocketsLoadedCount.observe {
                removeChildren()
                drawCartridges()
            }
            colorTheme.observe {
                removeChildren()
                drawCartridges()
            }
        }

        bombScaleNormal = bombContainer.scale
        bombScaleSelected = bombScaleNormal * 1.2
        rocketScaleNormal = rocketContainer.scale
        rocketScaleSelected = rocketScaleNormal * 1.2

        Napier.d("UI Initialized")

        // On first launch (no "tutorialSeen" flag yet) the interactive tutorial runs, scripting
        // its own cells onto this board step by step.
        val isFirstLaunch = storage.getOrNull(tutorialSeenKey) == null
        blocksMap = initializeRandomBlocksMap()
        drawAllBlocks()
        // Recolor every block when the theme changes (the picker is open, so the board is idle).
        colorTheme.observe { this@Korge.refreshBoardColors() }

        blockScaleNormal = blocksMap[Position(0, 0)]!!.scale
        blockScaleSelected = blockScaleNormal * 1.2

        touch {
            end { handleUp(it.global) }
        }

        // Idle hint loop: count up time since the last board touch and, once the
        // board is stuck with no available moves, jiggle any held power-up.
        addUpdater { dt ->
            if (isAnimating || showingRestart || tutorialActive) return@addUpdater
            idleTime += dt.seconds
            if (idleTime >= nextIdleHintTime) {
                nextIdleHintTime += idleHintDelay
                checkIdleHint()
            }
        }

        // On the very first launch, walk the player through a scripted merge, bomb and rocket,
        // then mark the tutorial seen and deal a fresh random board.
        if (isFirstLaunch) {
            startInteractiveTutorial {
                storage[tutorialSeenKey] = "true"
                restart()
            }
        }
}

fun Stage.unselectAllPowerUps() {
    if (bombSelected) {
        bombSelected = false
        animatePowerUpSelection(bombContainer, false)
    }
    if (rocketSelection.selected)
        {
            rocketSelection.unselect()
            animatePowerUpSelection(rocketContainer, false)
        }
}

/**
 * Builds the shareable message for a board: the emoji grid (row by row, skipping empty
 * cells), then the score rendered as keycap-digit emoji, then the call to action. Shared by
 * the SHARE button (current board) and the BEST box (the saved record layout).
 */
fun buildShareMessage(blocks: Map<Position, Block>, scoreValue: Int, gravity: Boolean): String {
    var gridString = ""
    for (j in 0 until gridRows) {
        for (i in 0 until gridColumns) {
            val block = blocks[Position(i, j)]
            if (block != null) {
                gridString = gridString + block.number.emoji
            }
        }
        gridString = gridString + "\n"
    }

    // Render the score with keycap-digit emoji, e.g. 1️⃣2️⃣3️⃣.
    val scoreEmoji = scoreValue.toString()
        .map { digit -> "$digit️⃣" }
        .joinToString("")

    // A gravity-mode run is flagged with a header line above the grid.
    val header = if (gravity) "⬇️ GRAVITY MODE ⬇️\n" else ""

    // Optional mode header, the emoji grid, then the score, then the call to action.
    return header + gridString + "Score: " + scoreEmoji + "\nJoin the triplo.club"
}

/**
 * When the board has settled at an all-time-high score, snapshot its layout as the shareable
 * "best" message so tapping the BEST box later copies this exact board. Called from
 * [checkGameOver], the one place guaranteed to run on a fully settled (merged + refilled) board.
 * Skips while the score is below the best (a record set in an earlier game still stands).
 */
fun Stage.maybeCaptureBestShare() {
    // Capture against the active mode's best so the two modes keep independent record layouts.
    val modeBest = if (gravityModeEnabled.value) gravityBest else best
    if (modeBest.value <= 0 || score.value != modeBest.value) return
    val msg = buildShareMessage(blocksMap, modeBest.value, gravityModeEnabled.value)
    if (gravityModeEnabled.value) {
        gravityBestShareMessage = msg
        views.storage["gravityBestShare"] = msg
    } else {
        bestShareMessage = msg
        views.storage["bestShare"] = msg
    }
    Napier.d("Captured ${if (gravityModeEnabled.value) "gravity " else ""}best-score share layout for score ${modeBest.value}")
}

fun Container.showRestart(isGameOver: Boolean = false, onRestart: () -> Unit) =
    container {
        // Capture the outer container so nested button blocks (which shadow `this@container` with
        // their own inner container scope) can still address the pause popup itself.
        val pausePopup = this
        showingRestart = true
        Napier.d("Showing Restart Container...")

        fun restart() {
            this@container.removeFromParent()
            onRestart()
        }

        fun copyBlocksToClipboard() {
            val clipboardContent = buildShareMessage(blocksMap, score.value, gravityModeEnabled.value)
            Napier.d("Clipboard Content:\n $clipboardContent")
            stage?.views?.copyTextToClipboard(clipboardContent)
        }

        // Tapping anywhere outside the menu buttons resumes the game. The dark backdrop below
        // only covers the board, so this invisible full-screen catcher sits behind it to also
        // pick up taps above and below the grid (the score and power-up rows). The game over
        // screen has no game left to return to, so it gets no catcher.
        if (!isGameOver) {
            solidRect(4000.0, 4000.0, RGBA(0, 0, 0, 0)) {
                centerXOn(gameField)
                centerYOn(gameField)
                onClick {
                    Napier.d("Pause backdrop tapped - resuming")
                    showingRestart = false
                    this@container.removeFromParent()
                }
            }
        }

        val restartBackground =
            roundRect(Size(fieldWidth, fieldHeight), RectCorners(5), fill = grayedGameFieldColor) {
                centerXOn(gameField)
                centerYOn(gameField)
                // The pause screen can be dismissed by tapping the backdrop; the game over
                // screen cannot, because there is no game left to return to.
                if (!isGameOver) {
                    onClick {
                        Napier.d("Restart Button - NO Clicked")
                        showingRestart = false
                        this@container.removeFromParent()
                    }
                }
            }

        fun clearPopup () { this@container.removeFromParent() }

        // Lays out each pause-menu button as [icon] [label]. The icon and label sit at fixed
        // offsets from the button edge, so they line up into tidy columns across every button.
        fun layoutButtonContent(label: View, icon: View, within: View) {
            val pad = fieldWidth * 0.085
            val gap = fieldWidth * 0.05
            icon.x = within.x + pad
            icon.centerYOn(within)
            label.x = within.x + pad + icon.width + gap
            label.centerYOn(within)
        }

        // Pause shows four rows (UNDO / RESTART / SHARE / SETTINGS); game over hides UNDO (the
        // only state to undo to is the one that just ended the game) and so shows three. Buttons
        // are sized identically in both contexts; the whole stack is centered vertically in the
        // popup background, so the 3-row game-over screen still looks balanced.
        val buttonWidth = fieldWidth * 2.0 / 3
        val buttonHeight = fieldHeight * 0.15
        val buttonGap = fieldHeight * 0.035
        val labelSize = 27.0
        val rowCount = if (isGameOver) 3 else 4
        val firstRowTop = (fieldHeight - (rowCount * buttonHeight + (rowCount - 1) * buttonGap)) / 2.0
        fun buttonTopOffset(index: Int) = firstRowTop + index * (buttonHeight + buttonGap)
        // Without UNDO at row 0, the remaining rows shift up by one on game over.
        val rowOffset = if (isGameOver) 1 else 0

        val bgUndoContainer: Container? = if (isGameOver) null else container {
            val canUndo = Undo.canUndo()
            val textContainer = roundRect(Size(buttonWidth, buttonHeight), RectCorners(25), fill = pauseScreenBlockColor) {
                centerXOn(restartBackground)
                alignTopToTopOf(restartBackground, buttonTopOffset(0))
            }
            val label = text("UNDO (${Undo.remaining()} LEFT)", labelSize, pauseScreenTextColor, font) {
                if (canUndo) {
                    onOver { color = pauseScreenTextHoverColor }
                    onOut { color = pauseScreenTextColor }
                    onDown { color = pauseScreenTextDownColor }
                    onUp { color = pauseScreenTextDownColor }
                }
            }
            val icon = undoIcon(fieldWidth * 0.13, pauseScreenTextColor)
            layoutButtonContent(label, icon, textContainer)
            // Dim the whole button when there is nothing to undo, matching the haptics/sfx
            // "off" treatment so the disabled state reads at a glance without separate art.
            if (!canUndo) alpha = 0.35
            onClick {
                if (!Undo.canUndo()) {
                    Napier.d("Undo Button clicked but no history")
                    return@onClick
                }
                // Capture the stage BEFORE detaching the popup: `View.stage` walks up the
                // parent chain, so calling it after `clearPopup()` returns null and the
                // restore silently no-ops.
                val s = stage ?: return@onClick
                Napier.d("Undo Button clicked")
                showingRestart = false
                s.undoLastMove()
                clearPopup()
            }
        }
        val bgRestartContainer =
            container {
                val textContainer = roundRect(Size(buttonWidth, buttonHeight), RectCorners(25), fill = pauseScreenBlockColor) {
                    centerXOn(restartBackground)
                    alignTopToTopOf(restartBackground, buttonTopOffset(1 - rowOffset))
                }
                val label = text("RESTART", labelSize, pauseScreenTextColor, font) {
                    onOver { color = pauseScreenTextHoverColor }
                    onOut { color = pauseScreenTextColor }
                    onDown { color = pauseScreenTextDownColor }
                    onUp { color = pauseScreenTextDownColor }
                }
                val icon = restartIcon(fieldWidth * 0.13, pauseScreenTextColor)
                layoutButtonContent(label, icon, textContainer)
                onUp {
                    Napier.d("Restart Button - YES Clicked")
                    showingRestart = false
                    restart()
                    clearPopup()
                }
                onClick {
                    Napier.d("Restart Button - YES Clicked")
                    showingRestart = false
                    restart()
                    clearPopup()
                }
            }
        val shareContainer =
            container {
                val textContainer = roundRect(Size(buttonWidth, buttonHeight), RectCorners(25), fill = pauseScreenBlockColor) {
                    centerXOn(restartBackground)
                    alignTopToTopOf(restartBackground, buttonTopOffset(2 - rowOffset))
                }
                val label = text("SHARE", labelSize, pauseScreenTextColor, font) {
                    onOver { color = pauseScreenTextHoverColor }
                    onOut { color = pauseScreenTextColor }
                    onDown { color = pauseScreenTextDownColor }
                    onUp { color = pauseScreenTextDownColor }
                }
                val icon = shareIcon(fieldWidth * 0.13, pauseScreenTextColor)
                layoutButtonContent(label, icon, textContainer)
                // Copy the grid, then flash "COPIED" and a brighter button so the tap registers visibly.
                fun shareAndConfirm() {
                    Napier.d("Share Button - YES Clicked")
                    copyBlocksToClipboard()
                    label.text = "COPIED"
                    textContainer.fill = pauseScreenBlockCopiedColor
                    stage?.views?.launchImmediately {
                        delay(1200L)
                        label.text = "SHARE"
                        textContainer.fill = pauseScreenBlockColor
                    }
                }
                onUp { shareAndConfirm() }
                onClick { shareAndConfirm() }
            }

        // SETTINGS opens the secondary page that holds GUIDE, AD PERMISSIONS and the
        // HAPTICS/SOUND toggles. The pause popup itself is left attached but hidden behind
        // settings, so closing settings restores it without rebuilding any state.
        val settingsContainer =
            container {
                val textContainer = roundRect(Size(buttonWidth, buttonHeight), RectCorners(25), fill = pauseScreenBlockColor) {
                    centerXOn(restartBackground)
                    alignTopToTopOf(restartBackground, buttonTopOffset(3 - rowOffset))
                }
                val label = text("SETTINGS", labelSize, pauseScreenTextColor, font) {
                    onOver { color = pauseScreenTextHoverColor }
                    onOut { color = pauseScreenTextColor }
                    onDown { color = pauseScreenTextDownColor }
                    onUp { color = pauseScreenTextDownColor }
                }
                val icon = settingsIcon(fieldWidth * 0.13, pauseScreenTextColor)
                layoutButtonContent(label, icon, textContainer)
                onClick {
                    Napier.d("Settings Button Clicked")
                    val s = stage ?: return@onClick
                    pausePopup.visible = false
                    settingsPopupContainer = s.showSettings(fromGameOver = isGameOver, pauseMenu = pausePopup) { pausePopup.visible = true }
                }
            }

        // On game over, stagger the screen in and show a heading above the buttons.
        // Otherwise this is the pause screen and everything is already visible.
        if (isGameOver) {
            val headingText = "GAME OVER"
            val headingGlyphs = mutableListOf<Text>()
            container {
                // Faux-bold: clear_sans.fnt is a single-weight bitmap font, so stamp the
                // text twice with a 1px offset to thicken the strokes.
                for (offset in listOf(0.0, 1.0)) {
                    headingGlyphs += text(headingText, 20.0, RGBA(0, 0, 0), font) {
                        alignment = TextAlignment.MIDDLE_CENTER
                        x = offset
                    }
                }
                centerXOn(restartBackground)
                alignBottomToTopOf(bgRestartContainer, cellSize * 0.75)
            }
            // bgUndoContainer is null on game over, so the staggered intro list omits it.
            animateGameOverIntro(
                restartBackground,
                headingGlyphs,
                headingText,
                listOf(bgRestartContainer, shareContainer, settingsContainer),
            )
        }
    }

/**
 * Confirmation dialog shown before a game-mode switch discards a run worth keeping. A centered
 * card over a dimmed board: START runs [onConfirm] (which restarts into the new mode); CANCEL or
 * an outside tap dismisses it and leaves the current run untouched. Styled to match the pause menu
 * (rounded card, pill buttons, faux-bold heading) so it reads as part of the game, not a system
 * alert.
 */
fun Container.showGravityConfirm(turningOn: Boolean, onConfirm: () -> Unit) =
    container {
        val dialog = this

        // Dim the whole board behind the card; a tap outside the card cancels.
        solidRect(4000.0, 4000.0, RGBA(0, 0, 0, 175)) {
            centerXOn(gameField)
            centerYOn(gameField)
            onClick { dialog.removeFromParent() }
        }

        val cardW = fieldWidth * 0.94
        val cardH = fieldHeight * 0.52
        val cardCorner = RectCorners(cardH * 0.08)

        // Soft drop shadow nudged down behind the card, so it lifts off the board.
        roundRect(Size(cardW, cardH), cardCorner, fill = RGBA(0, 0, 0, 55)) {
            centerXOn(gameField)
            centerYOn(gameField)
            y += cardH * 0.025
        }
        // Opaque card (the chrome color is normally semi-transparent): over the dimmed board a
        // see-through card muddies the text contrast, badly so in the lighter themes.
        val card = roundRect(Size(cardW, cardH), cardCorner, fill = grayedGameFieldColor.withA(255)) {
            centerXOn(gameField)
            centerYOn(gameField)
            onClick { /* swallow taps so they don't reach the dismiss backdrop */ }
        }

        // Title — faux-bold via the same double-stamp trick the GAME OVER heading uses, since
        // clear_sans.fnt is single-weight.
        val modeName = if (turningOn) "Gravity" else "Classic"
        val titleText = "Start a new $modeName game?"
        container {
            for (offset in listOf(0.0, 1.0)) {
                text(titleText, 24.0, pauseScreenTextColor, font) {
                    setTextBounds(Rectangle(0.0, 0.0, cardW, 34.0))
                    alignment = TextAlignment.MIDDLE_CENTER
                    x = offset
                }
            }
            centerXOn(card)
            alignTopToTopOf(card, cardH * 0.18)
        }

        // Subtitle — secondary warning. Uses the readable chrome text color (the muted "pressed"
        // color was too low-contrast on the card in the lighter themes); the smaller, non-bold
        // weight is enough to keep it subordinate to the title.
        text("Your current game will be lost", 19.0, pauseScreenTextColor, font) {
            setTextBounds(Rectangle(0.0, 0.0, cardW, 26.0))
            alignment = TextAlignment.MIDDLE_CENTER
            centerXOn(card)
            alignTopToTopOf(card, cardH * 0.44)
        }

        val btnW = cardW * 0.42
        val btnH = cardH * 0.22
        val btnTop = cardH * 0.66

        // A pill button matching the pause-menu language: rounded fill + centered label. When
        // [labelColor] is null (CANCEL) the label uses the chrome text color and brightens on
        // hover / darkens on press, like every other menu button. When set (START) it uses a fixed
        // high-contrast ink for its accent fill and skips the state swaps, so the bright accent
        // (notably NEON's) never washes the label out.
        fun pillButton(labelText: String, fill: RGBA, labelColor: RGBA?, alignLeft: Boolean, onTap: () -> Unit) {
            container {
                val bg = roundRect(Size(btnW, btnH), RectCorners(btnH * 0.5), fill = fill) {
                    if (alignLeft) alignLeftToLeftOf(card, cardW * 0.06) else alignRightToRightOf(card, cardW * 0.06)
                    alignTopToTopOf(card, btnTop)
                }
                text(labelText, 22.0, labelColor ?: pauseScreenTextColor, font) {
                    setTextBounds(Rectangle(0.0, 0.0, btnW, btnH))
                    alignment = TextAlignment.MIDDLE_CENTER
                    centerOn(bg)
                    if (labelColor == null) {
                        onOver { color = pauseScreenTextHoverColor }
                        onOut { color = pauseScreenTextColor }
                        onDown { color = pauseScreenTextDownColor }
                        onUp { color = pauseScreenTextDownColor }
                    }
                }
                onClick { onTap() }
            }
        }

        pillButton("CANCEL", fill = pauseScreenBlockColor, labelColor = null, alignLeft = true) {
            dialog.removeFromParent()
        }
        pillButton("START", fill = pauseScreenBlockCopiedColor, labelColor = contrastInkOn(pauseScreenBlockCopiedColor), alignLeft = false) {
            dialog.removeFromParent()
            onConfirm()
        }
    }

/**
 * Settings sub-page reached from the SETTINGS button in the pause menu. Holds GUIDE, the GRAVITY
 * mode toggle, AD PERMISSIONS (only when GDPR / US-state privacy laws require us to expose a
 * "manage consent" affordance) and the HAPTICS / SOUND icon toggles. Mirrors the pause menu's
 * full-board backdrop so taps outside the buttons return to the pause menu beneath.
 */
fun Container.showSettings(
    fromGameOver: Boolean = false,
    pauseMenu: View? = null,
    onClose: () -> Unit,
) =
    container {
        // Captured for the same reason as showRestart's pausePopup: nested button blocks shadow
        // `this@container` so we hold an outer ref to toggle visibility (e.g. when GUIDE opens).
        val settingsPopup = this
        showingSettings = true
        Napier.d("Showing Settings Container...")

        val privacyOptionsAvailable = adsPrivacyOptionsRequired()

        fun dismiss() {
            showingSettings = false
            this@container.removeFromParent()
            onClose()
        }

        // Full-screen tap catcher behind the dialog: a tap anywhere outside the settings card
        // dismisses settings and surfaces the pause menu underneath.
        solidRect(4000.0, 4000.0, RGBA(0, 0, 0, 0)) {
            centerXOn(gameField)
            centerYOn(gameField)
            onClick { dismiss() }
        }

        val settingsBackground =
            roundRect(Size(fieldWidth, fieldHeight), RectCorners(5), fill = grayedGameFieldColor) {
                centerXOn(gameField)
                centerYOn(gameField)
                onClick { dismiss() }
            }

        fun layoutButtonContent(label: View, icon: View, within: View) {
            val pad = fieldWidth * 0.085
            val gap = fieldWidth * 0.05
            icon.x = within.x + pad
            icon.centerYOn(within)
            label.x = within.x + pad + icon.width + gap
            label.centerYOn(within)
        }

        // Five rows max (GUIDE / GRAVITY / THEMES / AD PERMISSIONS / HAPTICS+SFX). AD PERMISSIONS is
        // omitted when the user is outside any jurisdiction that requires a "manage consent"
        // affordance — in that case the row collapses out and HAPTICS+SFX moves up into its slot.
        val buttonWidth = fieldWidth * 2.0 / 3
        val buttonHeight = fieldHeight * 0.15
        val buttonGap = fieldHeight * 0.035
        val labelSize = 27.0
        val rowCount = if (privacyOptionsAvailable) 5 else 4
        val firstRowTop = (fieldHeight - (rowCount * buttonHeight + (rowCount - 1) * buttonGap)) / 2.0
        fun buttonTopOffset(index: Int) = firstRowTop + index * (buttonHeight + buttonGap)

        // GUIDE — opens the tutorial overlay. Hides this settings popup while open so taps land
        // on the guide's own buttons; restores it when the guide closes.
        container {
            val textContainer = roundRect(Size(buttonWidth, buttonHeight), RectCorners(25), fill = pauseScreenBlockColor) {
                centerXOn(settingsBackground)
                alignTopToTopOf(settingsBackground, buttonTopOffset(0))
            }
            val label = text("GUIDE", labelSize, pauseScreenTextColor, font) {
                onOver { color = pauseScreenTextHoverColor }
                onOut { color = pauseScreenTextColor }
                onDown { color = pauseScreenTextDownColor }
                onUp { color = pauseScreenTextDownColor }
            }
            val icon = helpIcon(fieldWidth * 0.13, pauseScreenTextColor)
            layoutButtonContent(label, icon, textContainer)
            onClick {
                Napier.d("How To Play Button Clicked (from settings)")
                settingsPopup.visible = false
                stage?.showHowToPlay { settingsPopup.visible = true }
            }
        }

        // GRAVITY — flips gravity mode. Each mode keeps its own high score, so switching starts a
        // fresh game; when the current run is worth keeping (> threshold) we confirm first.
        container {
            val textContainer = roundRect(Size(buttonWidth, buttonHeight), RectCorners(25), fill = pauseScreenBlockColor) {
                centerXOn(settingsBackground)
                alignTopToTopOf(settingsBackground, buttonTopOffset(1))
            }
            val label = text("GRAVITY ${if (gravityModeEnabled.value) "ON" else "OFF"}", labelSize, pauseScreenTextColor, font) {
                onOver { color = pauseScreenTextHoverColor }
                onOut { color = pauseScreenTextColor }
                onDown { color = pauseScreenTextDownColor }
                onUp { color = pauseScreenTextDownColor }
            }
            val icon = gravityIcon(fieldWidth * 0.13, pauseScreenTextColor)
            layoutButtonContent(label, icon, textContainer)
            // Dim the row when the mode is off, matching the haptics/sfx "off" treatment.
            if (!gravityModeEnabled.value) alpha = 0.4

            fun applyGravity(turningOn: Boolean) {
                val s = stage ?: return
                gravityModeEnabled.update(turningOn)
                s.views.storage.set(gravityModeEnabledKey, turningOn.toString())
                Napier.d("Gravity mode toggled ${if (turningOn) "on" else "off"}; dealing a fresh board")
                // Tear down the settings + pause/game-over menus and deal a fresh board in the new mode.
                showingSettings = false
                showingRestart = false
                settingsPopup.removeFromParent()
                pauseMenu?.removeFromParent()
                s.unselectAllPowerUps()
                s.restart()
            }

            onClick {
                val s = stage ?: return@onClick
                val turningOn = !gravityModeEnabled.value
                // Warn before discarding a run with real progress — but not on the game-over
                // screen, where there is nothing left to lose.
                if (!fromGameOver && score.value > gravityConfirmScoreThreshold) {
                    s.showGravityConfirm(turningOn) { applyGravity(turningOn) }
                } else {
                    applyGravity(turningOn)
                }
            }
        }

        // THEMES — opens the color-theme picker sub-page. Like GUIDE, hides this popup while the
        // picker is open and restores it on close.
        container {
            val textContainer = roundRect(Size(buttonWidth, buttonHeight), RectCorners(25), fill = pauseScreenBlockColor) {
                centerXOn(settingsBackground)
                alignTopToTopOf(settingsBackground, buttonTopOffset(2))
            }
            val label = text("THEMES", labelSize, pauseScreenTextColor, font) {
                onOver { color = pauseScreenTextHoverColor }
                onOut { color = pauseScreenTextColor }
                onDown { color = pauseScreenTextDownColor }
                onUp { color = pauseScreenTextDownColor }
            }
            val icon = paletteIcon(fieldWidth * 0.13, pauseScreenTextColor)
            layoutButtonContent(label, icon, textContainer)
            onClick {
                Napier.d("Themes Button Clicked (from settings)")
                settingsPopup.visible = false
                stage?.showThemes(
                    // Tapping outside a swatch returns to this settings page.
                    onClose = { settingsPopup.visible = true },
                    // Picking a theme closes the whole menu stack so the recolored board is revealed
                    // (otherwise the rebuilt blocks render on top of the still-open menu).
                    onThemeChosen = {
                        showingSettings = false
                        showingRestart = false
                        settingsPopup.removeFromParent()
                        pauseMenu?.removeFromParent()
                    },
                )
            }
        }

        // AD PERMISSIONS — presents the UMP privacy-options form so the player can revoke or
        // change their ad-consent choice. Required for GDPR compliance + App Store / Play Store
        // review on apps that show personalized ads.
        if (privacyOptionsAvailable) {
            container {
                val textContainer = roundRect(Size(buttonWidth, buttonHeight), RectCorners(25), fill = pauseScreenBlockColor) {
                    centerXOn(settingsBackground)
                    alignTopToTopOf(settingsBackground, buttonTopOffset(3))
                }
                val label = text("AD PERMISSIONS", labelSize, pauseScreenTextColor, font) {
                    onOver { color = pauseScreenTextHoverColor }
                    onOut { color = pauseScreenTextColor }
                    onDown { color = pauseScreenTextDownColor }
                    onUp { color = pauseScreenTextDownColor }
                }
                val icon = shieldIcon(fieldWidth * 0.13, pauseScreenTextColor)
                layoutButtonContent(label, icon, textContainer)
                onClick {
                    Napier.d("Ad Permissions Button Clicked")
                    adsPresentPrivacyOptions { /* form-modal returns control on its own */ }
                }
            }
        }

        // Bottom row holds the icon-only HAPTICS / SOUND pair: same horizontal footprint as the
        // labelled rows above, split into two square slots.
        val slotGap = buttonWidth * 0.08
        val slotWidth = (buttonWidth - slotGap) / 2.0
        val slotShift = (slotWidth + slotGap) / 2.0
        val toggleIconSize = fieldWidth * 0.15
        val toggleRowIndex = rowCount - 1

        container {
            val textContainer = roundRect(Size(slotWidth, buttonHeight), RectCorners(25), fill = pauseScreenBlockColor) {
                centerXOn(settingsBackground)
                x -= slotShift
                alignTopToTopOf(settingsBackground, buttonTopOffset(toggleRowIndex))
            }
            val icon = hapticsIcon(toggleIconSize, pauseScreenTextColor)
            icon.centerOn(textContainer)

            fun showEnabledState() {
                icon.alpha = if (Haptics.enabled) 1.0 else 0.3
            }
            showEnabledState()

            onClick {
                Haptics.enabled = !Haptics.enabled
                stage?.views?.storage?.set(hapticsEnabledKey, Haptics.enabled.toString())
                showEnabledState()
                if (Haptics.enabled) Haptics.tap()
                Napier.d("Haptics toggled ${if (Haptics.enabled) "on" else "off"}")
            }
        }

        container {
            val textContainer = roundRect(Size(slotWidth, buttonHeight), RectCorners(25), fill = pauseScreenBlockColor) {
                centerXOn(settingsBackground)
                x += slotShift
                alignTopToTopOf(settingsBackground, buttonTopOffset(toggleRowIndex))
            }
            val icon = speakerIcon(toggleIconSize, pauseScreenTextColor)
            icon.centerOn(textContainer)

            fun showEnabledState() {
                icon.alpha = if (Sfx.enabled) 1.0 else 0.3
            }
            showEnabledState()

            onClick {
                Sfx.enabled = !Sfx.enabled
                stage?.views?.storage?.set(sfxEnabledKey, Sfx.enabled.toString())
                showEnabledState()
                if (Sfx.enabled) Sfx.merge(Rank.ONE)
                Napier.d("Sfx toggled ${if (Sfx.enabled) "on" else "off"}")
            }
        }
    }

/**
 * Color-theme picker, reached from the THEMES row in [showSettings]. Mirrors that page's full-board
 * backdrop (taps outside the card return to settings beneath). Shows a 2x2 grid of swatch tiles,
 * each rendered in its *own* palette so the player can compare before choosing; tapping a tile
 * applies it live and persists the choice. The on-board recolor is handled by the colorTheme
 * observers wired across the always-present scene, so this only flips the observable + storage.
 *
 * Deliberately registers no colorTheme observers of its own: this popup is rebuilt on every open,
 * and observe() handlers never auto-unregister, so observing here would leak across re-opens.
 */
fun Container.showThemes(onClose: () -> Unit, onThemeChosen: () -> Unit) =
    container {
        // Held so the nested swatch tiles (whose own `container {}` shadows this receiver) can still
        // address the popup itself.
        val themesPopup = this
        Napier.d("Showing Themes Container...")

        fun dismiss() {
            themesPopup.removeFromParent()
            onClose()
        }

        // Full-screen tap catcher behind the card: a tap outside the card returns to settings.
        solidRect(4000.0, 4000.0, RGBA(0, 0, 0, 0)) {
            centerXOn(gameField)
            centerYOn(gameField)
            onClick { dismiss() }
        }

        val card =
            roundRect(Size(fieldWidth, fieldHeight), RectCorners(5), fill = grayedGameFieldColor) {
                centerXOn(gameField)
                centerYOn(gameField)
                onClick { dismiss() }
            }

        text("CHOOSE A THEME", 24.0, pauseScreenTextColor, font) {
            centerXOn(card)
            alignTopToTopOf(card, fieldHeight * 0.07)
        }

        val tileW = fieldWidth * 0.38
        val tileH = fieldHeight * 0.24
        val gapX = fieldWidth * 0.08
        val gapY = fieldHeight * 0.05
        val gridTop = fieldHeight * 0.20
        val gridLeft = (fieldWidth - (tileW * 2 + gapX)) / 2.0

        // Re-rendered after each pick so the selection ring moves to the chosen tile.
        val grid = container { }

        fun renderTiles() {
            grid.removeChildren()
            ColorTheme.values().forEachIndexed { idx, option ->
                val p = paletteOf(option)
                val offX = gridLeft + (idx % 2) * (tileW + gapX)
                val offY = gridTop + (idx / 2) * (tileH + gapY)
                grid.container {
                    // Tile backing in this theme's block tone.
                    val tile = roundRect(Size(tileW, tileH), RectCorners(8), fill = p.pauseScreenBlockColor)
                    // Mini sample tiles: green / red / blue / orange — instantly recognisable families.
                    val samples = listOf(Rank.THREE, Rank.FIVE, Rank.SIX, Rank.EIGHT)
                    val mini = tileW * 0.16
                    val miniGap = tileW * 0.07
                    val totalW = samples.size * mini + (samples.size - 1) * miniGap
                    var mx = tileW / 2.0 - totalW / 2.0
                    samples.forEach { r ->
                        roundRect(Size(mini, mini), RectCorners(3), fill = p.rankFill[r.ordinal]) {
                            x = mx
                            alignTopToTopOf(tile, tileH * 0.20)
                        }
                        mx += mini + miniGap
                    }
                    // Theme name in this theme's ink.
                    text(option.displayName, 16.0, p.pauseScreenTextColor, font) {
                        centerXOn(tile)
                        alignTopToTopOf(tile, tileH * 0.55)
                    }
                    // Selection ring in this theme's accent marks the active choice.
                    if (option == colorTheme.value) {
                        roundRect(
                            Size(tileW, tileH),
                            RectCorners(8),
                            fill = Colors.TRANSPARENT,
                            stroke = p.loadedRocketCartridgeColor,
                            strokeThickness = 4.0,
                        )
                    }
                    alignLeftToLeftOf(card, offX)
                    alignTopToTopOf(card, offY)
                    onClick {
                        if (colorTheme.value != option) {
                            colorTheme.update(option)
                            stage?.views?.storage?.set(colorThemeKey, option.name)
                            Napier.d("Color theme changed to ${option.name}")
                        }
                        Haptics.tap()
                        // Selecting a theme exits the picker and the menus beneath it, so the freshly
                        // recolored board isn't left rendering on top of a still-open menu.
                        themesPopup.removeFromParent()
                        onThemeChosen()
                    }
                }
            }
        }
        renderTiles()
    }

/**
 * Recolors every block for the active theme by recreating each block view (its init samples the
 * current palette). Driven by a colorTheme observer; the picker is open and the board idle when
 * this runs, so rebuilding in place is safe.
 */
fun Stage.refreshBoardColors() {
    blocksMap.toList().forEach { (position, block) -> updateBlock(block, position) }
}

fun Container.restart() {
    Napier.d("Running Restart Function...")
    resetIdleTimer()
    // Drop any pending undo history — the new board has no connection to the previous game.
    Undo.clear()
    score.update(0)
    bombsLoadedCount.update(startingBombCount)
    rocketsLoadedCount.update(startingRocketCount)
    blocksMap.values.forEach { it.removeFromParent() }
    blocksMap.clear()
    blocksMap = initializeRandomBlocksMap()
    drawAllBlocks()
    // New game: the background gradient drops back to its gray -> green opening.
    resetBackgroundGradient()
}
