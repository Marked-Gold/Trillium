import korlibs.time.*
import korlibs.korge.animate.*
import korlibs.korge.tween.*
import korlibs.korge.view.*
import korlibs.io.async.launchImmediately
import korlibs.math.interpolation.*
import korlibs.math.geom.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Breadcrumb naming the board-changing action currently in flight (merge / bomb / rocket / ...).
// Folded into recovery logs so a rare failure pinpoints which path broke.
var lastGameAction: String = "none"

/**
 * [launchImmediately] with a crash guard. On Kotlin/Native (iOS) an exception that escapes a
 * coroutine terminates the whole process — unlike JVM/JS, where it is merely logged — so every
 * board-changing animation launches through this instead: the failure is logged with the
 * [lastGameAction] breadcrumb and input is unlocked, leaving the game playable.
 *
 * Note the guard only covers the coroutine itself. `block { }` / `sequenceLazy { }` bodies inside
 * an `animate { }` run later, on the frame loop — those must stay null-safe end to end.
 */
fun Stage.launchGameAnimation(action: String, body: suspend () -> Unit) =
    launchImmediately {
        lastGameAction = action
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Napier.e("Recovered from crash during '$action': $e\n${e.stackTraceToString()}")
            stopAnimating()
        }
    }

fun Stage.animateMerge(mergeMap: MutableMap<Position, Pair<Rank, List<Position>>>) =
    launchGameAnimation("merge") {
        startAnimating()
        // One bomb is awarded for every block of 243 (tier FIVE) or higher created
        // by this merge, regardless of whether that tier has been reached before.
        var bombsEarned = 0
        // The highest-tier block (81 / tier FOUR or above) forged by this merge,
        // and where it landed: once the merge settles its colour ripples out
        // across the background from that spot.
        var topTier: Rank? = null
        var topHead: Position? = null
        // Highest tier forged anywhere in this merge — drives the single merge SFX pop. Tracked
        // separately from topTier so it captures sub-81 merges too (and so square-merges that
        // forge two heads at once don't double-trigger the sound).
        var highestMergeTier: Rank? = null
        animate {
            parallel {
                Napier.v("Animating the blocks merging together")
                mergeMap.forEach { (headPosition, valueAndMergePositions) ->
                    val mergePositions = valueAndMergePositions.second
                    mergePositions.forEach { position ->
                        // A missing source block means the board state desynced; skip the tween
                        // rather than crash — the block {} below re-checks before deleting.
                        val source = blocksMap[position]
                        if (source == null) {
                            Napier.e("animateMerge: no block at source ${position.log()} merging into ${headPosition.log()}")
                            return@forEach
                        }
                        Napier.d("Moving block from ${position.log()} to new block")
                        moveTo(
                            source,
                            getXFromPosition(headPosition) + cellSize / 2,
                            getYFromPosition(headPosition) + cellSize / 2,
                            0.15.seconds,
                            Easing.LINEAR,
                        )
                        scaleTo(source, 0, 0, 0.15.seconds, Easing.LINEAR)
                    }
                }
            }
            block {
                // Runs on the frame loop, outside the launch guard: a throw here would kill the
                // iOS process, so every board access stays null-safe.
                Napier.v("Animating deletion of previous blocks and adding new upgraded block")
                mergeMap.forEach { (headPosition, valueAndMergePositions) ->
                    valueAndMergePositions.second.forEach { position -> deleteBlock(blocksMap[position]) }
                    val value = valueAndMergePositions.first
                    if (value.ordinal >= Rank.FIVE.ordinal) bombsEarned++
                    if (value.ordinal >= Rank.FOUR.ordinal &&
                        (topTier == null || value.ordinal > topTier!!.ordinal)
                    ) {
                        topTier = value
                        topHead = headPosition
                    }
                    if (highestMergeTier == null || value.ordinal > highestMergeTier!!.ordinal) {
                        highestMergeTier = value
                    }
                    val headBlock = blocksMap[headPosition]
                    if (headBlock == null) {
                        // The head vanished mid-merge; the refill step below repopulates the cell,
                        // so skipping the upgrade keeps the board playable.
                        Napier.e("animateMerge: no head block at ${headPosition.log()}; skipping upgrade")
                        return@forEach
                    }
                    val newBlock = headBlock.updateRank(value).unselect().copy()
                    deleteBlock(headBlock)
                    blocksMap[headPosition] = newBlock
                    drawBlock(newBlock, headPosition)
                }
                // One soft pop per merge event, pitched to the highest tier forged. Firing per-head
                // makes square-merges and other multi-head merges sound like a smeared double-tap.
                highestMergeTier?.let { Sfx.merge(it) }
            }
            sequenceLazy {
                if (gravityModeEnabled.value) {
                    // Gravity mode: survivors fall and new pieces drop in from the top, rather than
                    // every vacated cell being filled in place.
                    animateGravityRefill(this@animateMerge)
                    return@sequenceLazy
                }
                val newPositionBlocks = generateBlocksForEmptyPositions()
                Napier.d(
                    "Generating new blocks ${newPositionBlocks.map {
                            (position, block) ->
                        "${block.number.value} at (${position.log()}\n"
                    }}",
                )
                blocksMap.putAll(newPositionBlocks)

                parallel {
                    newPositionBlocks
                        .forEach { (position, block) ->

                            val x = getXFromPosition(position)
                            val y = getYFromPosition(position)
                            val scale = block.scale

                            val newBlock = addBlock(block)
                            newBlock.position(x + cellSize / 2, y + cellSize / 2)
                            newBlock.scale = 0.0

                            tween(
                                newBlock::x[x],
                                newBlock::y[y],
                                newBlock::scale[scale],
                                time = 0.3.seconds,
                                easing = Easing.EASE_SINE,
                            )
                        }

                    mergeMap.forEach { (headPosition, _) ->
                        if (blocksMap[headPosition] != null) {
                            animateConsumption(blocksMap[headPosition]!!)
                        } else {
                            Napier.w("No block found for consumption at ${headPosition.log()}")
                        }
                    }
                }
            }
            block {
                stopAnimating()
                if (bombsEarned > 0) {
                    Napier.d("Merge created $bombsEarned block(s) of 243+, awarding $bombsEarned bomb(s)")
                    tryAddBombs(bombsEarned)
                }
                val tier = topTier
                val head = topHead
                if (tier != null && head != null) {
                    triggerBackgroundPulse(
                        tier,
                        getXFromPosition(head) + cellSize / 2.0,
                        getYFromPosition(head) + cellSize / 2.0,
                    )
                    // Singing-bowl swell on top of the merge pop, pitched per tier.
                    Sfx.pulse(tier)
                }
                checkGameOver()
                onTutorialMerge()
            }
        }
    }

