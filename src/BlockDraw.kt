import korlibs.korge.view.*
import kotlin.math.max
import kotlin.math.min

fun Container.deleteBlock(block: Block?) {
    if (block is Block) {
        Napier.v("deleteBlock with Id = ${block.id}")
        blocksMap = blocksMap.filter { (position, existingBlock) -> block.id != existingBlock.id }.toMutableMap()
        removeBlock(block)
    }
}

fun Container.drawBlock(
    block: Block,
    position: Position,
) {
    Napier.v("drawBlock at ${position.log()} with Rank ${block.number.value}, IsSelected ${block.selection}")
    blocksMap[position] = addBlock(block).position(getXFromPosition(position), getYFromPosition(position))
}

fun Container.drawAllBlocks() {
    Napier.v("drawBlocks")
    blocksMap
        .forEach { (position, block) -> drawBlock(block, position) }
}

fun Stage.updateBlock(
    block: Block,
    position: Position,
)  {
    val newBlock = block.copy()
    deleteBlock(block)
    blocksMap[position] = newBlock
    drawBlock(newBlock, position)
    animateSelectedBlock(newBlock, newBlock.isGenerallySelected())
}

fun Container.atLeastThreeSelected(): Boolean {
    return (hoveredPositions.size > 2)
}

fun Stage.unsuccessfulShape() {
    Napier.d("Shape was unsuccessful ")
    removeSelection()
    hoveredPositions
        .forEach { position ->
            val block = blocksMap[position]?.unselect()
            if (block == null) {
                Napier.e("unsuccessfulShape: no block at hovered ${position.log()}")
                return@forEach
            }
            blocksMap[position] = block
            Napier.d("Removing hovered ${position.log()}")
            updateBlock(block, position)
        }
    hoveredPositions.clear()
}

fun Stage.successfulShape() {
    // Snapshot the pre-merge state so the player can undo this chain from the pause menu.
    Undo.push()
    Haptics.success()
    if (hoveredPositions.size >= rocketPowerUpLength) tryAddRockets(1)
    val pattern = determinePattern(hoveredPositions.toMutableList())
    val scoredPoints = determineScore(hoveredPositions.toMutableList())
    Napier.d("Hovered position size ${hoveredPositions.size}")
    val mergeMap = determineMerge(hoveredPositions.toMutableList())
    Napier.d("Hovered position size ${hoveredPositions.size}")
    hoveredPositions.clear()
    animateMerge(mergeMap)
    score.update(score.value + scoredPoints)
}

fun tryAddBombs(numberOfBombs: Int)  {
    Napier.d("Trying to add $numberOfBombs bombs")
    val newBombCount = min(bombsLoadedCount.value + numberOfBombs, maxBombCount)
    bombsLoadedCount.update(newBombCount)
}

fun removeBomb()  {
    // Debug: keep the rack full so bombs can be thrown indefinitely while hunting a crash.
    if (debugInfiniteBombs) return
    Napier.d("Removing a bomb")
    val newBombCount = max(bombsLoadedCount.value - 1, 0)
    bombsLoadedCount.update(newBombCount)
}

fun Stage.drawBombHover(maybePosition: Position?) {
    if (maybePosition != null) {
        hoveredBombPositions = tryAllSurroundingPositions(maybePosition).toMutableList()
        hoveredBombPositions.add(maybePosition)
        hoveredBombPositions.forEach { pos ->
            if (blocksMap[pos] != null) {
                updateBlock(blocksMap[pos]!!.selectBomb(), pos)
            } else {
                Napier.e("drawBombHover tried to draw a surrounding block that is null")
            }
        }
    } else {
        Napier.e("drawBombHover tried to draw a main block that is null")
    }
}

fun Stage.removeBombHover() {
    hoveredBombPositions.forEach { pos ->
        if (blocksMap[pos] != null) {
            updateBlock(blocksMap[pos]!!.unselect(), pos)
        } else {
            Napier.e("drawBombHover tried to draw a surrounding block that is null")
        }
    }
    hoveredBombPositions.clear()
}

fun tryAddRockets(numberOfRockets: Int)  {
    Napier.d("Trying to add $numberOfRockets rockets")
    val newRocketCount = min(rocketsLoadedCount.value + numberOfRockets, maxRocketCount)
    rocketsLoadedCount.update(newRocketCount)
}

fun removeRocket()  {
    Napier.d("Removing a rocket")
    val newRocketCount = max(rocketsLoadedCount.value - 1, 0)
    rocketsLoadedCount.update(newRocketCount)
}

fun Stage.drawRocketSelection(maybePosition: Position?) {
    when {
        (maybePosition == null) -> Napier.e("drawRocketSelection tried to draw a main block that is null")
        (!rocketSelection.selected) -> Napier.e("drawRocketSelection tried to draw  when rocket is unselected")
        (rocketSelection.firstPosition == null) -> {
            rocketSelection.selectFirst(maybePosition)
            updateBlock(blocksMap[maybePosition]!!.selectRocket(), maybePosition)
            Haptics.tap()
        }
        (rocketSelection.firstPosition == maybePosition) -> {
            rocketSelection.unselectFirst()
            updateBlock(blocksMap[maybePosition]!!.unselect(), maybePosition)
        }
        (rocketSelection.secondPosition == null) -> {
            // Snapshot the pre-swap state so the player can undo this rocket launch.
            Undo.push()
            rocketSelection.selectSecond(maybePosition)
            Haptics.success()
            animateRocket(rocketSelection.copy())
            removeRocket()
            rocketSelection.unselect()
            animatePowerUpSelection(rocketContainer, false)
        }
        else ->
            {
            }
    }
}

fun Stage.removeRocketSelection() {
    if (rocketSelection.firstPosition != null && blocksMap[rocketSelection.firstPosition] != null) {
        updateBlock(
            blocksMap[rocketSelection.firstPosition]!!.unselect(),
            rocketSelection.firstPosition!!,
        )
    } else {
        Napier.d("No first rocket position to remove")
    }
    if (rocketSelection.secondPosition != null && blocksMap[rocketSelection.secondPosition] != null) {
        updateBlock(
            blocksMap[rocketSelection.secondPosition]!!.unselect(),
            rocketSelection.secondPosition!!,
        )
    } else {
        Napier.d("No second rocket position to remove")
    }
    rocketSelection.unselect()
}

fun Stage.removeSelection() {
    hoveredPositions.map { position -> animateSelectedBlock(blocksMap[position], false) }
}

fun Stage.selectBlocks() {
    hoveredPositions.map { position -> animateSelectedBlock(blocksMap[position], true) }
}
