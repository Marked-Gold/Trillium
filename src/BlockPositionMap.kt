import kotlin.math.abs
import kotlin.math.min
import kotlin.random.*

data class Position(val x: Int, val y: Int) {
    init {
        require(x >= 0)
        require(x < gridColumns)
        require(y >= 0)
        require(y < gridRows)
    }

    fun log(): String {
        return "Position(${this.x},${this.y})"
    }
}

fun allPositions(): List<Position> {
    val positionList: MutableList<Position> = mutableListOf()
    for (j in 0 until gridRows) {
        for (i in 0 until gridColumns) {
            positionList.add(Position(i, j))
        }
    }
    return positionList
}

enum class Direction {
    NORTH,
    NORTHEAST,
    EAST,
    SOUTHEAST,
    SOUTH,
    SOUTHWEST,
    WEST,
    NORTHWEST,
}

fun getCardinalDirections() = arrayOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)

// Debug toggle: when true, the board (and every replacement block) is filled with
// Rank.ZERO ("1" tiles), to make it trivial to test square/line merge patterns.
// Leave this `false` for normal play.
const val debugAllOnes = false

fun initBlock(): Block {
    val selectedId = nextBlockId
    nextBlockId++
    if (debugAllOnes) return Block(id = selectedId, Rank.ZERO)
    val random = Random.nextDouble()
    return when {
        selectedId == random27ID -> Block(id = selectedId, Rank.THREE)
        random < 0.1 -> Block(id = selectedId, Rank.THREE)
        random < 0.2 -> Block(id = selectedId, Rank.TWO)
        random < 0.5 -> Block(id = selectedId, Rank.ONE)
        else -> Block(id = selectedId, Rank.ZERO)
    }
}

fun initializeRandomBlocksMap(): MutableMap<Position, Block> {
    return allPositions().map { position -> Pair(position, initBlock()) }.toMap().toMutableMap()
}

// private fun getEnumId(position: Position) = array[position.x + position.y * gridRows]

private fun getRank(id: Int) = Rank.values()[id]

private fun getPosition(idx: Int) = Position(idx % gridColumns, idx / gridColumns)

fun getIndex(position: Position) = position.x + position.y * gridColumns

private fun tryPositionInDirection(
    position: Position,
    direction: Direction,
) = try {
    when (direction) {
        Direction.NORTH -> Position(position.x, position.y + 1)
        Direction.NORTHEAST -> Position(position.x + 1, position.y + 1)
        Direction.EAST -> Position(position.x + 1, position.y)
        Direction.SOUTHEAST -> Position(position.x + 1, position.y - 1)
        Direction.SOUTH -> Position(position.x, position.y - 1)
        Direction.SOUTHWEST -> Position(position.x - 1, position.y - 1)
        Direction.WEST -> Position(position.x - 1, position.y)
        Direction.NORTHWEST -> Position(position.x - 1, position.y + 1)
    }
} catch (e: IllegalArgumentException) {
    null
}

private fun tryAllAdjacentPositions(position: Position) =
    getCardinalDirections().mapNotNull { direction -> tryPositionInDirection(position, direction) }

fun tryAllSurroundingPositions(position: Position) =
    Direction.values().mapNotNull { direction -> tryPositionInDirection(position, direction) }

fun isValidTransition(
    oldPosition: Position,
    newPosition: Position?,
): Boolean  {
    return if (newPosition is Position) {
        (abs(newPosition.x - oldPosition.x) == 1) xor (abs(newPosition.y - oldPosition.y) == 1) &&
            ((newPosition.x - oldPosition.x) * (newPosition.y - oldPosition.y) == 0)
    } else {
        false
    }
}

fun hasAvailableMoves(): Boolean {
    return blocksMap.any { (position, block) -> hasTwoMatchingAdjacents(position, block) }
}

private fun hasTwoMatchingAdjacents(
    position: Position,
    block: Block,
): Boolean {
    return tryAllAdjacentPositions(position)
        .map { adjPosition -> blocksMap[adjPosition]?.number }
        .filter { adjRank -> adjRank == block.number }
        .count() >= 2
}

fun getAllEmptyPositions(): List<Position> {
    return allPositions().mapNotNull { position -> if (!blocksMap.containsKey(position)) position else null }
}

fun getRandomRank(): Rank {
    if (debugAllOnes) return Rank.ZERO
    val random = Random.nextDouble()
    return when {
        random < 0.05 -> Rank.THREE
        random < 0.2 -> Rank.TWO
        random < 0.55 -> Rank.ONE
        else -> Rank.ZERO
    }
}

fun generateBlocksForEmptyPositions(): List<Pair<Position, Block>> {
    return getAllEmptyPositions().map { position ->
        val selectedId = nextBlockId
        nextBlockId++
        Pair(position, Block(selectedId, getRandomRank()))
    }
}