// Ends the game when the board is fully stuck: no available moves and no
// power-ups left to break the deadlock. Must run after every board-changing
// animation (merge, bomb, rocket) — any of them can leave the board dead.
fun Stage.checkGameOver() {
    // The scripted tutorial drains power-ups on purpose; never end the game during it.
    if (tutorialActive) return
    // The board is fully settled by the time we get here (after every merge/bomb/rocket), so
    // this is the moment to snapshot a record-setting layout for the shareable BEST box.
    maybeCaptureBestShare()
    if (!hasAvailableMoves() && bombsLoadedCount.value == 0 && rocketsLoadedCount.value == 0) {
        Napier.d("Game Over!")
        launchGameAnimation("game-over") {
            // First the board shakes to signal the dead end, then the screen staggers in.
            animateBoardShake()
            showRestart(isGameOver = true) {
                // Only show the interstitial once the player chooses to restart.
                launchGameAnimation("restart") {
                    Ads.showInterstitial()
                    restart()
                }
            }
        }
    }
}

// How long a tile takes to settle / drop into place in gravity mode.
val gravityFallTime = 0.5.seconds

/**
 * Gravity-mode refill. Instead of filling every empty cell in place, the surviving blocks in each
 * column settle against the floor (largest y), preserving their relative order, and fresh blocks
 * drop in from above the top edge to fill the vacated cells. [blocksMap] is rebuilt into its final
 * layout up front — so game-over checks and input see the settled board immediately — then the
 * views are animated from where they currently sit to that layout. Must be called inside an
 * `animate { }` (Animator) scope; [stage] supplies the container the new block views attach to.
 *
 * The new blocks start above the board (off the top of the grid). They are added to the stage
 * hidden, and the boot-time reveal updater (see [gravityPendingReveal]) flips each one visible the
 * instant its top edge drops past the playfield's top edge — so nothing is ever seen in the gap
 * above the grid, yet the drop onto the board is still visible. (A clip layer would be the natural
 * tool here, but KorGE's scissor clipping does not hold up under this scaled / letterboxed JS
 * setup, so visibility is used instead.) The survivors fall on the stage as usual.
 */
