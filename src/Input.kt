import korlibs.korge.view.Stage
import korlibs.math.geom.Point

fun getXFromIndex(index: Int) = leftIndent + cellIndentSize + (cellSize + cellIndentSize) * index

fun getYFromIndex(index: Int) = topIndent + cellIndentSize + (cellSize + cellIndentSize) * index

fun getXFromPosition(position: Position) = getXFromIndex(position.x)

fun getYFromPosition(position: Position) = getYFromIndex(position.y)

fun getPositionFromPoint(point: Point): Position? {
    Napier.d("Point x = ${point.x}, y = ${point.y}")
    var xCoord = -1
    var yCoord = -1
    for (i in 0 until gridColumns) {
        if (point.x > (i * (cellSize + cellIndentSize) + (cellIndentSize / 2) + leftIndent) &&
            point.x < ((i + 1) * (cellSize + cellIndentSize) + (cellIndentSize / 2) + leftIndent)
        ) {
            Napier.d("Matched x value to position index $i")
            xCoord = i
            break
        }
    }
    if (xCoord == -1) {
        Napier.d("x value outside of cell range")
        return null
    } else {
        for (j in 0 until gridRows) {
            if (point.y > (j * (cellSize + cellIndentSize) + (cellIndentSize / 2) + topIndent) &&
                point.y < ((j + 1) * (cellSize + cellIndentSize) + (cellIndentSize / 2) + topIndent)
            ) {
                Napier.d("Matched y value to position index $j")
                yCoord = j
                break
            }
        }
    }
    return	if (yCoord == -1) {
        Napier.d("y value outside of cell range")
        null
    } else {
        Napier.d("Returned Position($xCoord,$yCoord)")
        Position(xCoord, yCoord)
    }
}

fun Stage.handleDown(point: Point)  {
    resetIdleTimer()
    when {
        isAnimating -> return
        showingRestart -> return
        // During the tutorial only the current step's scripted action accepts board touches.
        tutorialActive && !tutorialBoardEnabled() -> return
        bombSelected -> {
            isPressed = true
            drawBombHover(getPositionFromPoint(point))
        }
        rocketSelection.selected -> {
            isPressed = true
            drawRocketSelection(getPositionFromPoint(point))
        }
        else -> {
            isPressed = true
            return pressDown(getPositionFromPoint(point))
        }
    }
}

fun Stage.handleHover(point: Point)  {
    if (isPressed) {
        when {
            isAnimating ||
                showingRestart ||
                rocketSelection.selected -> return
            bombSelected -> {
                removeBombHover()
                drawBombHover(getPositionFromPoint(point))
            }
            else -> hoverBlock(getPositionFromPoint(point))
        }
    }
}

fun Stage.handleUp(point: Point)  {
    resetIdleTimer()
    isPressed = false
    when {
        isAnimating ||
            rocketSelection.selected ||
            showingRestart -> return
        tutorialActive && !tutorialBoardEnabled() -> return
        bombSelected -> {
            val maybePosition = getPositionFromPoint(point)
            if (maybePosition == null) {
                removeBombHover()
            } else {
                // Snapshot the pre-detonation state so the player can undo this bomb.
                Undo.push()
                Haptics.success()
                animateBomb()
                removeBomb()
                bombSelected = false
                animatePowerUpSelection(bombContainer, false)
            }
        }
        else ->
            {
                if (atLeastThreeSelected()) {
                    successfulShape()
                } else {
                    unsuccessfulShape()
                }
            }
    }
}