fun determineMerge(positionList: MutableList<Position>): MutableMap<Position, Pair<Rank, List<Position>>> {
    val mergeMap = mutableMapOf<Position, Pair<Rank, List<Position>>>()
    val pattern = determinePattern(positionList)

    var nextRank =
        when (positionList.size) {
            in 0..mediumSelectionSize - 1 -> blocksMap[positionList.first()]?.number?.next() ?: Rank.ZERO
            in mediumSelectionSize..largeSelectionSize - 1 -> blocksMap[positionList.first()]?.number?.next()?.next() ?: Rank.ZERO
            else -> blocksMap[positionList.first()]?.number?.next()?.next()?.next() ?: Rank.ZERO
        }
    when (pattern) {
        Pattern.TRIPLE -> {
            val last = positionList.removeAt(positionList.lastIndex)
            mergeMap[last] = Pair(nextRank, positionList)
        }
        Pattern.I4 -> {
            val last = positionList.removeAt(positionList.lastIndex)
            val secondLast = positionList.removeAt(positionList.lastIndex)
            mergeMap[secondLast] = Pair(nextRank, positionList.subList(0, 1).toMutableList())
            mergeMap[last] = Pair(nextRank, positionList.subList(1, 2).toMutableList())
        }
        Pattern.I5 -> {
            val last = positionList.removeAt(positionList.lastIndex)
            mergeMap[last] = Pair(nextRank.next(), positionList.toMutableList())
        }
        Pattern.I6 -> {
            val last = positionList.removeAt(positionList.lastIndex)
            val secondLast = positionList.removeAt(positionList.lastIndex)
            mergeMap[secondLast] = Pair(nextRank, positionList.subList(0, 2).toMutableList())
            mergeMap[last] = Pair(nextRank, positionList.subList(2, 4).toMutableList())
        }
        Pattern.I7 -> {
            val last = positionList.removeAt(positionList.lastIndex)
            val secondLast = positionList.removeAt(positionList.lastIndex)
            mergeMap[secondLast] = Pair(nextRank, positionList.subList(0, 2).toMutableList())
            mergeMap[last] = Pair(nextRank.next(), positionList.subList(2, 5).toMutableList())
        }
        Pattern.RECTANGLE -> {
            val last = positionList.last()
            val secondLast = positionList[positionList.size - 2]

            val uniqueX =
                positionList.fold(
                    arrayOf<Int>(),
                    {
                            directionArray,
                            position,
                        ->
                        if (directionArray.contains(position.x)) directionArray else directionArray.plus(position.x)
                    },
                )
            val uniqueY =
                positionList.fold(
                    arrayOf<Int>(),
                    {
                            directionArray,
                            position,
                        ->
                        if (directionArray.contains(position.y)) directionArray else directionArray.plus(position.y)
                    },
                )

            Napier.d("UniqueX size: ${uniqueX.size} UniqueY size: ${uniqueY.size}")

            nextRank = blocksMap[positionList.first()]?.number?.next() ?: Rank.ZERO

            if (uniqueX.size == 3 && uniqueY.size == 3) {
                // A full 3x3 collapses into a single block four tiers above the
                // selection (the rest of the rectangle family produces a line). It
                // spawns on the last tile the player selected, so they can choose
                // where the upgraded block lands.
                val sources = positionList.filter { it != last }
                mergeMap[last] = Pair(nextRank.next().next().next(), sources)
            } else if (uniqueX.size == 4 && uniqueY.size == 4) {
                // A full 4x4 collapses into a 2x2 at the centre, each cell four tiers
                // above the selection. Each source flies to its nearest centre cell.
                val xs = uniqueX.sorted()
                val ys = uniqueY.sorted()
                val resultRank = nextRank.next().next().next()
                val centerCells = listOf(
                    Position(xs[1], ys[1]),
                    Position(xs[2], ys[1]),
                    Position(xs[1], ys[2]),
                    Position(xs[2], ys[2]),
                )
                val buckets = centerCells.associateWith { mutableListOf<Position>() }
                positionList
                    .filter { it !in centerCells }
                    .forEach { src ->
                        val nearest = centerCells.minBy { abs(it.x - src.x) + abs(it.y - src.y) }
                        buckets.getValue(nearest).add(src)
                    }
                centerCells.forEach { cell ->
                    mergeMap[cell] = Pair(resultRank, buckets.getValue(cell))
                }
            } else {
                val mergeRank =
                    when (min(uniqueX.size, uniqueY.size)) {
                        in 0..2 -> nextRank
                        3 -> nextRank.next()
                        4 -> nextRank.next().next()
                        5 -> nextRank.next().next().next()
                        else -> nextRank.next().next().next().next()
                    }

                if (uniqueX.size < uniqueY.size || (uniqueX.size == uniqueY.size && last.x == secondLast.x))
                    {
                        for (i in uniqueY) {
                            mergeMap[Position(last.x, i)] = Pair(mergeRank, uniqueX.filter { x -> x != last.x }.map { x -> Position(x, i) })
                        }
                    } else {
                    for (i in uniqueX) {
                        mergeMap[Position(i, last.y)] = Pair(mergeRank, uniqueY.filter { y -> y != last.y }.map { y -> Position(i, y) })
                    }
                }
            }
        }
        else -> {
            val last = positionList.removeAt(positionList.lastIndex)
            mergeMap[last] = Pair(nextRank, positionList)
        }
    }

    return mergeMap
}

fun determineScore(positionList: MutableList<Position>): Int {
    return positionList.map { position -> blocksMap[position]?.number?.value ?: 0 }.fold(0, { a, b -> a + b })
}