fun Animator.animateGravityRefill(stage: Stage) {
    val newMap = mutableMapOf<Position, Block>()
    // Surviving blocks that slide down, paired with the cell they settle into.
    val falls = mutableListOf<Pair<Block, Position>>()
    // Freshly spawned blocks that drop in from above, paired with their landing cell.
    val drops = mutableListOf<Pair<Block, Position>>()

    for (x in 0 until gridColumns) {
        // Surviving blocks in this column, top -> bottom, tagged with their current row.
        val column = (0 until gridRows).mapNotNull { y -> blocksMap[Position(x, y)]?.let { y to it } }
        val emptyCount = gridRows - column.size
        // Drop the survivors onto the floor, keeping their relative order.
        column.forEachIndexed { i, (oldY, block) ->
            val newY = emptyCount + i
            val target = Position(x, newY)
            newMap[target] = block
            if (newY != oldY) falls.add(block to target)
        }
        // Fill the freed top cells with new blocks falling in from above the board. Starting each
        // one `emptyCount` rows above its target makes the whole new column enter together, sliding
        // down past the top edge. Each starts hidden and is revealed as it crosses onto the board.
        for (row in 0 until emptyCount) {
            val newBlock = Block(nextBlockId++, getRandomRank())
            val target = Position(x, row)
            newMap[target] = newBlock
            stage.addBlock(newBlock)
            newBlock.position(getXFromPosition(target), getYFromIndex(row - emptyCount))
            newBlock.visible = false
            gravityPendingReveal.add(newBlock)
            drops.add(newBlock to target)
        }
    }

    blocksMap = newMap

    parallel {
        falls.forEach { (block, target) ->
            moveTo(block, getXFromPosition(target), getYFromPosition(target), gravityFallTime, Easing.EASE_IN)
        }
        drops.forEach { (block, target) ->
            moveTo(block, getXFromPosition(target), getYFromPosition(target), gravityFallTime, Easing.EASE_IN)
        }
    }
    block {
        // By now every dropped block has crossed onto the board and been revealed by the updater;
        // force it and drop it from the pending list as a safety net against rounding.
        drops.forEach { (block, _) ->
            block.visible = true
            gravityPendingReveal.remove(block)
        }
    }
}

fun Animator.animateConsumption(block: Block) {
    val x = block.x
    val y = block.y
    val scale = block.scale
    tween(
        block::x[x - 4],
        block::y[y - 4],
        block::scale[scale + 0.1],
        time = 0.1.seconds,
        easing = Easing.LINEAR,
    )
    tween(
        block::x[x],
        block::y[y],
        block::scale[scale],
        time = 0.1.seconds,
        easing = Easing.LINEAR,
    )
}

fun Stage.animatePowerUpSelection(
    image: View,
    toggle: Boolean,
) = launchGameAnimation("powerup-select") {
    // Animate to ABSOLUTE home / selected coordinates rather than offsetting from the view's live
    // position. Each call spawns its own 0.1s tween, and when bombs are used in quick succession
    // those tweens overlap: a relative offset captured from a mid-tween position no longer cancels
    // its counterpart, so the icon used to creep up-left. Absolute targets always converge home.
    val isRocket = image === rocketContainer
    val homeX = if (isRocket) rocketHomeX else bombHomeX
    val homeY = if (isRocket) rocketHomeY else bombHomeY
    animate {
        if (toggle) {
            tween(
                image::x[homeX - 8],
                image::y[homeY - 12],
                image::scale[bombScaleSelected],
                time = 0.1.seconds,
                easing = Easing.LINEAR,
            )
        } else {
            tween(
                image::x[homeX],
                image::y[homeY],
                image::scale[bombScaleNormal],
                time = 0.1.seconds,
                easing = Easing.LINEAR,
            )
        }
    }
}

// Shakes the whole board left and right to signal the player is out of moves.
// A decaying ~0.54s shake; suspends until it settles so the screen can follow it.
suspend fun Stage.animateBoardShake() {
    val blocks = blocksMap.values.toList()
    if (blocks.isEmpty()) return
    // Descending sigh lands in step with the shake; the shake's the only thing that signals the
    // dead end visually, so the sound piggybacks on it rather than getting its own moment.
    Sfx.gameOver()
    val homeX = blocks.associateWith { it.x }
    animate {
        for (dx in listOf(10.0, -9.0, 7.0, -5.0, 3.0, 0.0)) {
            parallel {
                blocks.forEach { block ->
                    tween(block::x[homeX.getValue(block) + dx], time = 0.09.seconds, easing = Easing.LINEAR)
                }
            }
        }
    }.awaitComplete()
}

