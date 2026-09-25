// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema v1 (app 1.1) to v2 (app 1.2): playlists gain an order, and artwork gets its table.
 * The DDL is [SchemaV2.DDL], copied from Room's generated `schemas/…/2.json` (SchemaV2Test
 * holds the two equal). Then every playlist's positions are filled in, in the order its pieces
 * were added (title for ties), numbered here in Kotlin because API 26's SQLite has no window
 * functions. Runs inside Room's migration transaction.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SchemaV2.DDL.forEach(db::execSQL)
        backfillPositions(db)
    }
}

/** Numbers each playlist's links 0, 1, 2… in [SchemaV2.LINKS_IN_ORDER]'s order; 0 is the column's default already. */
private fun backfillPositions(db: SupportSQLiteDatabase) {
    val playlists = ArrayList<Long>()
    val pieces = ArrayList<Long>()
    db.query(SchemaV2.LINKS_IN_ORDER).use { cursor ->
        while (cursor.moveToNext()) {
            playlists += cursor.getLong(0)
            pieces += cursor.getLong(1)
        }
    }
    val positions = SchemaV2.positions(playlists.toLongArray())
    db.compileStatement(SchemaV2.SET_POSITION).use { statement ->
        for (i in positions.indices) {
            if (positions[i] == 0) continue
            statement.bindLong(1, positions[i].toLong())
            statement.bindLong(2, playlists[i])
            statement.bindLong(3, pieces[i])
            statement.executeUpdateDelete()
            statement.clearBindings()
        }
    }
}

/** Schema v2's statements, as Room generated them in `2.json`, and the position numbering. Pure: unit-tested. */
object SchemaV2 {
    /** `collection_pieces.position`, exactly as 2.json's createSql for the table declares it. */
    const val POSITION_COLUMN = "`position` INTEGER NOT NULL DEFAULT 0"

    /** The column, added the way Room's own auto-migrations add a column with a default. */
    const val ADD_POSITION = "ALTER TABLE `collection_pieces` ADD COLUMN $POSITION_COLUMN"

    /** 2.json's createSql for the new index, with the table name filled in. */
    const val CREATE_POSITION_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_collection_pieces_collectionId_position` ON `collection_pieces` (`collectionId`, `position`)"

    /** 2.json's createSql for the artwork table, with the table name filled in. */
    const val CREATE_ARTWORK =
        "CREATE TABLE IF NOT EXISTS `artwork` (`key` TEXT NOT NULL, `imagePath` TEXT, `description` TEXT, `sourceUrl` TEXT, " +
            "`sourceTitle` TEXT, `fetchedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`key`))"

    /** The migration's DDL, in order. */
    val DDL: List<String> = listOf(ADD_POSITION, CREATE_POSITION_INDEX, CREATE_ARTWORK)

    /** Every playlist link, playlist by playlist, each in the order its pieces were added (title, then id, for ties). */
    const val LINKS_IN_ORDER =
        "SELECT cp.collectionId, cp.pieceId FROM collection_pieces cp LEFT JOIN pieces p ON p.id = cp.pieceId " +
            "ORDER BY cp.collectionId, cp.addedAt, p.titleKey, cp.pieceId"

    const val SET_POSITION = "UPDATE collection_pieces SET position = ? WHERE collectionId = ? AND pieceId = ?"

    /**
     * Each link's place within its own playlist: [playlistIds] holds the playlist of every link
     * in playing order; the result numbers each playlist's links 0, 1, 2… in that order, however
     * the playlists interleave.
     */
    fun positions(playlistIds: LongArray): IntArray {
        val next = HashMap<Long, Int>()
        return IntArray(playlistIds.size) { i ->
            val playlist = playlistIds[i]
            val position = next[playlist] ?: 0
            next[playlist] = position + 1
            position
        }
    }
}
