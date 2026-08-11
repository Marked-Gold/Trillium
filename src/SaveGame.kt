import korlibs.korge.service.storage.storage
import korlibs.korge.view.Stage
import korlibs.korge.view.Views

/**
 * Manual save / load of a run in progress, reached from the SAVED GAME row in the pause menu.
 *
 * Exists because the OS can reclaim a backgrounded game and wipe an in-progress run — most painfully
 * overnight. An explicit save lets the player put the phone down knowing the run can be brought back.
 *
 * There is exactly **one** slot: saving over an existing save replaces it (the UI warns first), and
 * loading leaves the save in place so it can be loaded again. The slot survives app restarts because
 * it lives in the same key-value storage as the high scores.
 *
 * Only the run's own state is captured — board, score, power-ups, game mode. Everything else is
 * either a preference that should keep whatever the player has set since (theme, sound, haptics) or
 * deliberately not carried over: [Undo] history is dropped, since the moves it points back to belong
 * to the run being replaced.
 *
 * The stored payload is a single flat string, versioned so a payload this build does not understand
 * is ignored rather than misread:
 *
 *     1|<columns>|<rows>|<gravity 0|1>|<score>|<bombs>|<rockets>|<cell>,<cell>,...
 *
 * with one cell per grid position in row-major order — the block's [Rank] ordinal, or `-` for an
 * empty cell. Block ids are deliberately not stored: they are only ever used for identity within a
 * session, so restored blocks are handed fresh ones and can never collide with live blocks.
 */
object SaveGame {
    /** Storage key holding the single save slot. */
    const val storageKey = "savedGame"

    private const val formatVersion = "1"
    private const val fieldSeparator = "|"
    private const val cellSeparator = ","
    private const val emptyCellToken = "-"

    /** Everything needed to put a run back on the board. */
    data class Snapshot(
        val blocks: Map<Position, Rank>,
        val score: Int,
        val bombs: Int,
        val rockets: Int,
        val gravity: Boolean,
        val columns: Int = gridColumns,
        val rows: Int = gridRows,
    )

    /** Snapshots the live game state. The board must be settled — see the SAVE button's guard. */
    fun captureCurrent(): Snapshot =
        Snapshot(
            blocks = blocksMap.mapValues { (_, block) -> block.number },
            score = score.value,
            bombs = bombsLoadedCount.value,
            rockets = rocketsLoadedCount.value,
            gravity = gravityModeEnabled.value,
        )

    fun encode(snapshot: Snapshot): String {
        val cells =
            (0 until snapshot.columns * snapshot.rows).joinToString(cellSeparator) { index ->
                val position = Position(index % snapshot.columns, index / snapshot.columns)
                snapshot.blocks[position]?.ordinal?.toString() ?: emptyCellToken
            }
        return listOf(
            formatVersion,
            snapshot.columns.toString(),
            snapshot.rows.toString(),
            if (snapshot.gravity) "1" else "0",
            snapshot.score.toString(),
            snapshot.bombs.toString(),
            snapshot.rockets.toString(),
            cells,
        ).joinToString(fieldSeparator)
    }

    /**
     * Parses a stored payload, or returns null if there is nothing to load. Every failure mode —
     * absent, truncated, written by a different format version, or sized for a different grid —
     * lands on null, so a corrupt slot simply reads as "no saved game" instead of crashing the
     * pause menu.
     */
    fun decode(raw: String?): Snapshot? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(fieldSeparator)
        if (parts.size != 8) return null
        if (parts[0] != formatVersion) return null

        val columns = parts[1].toIntOrNull() ?: return null
        val rows = parts[2].toIntOrNull() ?: return null
        // A save from a differently-sized board cannot be mapped onto this one.
        if (columns != gridColumns || rows != gridRows) return null

        val gravity =
            when (parts[3]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
        val score = parts[4].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val bombs = parts[5].toIntOrNull()?.takeIf { it in 0..maxBombCount } ?: return null
        val rockets = parts[6].toIntOrNull()?.takeIf { it in 0..maxRocketCount } ?: return null

        val cells = parts[7].split(cellSeparator)
        if (cells.size != columns * rows) return null
        val ranks = Rank.values()
        val blocks = mutableMapOf<Position, Rank>()
        cells.forEachIndexed { index, cell ->
            if (cell == emptyCellToken) return@forEachIndexed
            val ordinal = cell.toIntOrNull() ?: return null
            if (ordinal !in ranks.indices) return null
            blocks[Position(index % columns, index / columns)] = ranks[ordinal]
        }

        return Snapshot(blocks, score, bombs, rockets, gravity, columns, rows)
    }

    /** The saved run, or null when the slot is empty (or unreadable). */
    fun peek(views: Views): Snapshot? = decode(views.storage.getOrNull(storageKey))

    /** Writes [snapshot] into the single slot, replacing whatever was there. */
    fun write(
        views: Views,
        snapshot: Snapshot = captureCurrent(),
    ) {
        views.storage[storageKey] = encode(snapshot)
        Napier.d("Saved game: score ${snapshot.score}, ${snapshot.blocks.size} blocks, gravity ${snapshot.gravity}")
    }
}

/**
 * Puts a saved run back on the board, replacing whatever is being played. Mirrors [Undo.restoreLatest]
 * — wipe the live block views, rebuild from the snapshot, then push the counters back — with the
 * extras a cross-session restore needs: the game mode, and the background's progress glow.
 */
fun Stage.loadSavedGame(snapshot: SaveGame.Snapshot) {
    Napier.d("Loading saved game: score ${snapshot.score}, gravity ${snapshot.gravity}")

    // Restore the mode *before* the score: the score observer credits a new best to whichever mode
    // is active, and a gravity run's score must never land on the classic best (or vice versa).
    if (gravityModeEnabled.value != snapshot.gravity) {
        gravityModeEnabled.update(snapshot.gravity)
        views.storage[gravityModeEnabledKey] = snapshot.gravity.toString()
    }

    resetIdleTimer()
    // The replaced run's history has nothing to do with the restored board.
    Undo.clear()
    hoveredPositions.clear()
    hoveredBombPositions.clear()
    rocketSelection.unselect()

    blocksMap.values.forEach { it.removeFromParent() }
    blocksMap.clear()
    // Drop any gravity blocks still mid-drop so none are left hidden/pending on the restored board.
    gravityPendingReveal.clear()
    snapshot.blocks.forEach { (position, rank) ->
        val id = nextBlockId
        nextBlockId++
        blocksMap[position] = Block(id, rank)
    }
    drawAllBlocks()

    score.update(snapshot.score)
    bombsLoadedCount.update(snapshot.bombs)
    rocketsLoadedCount.update(snapshot.rockets)

    // Put the background glow back where the restored run had it rather than at a new game's
    // opening green — the highest tier still on the board is the closest stand-in for the highest
    // tier that run ever forged, and it never drops below the opening tier.
    val topTier = snapshot.blocks.values.maxByOrNull { it.ordinal } ?: Rank.THREE
    setBackgroundGradientTier(if (topTier.ordinal < Rank.THREE.ordinal) Rank.THREE else topTier)

    // A saved board is normally playable, but it may have been saved one move from a dead end with
    // the power-ups already spent — re-check so the game-over screen still appears when it should.
    checkGameOver()
}