// Staggers the game-over screen in over ~1.3s (the board shake runs first, for ~1.85s
// total): the dark overlay fades up, the heading types out one character at a time,
// then the RESTART / SHARE buttons rise into place. The glyph list holds the stacked
// faux-bold copies of the heading, typed out in lockstep.
fun View.animateGameOverIntro(
    overlay: View,
    headingGlyphs: List<Text>,
    headingText: String,
    buttons: List<View>,
) {
    val stage = stage ?: return
    overlay.alpha = 0.0
    headingGlyphs.forEach { it.text = "" }
    buttons.forEach {
        it.alpha = 0.0
        it.y += 16.0
    }
    stage.launchGameAnimation("game-over-intro") {
        // 1. The dark overlay fades up (~0.35s).
        animate { alpha(overlay, 1.0, 0.35.seconds, Easing.EASE_OUT) }.awaitComplete()
        // 2. The heading types out, one character at a time (~0.63s).
        for (i in 1..headingText.length) {
            val visible = headingText.substring(0, i)
            headingGlyphs.forEach { it.text = visible }
            delay(70L)
        }
        // 3. The buttons fade in and rise into place (~0.34s).
        animate {
            parallel {
                buttons.forEach { button ->
                    alpha(button, 1.0, 0.34.seconds, Easing.EASE_OUT)
                    tween(button::y[button.y - 16.0], time = 0.34.seconds, easing = Easing.EASE_OUT)
                }
            }
        }.awaitComplete()
    }
}

