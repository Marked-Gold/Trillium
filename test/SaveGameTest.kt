import korlibs.image.font.readBitmapFont
import korlibs.io.file.std.resourcesVfs
import korlibs.korge.tests.ViewsForTesting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards the saved-game codec (see [SaveGame]). The payload is the only thing that survives an app
 * restart, so it has to round-trip exactly — and, just as importantly, every malformed payload has
 * to decode to null rather than throw: a corrupt slot must read as "no saved game", not crash the
 * pause menu on the way in.
 *
 * Pure string/enum work, so no [korlibs.korge.tests.ViewsForTesting] harness is needed — the codec
 * deliberately deals in ranks and positions rather than live [Block] views.
 */
class SaveGameTest : ViewsForTesting() {
    private fun boardOfEvery(rank: Rank): MutableMap<Position, Rank> =
        allPositions().associateWith { rank }.toMutableMap()

    private fun sampleSnapshot(): SaveGame.Snapshot {
        val blocks = boardOfEvery(Rank.ZERO)
        // A few distinct ranks in known cells so a row/column transposition in the codec shows up.
        blocks[Position(0, 0)] = Rank.NINETEEN
        blocks[Position(1, 0)] = Rank.THREE
        blocks[Position(0, 1)] = Rank.FIVE
        blocks[Position(gridColumns - 1, gridRows - 1)] = Rank.TWELVE
        // Gravity mode can leave the board mid-refill; an empty cell must survive the trip too.
        blocks.remove(Position(3, 4))
        return SaveGame.Snapshot(
            blocks = blocks,
            score = 123456,
            bombs = 2,
            rockets = 3,
            gravity = true,
        )
    }

    @Test
    fun roundTripsEveryFieldIncludingEmptyCells() {
        val original = sampleSnapshot()
        val restored = SaveGame.decode(SaveGame.encode(original))

        assertEquals(original, restored)
        // Spelled out so a change that silently drops a field can't pass on data-class equality alone.
        assertEquals(original.blocks, restored?.blocks)
        assertEquals(123456, restored?.score)
        assertEquals(2, restored?.bombs)
        assertEquals(3, restored?.rockets)
        assertEquals(true, restored?.gravity)
        assertNull(restored?.blocks?.get(Position(3, 4)), "the empty cell must stay empty")
        assertEquals(Rank.NINETEEN, restored?.blocks?.get(Position(0, 0)))
        assertEquals(Rank.FIVE, restored?.blocks?.get(Position(0, 1)), "cells are row-major")
        assertEquals(Rank.THREE, restored?.blocks?.get(Position(1, 0)), "cells are row-major")
        assertEquals(Rank.TWELVE, restored?.blocks?.get(Position(gridColumns - 1, gridRows - 1)))
    }

    @Test
    fun roundTripsAClassicRunWithNoPowerUps() {
        val original =
            SaveGame.Snapshot(
                blocks = boardOfEvery(Rank.ONE),
                score = 0,
                bombs = 0,
                rockets = 0,
                gravity = false,
            )
        assertEquals(original, SaveGame.decode(SaveGame.encode(original)))
    }

    @Test
    fun decodesMissingOrEmptySlotAsNoSavedGame() {
        assertNull(SaveGame.decode(null))
        assertNull(SaveGame.decode(""))
        assertNull(SaveGame.decode("   "))
    }

    @Test
    fun rejectsMalformedPayloadsInsteadOfThrowing() {
        val valid = SaveGame.encode(sampleSnapshot())
        val parts = valid.split("|")

        // A payload written by a format this build does not know.
        assertNull(SaveGame.decode(parts.toMutableList().also { it[0] = "2" }.joinToString("|")))
        // Truncated / overlong field lists.
        assertNull(SaveGame.decode(parts.dropLast(1).joinToString("|")))
        assertNull(SaveGame.decode("$valid|extra"))
        // A board of a different size cannot be mapped onto this one.
        assertNull(SaveGame.decode(parts.toMutableList().also { it[1] = "9" }.joinToString("|")))
        assertNull(SaveGame.decode(parts.toMutableList().also { it[2] = "9" }.joinToString("|")))
        // Non-numeric or out-of-range scalars.
        assertNull(SaveGame.decode(parts.toMutableList().also { it[3] = "yes" }.joinToString("|")))
        assertNull(SaveGame.decode(parts.toMutableList().also { it[4] = "abc" }.joinToString("|")))
        assertNull(SaveGame.decode(parts.toMutableList().also { it[4] = "-1" }.joinToString("|")))
        assertNull(SaveGame.decode(parts.toMutableList().also { it[5] = "99" }.joinToString("|")))
        assertNull(SaveGame.decode(parts.toMutableList().also { it[6] = "99" }.joinToString("|")))
        // Cell list of the wrong length, and a rank ordinal outside the enum.
        assertNull(SaveGame.decode(parts.toMutableList().also { it[7] = "0,0,0" }.joinToString("|")))
        assertNull(
            SaveGame.decode(
                parts.toMutableList().also { it[7] = it[7].replaceFirst("19", "99") }.joinToString("|"),
            ),
        )
        assertNull(
            SaveGame.decode(
                parts.toMutableList().also { it[7] = it[7].replaceFirst("19", "x") }.joinToString("|"),
            ),
        )
    }

    @Test
    fun capturesTheBoardsRanksScoreAndPowerUps() =
        viewsTest {
            // Block's constructor renders its rect + number immediately, so geometry and font must
            // be set before any block is created.
            cellSize = 32
            font = resourcesVfs["clear_sans.fnt"].readBitmapFont()
            blocksMap = mutableMapOf()
            blocksMap[Position(2, 2)] = Block(1, Rank.SEVEN)
            blocksMap[Position(4, 5)] = Block(2, Rank.TWO)
            score.update(4242)
            bombsLoadedCount.update(1)
            rocketsLoadedCount.update(4)
            gravityModeEnabled.update(false)

            val captured = SaveGame.captureCurrent()

            assertEquals(mapOf(Position(2, 2) to Rank.SEVEN, Position(4, 5) to Rank.TWO), captured.blocks)
            assertEquals(4242, captured.score)
            assertEquals(1, captured.bombs)
            assertEquals(4, captured.rockets)
            assertEquals(false, captured.gravity)
            assertEquals(gridColumns, captured.columns)
            assertEquals(gridRows, captured.rows)
            // And it survives the trip to storage and back.
            assertEquals(captured, SaveGame.decode(SaveGame.encode(captured)))
        }
}
