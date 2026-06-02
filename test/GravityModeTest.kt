import korlibs.image.font.*
import korlibs.io.file.std.*
import korlibs.korge.animate.*
import korlibs.korge.tests.*
import korlibs.korge.tween.*
import korlibs.korge.view.*
import korlibs.time.*
import kotlin.test.*

/**
 * Guards the core gravity-mode refill math (see [animateGravityRefill]): when a merge or bomb
 * leaves gaps, every column's surviving blocks must fall straight to the floor in their existing
 * top-to-bottom order, the vacated top cells must fill with brand-new blocks, and the board must
 * end completely full. These are the invariants an off-by-one in the column walk would break.
 */
class GravityModeTest : ViewsForTesting() {

    private suspend fun Stage.prepareBoard() {
        // Block's constructor renders its rect + number immediately, so geometry and font must be
        // set before any block is created.
        cellSize = 32
        font = resourcesVfs["clear_sans.fnt"].readBitmapFont()
        blocksMap = mutableMapOf()
        // Shared global; clear it so reveal state never leaks between tests.
        gravityPendingReveal.clear()
    }

    @Test
    fun gravitySettlesSurvivorsToTheFloorAndRefillsFromTheTop() =
        viewsTest {
            prepareBoard()

            // Column 0: occupied at rows 1, 3, 6 (top -> bottom) with known ids, gaps elsewhere.
            blocksMap[Position(0, 1)] = Block(100, Rank.ONE)
            blocksMap[Position(0, 3)] = Block(101, Rank.ONE)
            blocksMap[Position(0, 6)] = Block(102, Rank.ONE)
            // Column 1: completely full — must not move.
            for (row in 0 until gridRows) blocksMap[Position(1, row)] = Block(200 + row, Rank.TWO)
            // Columns 2..6: left empty — must become all-fresh columns.

            val originalEmpties = gridRows * gridColumns - blocksMap.size
            // Fresh blocks spawned by the refill get ids from here up, so id >= 1000 marks "new".
            nextBlockId = 1000

            animate { animateGravityRefill(views.stage) }

            // 1. The board is completely full afterwards.
            assertEquals(
                gridRows * gridColumns,
                blocksMap.size,
                "gravity must leave every cell filled",
            )

            // 2. Column 0 survivors fell to the bottom, preserving their top-to-bottom order.
            assertEquals(100, blocksMap[Position(0, 4)]?.id, "topmost survivor sits highest in the settled stack")
            assertEquals(101, blocksMap[Position(0, 5)]?.id, "middle survivor keeps its relative order")
            assertEquals(102, blocksMap[Position(0, 6)]?.id, "bottom survivor stays on the floor")
            // ...and the vacated top cells are brand-new blocks.
            for (row in 0 until 4) {
                assertTrue(
                    (blocksMap[Position(0, row)]?.id ?: -1) >= 1000,
                    "vacated top cell ($row) must hold a fresh block",
                )
            }

            // 3. A column that was already full is untouched.
            for (row in 0 until gridRows) {
                assertEquals(200 + row, blocksMap[Position(1, row)]?.id, "a full column must not shift under gravity")
            }

            // 4. Exactly one fresh block was spawned per vacated cell — no over/under-fill.
            val freshCount = blocksMap.values.count { it.id >= 1000 }
            assertEquals(originalEmpties, freshCount, "one fresh block per vacated cell")
        }

    @Test
    fun gravityRevealHidesBlocksAboveTheBoardAndShowsThemOnceTheyLand() =
        viewsTest {
            prepareBoard()
            topIndent = 100 // the playfield's top edge for this test

            // A block still above the board, and one that has dropped onto it — both start hidden
            // and queued, as animateGravityRefill leaves them.
            val above = Block(7000, Rank.ZERO).apply { y = 40.0; visible = false }
            val landed = Block(7001, Rank.ZERO).apply { y = 150.0; visible = false }
            gravityPendingReveal.add(above)
            gravityPendingReveal.add(landed)

            revealLandedGravityBlocks()

            assertFalse(above.visible, "a block still above the board must stay hidden")
            assertTrue(landed.visible, "a block that has dropped onto the board must be revealed")
            assertEquals(listOf(above), gravityPendingReveal, "only landed blocks leave the reveal queue")
        }

    @Test
    fun newBlocksEndUpVisibleOnTheStageWithTheRevealQueueDrained() =
        viewsTest {
            prepareBoard()
            // Fill every column except column 0, so the fall produces a full column of fresh blocks.
            for (x in 0 until gridColumns) {
                if (x == 0) continue
                for (y in 0 until gridRows) blocksMap[Position(x, y)] = Block(x * 10 + y, Rank.ZERO)
            }
            nextBlockId = 7000

            animate { animateGravityRefill(views.stage) }
            // Advance a full second of frames (comfortably past gravityFallTime) so the drop completes.
            val clock = solidRect(1, 1)
            tween(clock::x[2], time = 1.0.seconds)

            val fresh = blocksMap.values.filter { it.id >= 7000 }
            assertEquals(gridRows, fresh.size, "the empty column should produce a full column of fresh blocks")
            fresh.forEach {
                assertSame(views.stage, it.parent, "a new block must end up on the stage")
                assertTrue(it.visible, "no new block may be left hidden once it has dropped onto the board")
            }
            assertTrue(gravityPendingReveal.isEmpty(), "the pending-reveal queue must drain once blocks land")
        }

    @Test
    fun gravityFillsAFullyClearedColumnEntirely() =
        viewsTest {
            prepareBoard()
            // Fill every column except column 3; column 3 is entirely empty.
            for (x in 0 until gridColumns) {
                if (x == 3) continue
                for (y in 0 until gridRows) blocksMap[Position(x, y)] = Block(x * 10 + y, Rank.ZERO)
            }
            nextBlockId = 5000

            animate { animateGravityRefill(views.stage) }

            assertEquals(gridRows * gridColumns, blocksMap.size, "board must be full")
            for (y in 0 until gridRows) {
                assertTrue(
                    (blocksMap[Position(3, y)]?.id ?: -1) >= 5000,
                    "the empty column must fill entirely with fresh blocks",
                )
            }
        }
}