fun Stage.generateNewBlocks() =
    launchGameAnimation("spawn") {
        if (gravityModeEnabled.value) {
            // Gravity mode: collapse the columns and drop fresh pieces in from the top instead of
            // filling the cleared cells where they sit.
            animate { animateGravityRefill(this@generateNewBlocks) }
            return@launchGameAnimation
        }
        val newPositionBlocks = generateBlocksForEmptyPositions()
        Napier.d("Generating new blocks ${newPositionBlocks.map { (position, block) -> "${block.number.value} at (${position.log()}\n" }}")
        blocksMap.putAll(newPositionBlocks)

        animate {
            parallel {
                newPositionBlocks
                    .forEach { (position, block) ->

                        val x = getXFromPosition(position)
                        val y = getYFromPosition(position)
                        val scale = block.scale

                        val newBlock = addBlock(block)
                        newBlock.position(x + cellSize / 2, y + cellSize / 2)
                        newBlock.scale = 0.0

                        tween(
                            newBlock::x[x],
                            newBlock::y[y],
                            newBlock::scale[scale],
                            time = 0.3.seconds,
                            easing = Easing.EASE_SINE,
                        )
                    }
            }
        }
    }

fun Stage.animateBomb() =
    launchGameAnimation("bomb") {
        startAnimating()
        Sfx.bomb()
        Napier.v("Animating the bomb")
        val bombedPositions = hoveredBombPositions.toList()
        hoveredBombPositions.clear()
        val flyingBlocks = bombedPositions.mapNotNull { blocksMap[it] }

        // Run the long fly-off in the background so the new pieces can start
        // dropping in while the old tiles are still spinning away.
        val flyOff = launchGameAnimation("bomb-flyoff") {
            animate {
                parallel {
                    flyingBlocks.forEach { block ->
                        val random = Random.nextDouble(0.0, 2 * 3.1415)
                        val xDirection = sin(random)
                        val yDirection = cos(random)
                        Napier.d("Bombing block id ${block.id}")
                        // Re-centre the rotation pivot: a block rotates around its local
                        // origin (top-left corner), which makes it orbit awkwardly. Shift
                        // its content to be origin-centred and compensate the block's
                        // position so it stays put before the spin begins.
                        block.forEachChild { child -> child.xy(child.x - cellSize / 2, child.y - cellSize / 2) }
                        block.xy(block.x + cellSize / 2, block.y + cellSize / 2)
                        moveTo(
                            block,
                            xDirection * 1000 + cellSize / 2,
                            yDirection * 1000 + cellSize / 2,
                            2.8.seconds,
                            Easing.EASE_OUT_QUAD,
                        )
                        val spin = Random.nextDouble(3.0, 5.0) * if (Random.nextBoolean()) 1 else -1
                        rotateBy(
                            block,
                            (spin * 360).degrees,
                            2.8.seconds,
                            Easing.EASE_OUT_QUAD,
                        )
                    }
                }
                block {
                    flyingBlocks.forEach { removeBlock(it) }
                }
            }
        }

        // Free the bombed cells immediately so the new pieces can be generated,
        // then drop them in after a short head start — well before the old tiles
        // have finished flying off.
        blocksMap = blocksMap.filter { (_, block) -> flyingBlocks.none { it.id == block.id } }.toMutableMap()
        delay(500L)
        generateNewBlocks()
        // The board is logically complete and playable here, so re-enable input now rather
        // than after the purely cosmetic fly-off of the old tiles finishes (~2.3s later).
        stopAnimating()
        // Same reasoning for the tutorial gate: advance to the next step as soon as the
        // board is playable, so the rocket panel doesn't sit behind the long fly-off.
        onTutorialBomb()

        flyOff.join()
        checkGameOver()
    }

fun Stage.animateRocket(selection: RocketSelection) =
    launchGameAnimation("rocket") {
        when {
            (selection.firstPosition == null) -> Napier.e("No first position when animating rockets")
            (selection.secondPosition == null) -> Napier.e("No second position when animating rockets")
            else -> {
                startAnimating()
                Sfx.rocket()
                val firstPosition = selection.firstPosition!!
                val secondPosition = selection.secondPosition!!
                val firstBlock = blocksMap[firstPosition]
                val secondBlock = blocksMap[secondPosition]
                if (firstBlock == null || secondBlock == null) {
                    Napier.e(
                        "animateRocket: missing block at " +
                            "${if (firstBlock == null) firstPosition.log() else secondPosition.log()}; aborting swap",
                    )
                    stopAnimating()
                    return@launchGameAnimation
                }
                Napier.d("Rocketing: swapping ${firstPosition.log()} and ${secondPosition.log()}")
                animate {
                    parallel {
                        moveTo(
                            firstBlock,
                            getXFromPosition(secondPosition),
                            getYFromPosition(secondPosition),
                            0.15.seconds,
                            Easing.LINEAR,
                        )
                        moveTo(
                            secondBlock,
                            getXFromPosition(firstPosition),
                            getYFromPosition(firstPosition),
                            0.15.seconds,
                            Easing.LINEAR,
                        )
                    }
                    block {
                        updateBlock(firstBlock.unselect(), secondPosition)
                        updateBlock(secondBlock.unselect(), firstPosition)
                    }
                }
                stopAnimating()
                checkGameOver()
                onTutorialRocket()
            }
        }
    }

// Quick decaying horizontal shake. Used to nudge the player toward an unused
// power-up when the board is stuck and one must be spent to keep playing.
// `mirror` flips the shake direction so the bomb and rocket wobble symmetrically
// inward/outward rather than in lockstep.
fun Stage.jigglePowerUp(container: Container, mirror: Boolean = false) =
    launchGameAnimation("powerup-jiggle") {
        val baseX = container.x
        val dir = if (mirror) -1.0 else 1.0
        animate {
            for (offset in listOf(-7.0, 7.0, -5.0, 5.0, -3.0, 3.0, 0.0)) {
                tween(
                    container::x[baseX + offset * dir],
                    time = 0.1.seconds,
                    easing = Easing.LINEAR,
                )
            }
        }
    }

// Called once every idleHintDelay seconds of inactivity. If the board has no
// available moves the player must spend a power-up to continue, so any held
// bomb/rocket that is not already selected jiggles to draw attention to it.
fun Stage.checkIdleHint() {
    if (isAnimating || showingRestart || hasAvailableMoves()) return
    if (bombsLoadedCount.value > 0 && !bombSelected) jigglePowerUp(bombContainer)
    if (rocketsLoadedCount.value > 0 && !rocketSelection.selected) jigglePowerUp(rocketContainer, mirror = true)
}

fun Stage.animateSelectedBlock(
    maybeBlock: Block?,
    selected: Boolean,
) = launchGameAnimation("block-select") {
    if (maybeBlock == null)
        {
            Napier.e("Empty block passed into animateSelectedBlock")
        } else {
        val block = maybeBlock
        animate {
            val x = block.x
            val y = block.y
            if (selected) {
                tween(
                    block::x[x - 4],
                    block::y[y - 4],
                    block::scale[blockScaleSelected],
                    time = 0.1.seconds,
                    easing = Easing.LINEAR,
                )
            } else {
                tween(
                    block::x[x],
                    block::y[y],
                    block::scale[blockScaleNormal],
                    time = 0.1.seconds,
                    easing = Easing.LINEAR,
                )
            }
        }
    }
}