fun Stage.pressDown(maybePosition: Position?) {
    // Only the first finger down may *start* a chain. On a touchscreen KorGE fires `start`
    // once per finger, so a second/third finger would otherwise re-enter here and blindly
    // append its block to hoveredPositions — skipping the adjacency/matching-tier validation
    // that lives in hoverBlock(). That let players merge arbitrary, non-adjacent blocks by
    // tapping with multiple fingers. Ignore any touch-down while a selection is already active;
    // extending a chain must go through the validated drag path (hoverBlock).
    if (hoveredPositions.isNotEmpty()) {
        Napier.d("Ignoring additional touch-down while a selection chain is active")
        return
    }
    if (maybePosition != null) {
        if (tutorialBlocksPosition(maybePosition)) {
            Napier.d("Tutorial blocked selection at ${maybePosition.log()}")
        } else if (blocksMap[maybePosition] != null) {
            Napier.v("Selecting Block at Position(${maybePosition.x},${maybePosition.y})")
            hoveredPositions.add(maybePosition)
            blocksMap[maybePosition] = blocksMap[maybePosition]!!.select()
            Haptics.tap()
            updateSelectionPreview()
        } else {
            Napier.w("No block found at Position(${maybePosition.x},${maybePosition.y})")
        }
    } else {
        Napier.w("Position parameter was null ")
    }
}

fun Stage.hoverBlock(maybePosition: Position?) {
    if (maybePosition != null && tutorialBlocksPosition(maybePosition)) {
        Napier.d("Tutorial blocked hover at ${maybePosition.log()}")
    } else if (maybePosition != null && (hoveredPositions.size > 0 && hoveredPositions.last() != maybePosition)) {
        if (blocksMap[maybePosition] == null) {
            Napier.w("Null block found at Position(${maybePosition.x},${maybePosition.y})")
        } else if (hoveredPositions.size > 0 &&
            !isValidTransition(
                hoveredPositions.last(),
                maybePosition,
            )
        ) {
            Napier.d("Block transition is invalid")
        } else if (hoveredPositions.contains(maybePosition) && hoveredPositions.elementAtOrNull(hoveredPositions.size - 2) != maybePosition) {
            Napier.d("Block is already selected)")
        } else if (hoveredPositions.size > 0 && blocksMap[hoveredPositions.last()]?.number != blocksMap[maybePosition]?.number) {
            Napier.d("Hovered a square of a different value")
        } else {
            if (hoveredPositions.elementAtOrNull(hoveredPositions.size - 2) == maybePosition) {
                Napier.d("Reverted previous hover")
                val reverted = hoveredPositions.removeAt(hoveredPositions.size - 1)
                updateBlock(blocksMap[reverted]!!.unselect(), reverted)
            } else {
                Napier.v(
                    "Hovering Block at Position(${maybePosition.x},${maybePosition.y} from Position(${hoveredPositions.last().x},${hoveredPositions.last().y})",
                )
                hoveredPositions.add(maybePosition)
                blocksMap[maybePosition] = blocksMap[maybePosition]!!.select()
                Haptics.tap()
            }

            updateSelectionPreview()
        }
    }
}

/**
 * Refreshes the selection preview for every hovered block (issue 4).
 *
 * Once the chain is long enough to merge, the preview mirrors the real merge ([determineMerge]):
 * the result squares are fully recoloured to what they become, and the consumed squares only
 * recolour their border to the upcoming colour. Shorter chains just border-hint the next colour.
 */
fun Stage.updateSelectionPreview() {
    if (hoveredPositions.isEmpty()) return

    val previews = mutableMapOf<Position, Pair<Rank, Boolean>>()
    if (hoveredPositions.size < smallSelectionSize) {
        hoveredPositions.forEach { position ->
            val nextRank = blocksMap[position]?.number?.next() ?: return@forEach
            previews[position] = Pair(nextRank, false)
        }
    } else {
        // determineMerge mutates its argument, so hand it a copy.
        val mergeMap = determineMerge(hoveredPositions.toMutableList())
        mergeMap.forEach { (head, resultAndConsumed) ->
            val resultRank = resultAndConsumed.first
            previews[head] = Pair(resultRank, true)
            resultAndConsumed.second.forEach { consumed -> previews[consumed] = Pair(resultRank, false) }
        }
    }

    hoveredPositions.forEach { position ->
        val block = blocksMap[position] ?: return@forEach
        val preview = previews[position]
        block.previewRank = preview?.first
        block.isResultBlock = preview?.second ?: false
        updateBlock(block, position)
    }
}
