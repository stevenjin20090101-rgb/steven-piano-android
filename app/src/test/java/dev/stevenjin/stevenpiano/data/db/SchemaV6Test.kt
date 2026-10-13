// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager

/**
 * Schema v6 (app 1.24 — M57) against Room's own export, then on a real SQLite (the JVM's, through sqlite-jdbc): a
 * database built exactly as apps 1.14 to 1.23 built it, holding quiet times' blocks among the timed plays' old rows,
 * keeps every quiet block as it was (ids, days, times, names), loses the timed plays' rows, leaves every other table
 * alone, and has the shape a database made new at v6 has (what Room checks when it opens one). No destructive fallback
 * exists, so this is what stands between the upgrade and the school tablet's quiet times.
 */
class SchemaV6Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private val v5 = ExportedSchema.read(5)
    private val v6 = ExportedSchema.read(6)

    /** The quiet times' blocks a 1.23 tablet holds, as v6 keeps them: id, days, start, kind, name, end, made at. */
    private val quiet = listOf(
        listOf("3", "31", "520", "QUIET", "School days", "570", "1000"),
        listOf("4", "31", "580", "QUIET", "School days", "630", "1000"),
        listOf("7", "127", "1260", "QUIET", "Night", "420", "2000"),
        listOf("9", "96", "600", "QUIET", "Weekend's \"quiet\" hour", "660", "3000"),
    )

    @Test
    fun `the migration's table is 6_json's, and nothing else changes`() {
        assertEquals(6, v6.version)
        assertEquals(v6.table("schedules"), SchemaV6.CREATE_SCHEDULES.replace("`_new_schedules`", "`schedules`"))
        assertEquals(
            "the two timed plays' columns go, and nothing else",
            v5.table("schedules").replace(", `volumePct` INTEGER, `enabled` INTEGER NOT NULL DEFAULT 1", ""),
            v6.table("schedules"),
        )
        for (table in v5.tables.keys) {
            if (table != "schedules") assertEquals(v5.table(table), v6.table(table))
            assertEquals(v5.indices(table), v6.indices(table))
        }
        assertEquals(v5.tables.keys, v6.tables.keys)
        assertEquals(v5.views, v6.views)
    }

    @Test
    fun `a 1_23 database migrates, its quiet blocks as they were, the timed plays' rows gone, nothing else changed`() {
        connect("v5.db").use { db ->
            v5.ddl.forEach { db.exec(it) }
            fillAsVersion123(db)
            val before = otherRows(db)
            migrate(db)
            assertEquals(quiet, query(db, "SELECT id, days, startMinute, kind, target, endMinute, createdAt FROM schedules ORDER BY id"))
            assertEquals("no timed play is left", emptyList<List<String>>(), query(db, "SELECT id FROM schedules WHERE kind <> 'QUIET'"))
            assertEquals("every other table as it was", before, otherRows(db))
            // A block saved after the upgrade takes a new id after the kept ones (Room's autoGenerate inserts a null id).
            db.exec("INSERT INTO schedules (id, days, startMinute, kind, target, endMinute, createdAt) VALUES (NULL, 1, 0, 'QUIET', 'New', 30, 4000)")
            val id = query(db, "SELECT id FROM schedules WHERE target = 'New'").single().single().toLong()
            assertTrue("a new id: $id", id > 9)
        }
    }

    @Test
    fun `the migrated database is the one Room expects at v6`() {
        val migrated = connect("migrated.db").use { db ->
            v5.ddl.forEach { db.exec(it) }
            fillAsVersion123(db)
            migrate(db)
            shape(db)
        }
        val fresh = connect("fresh.db").use { db ->
            v6.ddl.forEach { db.exec(it) }
            shape(db)
        }
        assertEquals(fresh, migrated)
    }

    private fun migrate(db: Connection) = SchemaV6.STATEMENTS.forEach { db.exec(it) }

    /**
     * What apps 1.14 to 1.23 hold: two pieces, a playlist, a portrait, a Studio turn, and the schedules table with the
     * quiet times' blocks ([quiet]) among the timed plays' rows (a playlist, a channel and a piece, enabled or not, with a
     * volume or without), the ids interleaved.
     */
    private fun fillAsVersion123(db: Connection) {
        db.exec(
            "INSERT INTO pieces (id, title, composer, composerKey, composerShort, collection, sha256, fileName, sourceName, sizeBytes, " +
                "durationMs, noteCount, addedAt, favorite, playCount, lastPlayedAt, searchText, titleKey, genre) VALUES " +
                "(1, 'Clair de lune', 'Claude Debussy', 'debussy', 'Debussy', NULL, 's1', 's1.mid', 'Clair de lune.mid', 1000, 300000, 900, 10, 1, 4, 99, 'clair de lune debussy', 'clair de lune', 1), " +
                "(2, 'Perfect', 'Ed Sheeran', 'ed sheeran', 'Ed Sheeran', 'MIDI', 's2', 's2.mid', 'Perfect.mid', 800, 240000, 700, 11, 0, 0, NULL, 'perfect ed sheeran', 'perfect', 2)",
        )
        db.exec("INSERT INTO collections (id, name, createdAt, imported, builtIn, builtInKey) VALUES (1, 'MIDI', 10, 1, 0, NULL)")
        db.exec("INSERT INTO collection_pieces (collectionId, pieceId, addedAt, position) VALUES (1, 2, 11, 0)")
        db.exec(
            "INSERT INTO artwork (`key`, imagePath, description, sourceUrl, sourceTitle, fetchedAt, status) VALUES " +
                "('composer:debussy', 'art/debussy.jpg', 'A French composer.', 'https://en.wikipedia.org/wiki/Claude_Debussy', 'Claude Debussy', 16, 'OK')",
        )
        db.exec("INSERT INTO studio_generations (createdAt, kind, understood, pieceId, title, outcome) VALUES (20, 'compose', 'Calm', 1, 'Clair de lune', 'kept')")
        db.exec(
            "INSERT INTO schedules (id, days, startMinute, kind, target, endMinute, volumePct, enabled, createdAt) VALUES " +
                "(1, 31, 750, 'CHANNEL', 'calm', 795, 70, 1, 500), " +
                "(2, 1, 480, 'PLAYLIST', '4', NULL, NULL, 0, 600), " +
                "(5, 64, 600, 'PIECE', '2', 630, 40, 1, 700), " +
                "(8, 127, 0, 'CHANNEL', 'epic', NULL, NULL, 1, 800)",
        )
        for (row in quiet) {
            db.exec(
                "INSERT INTO schedules (id, days, startMinute, kind, target, endMinute, volumePct, enabled, createdAt) " +
                    "VALUES (${row[0]}, ${row[1]}, ${row[2]}, '${row[3]}', '${row[4].replace("'", "''")}', ${row[5]}, NULL, 1, ${row[6]})",
            )
        }
    }

    /** Every row of the five tables v6 leaves alone, as text, in a fixed order. */
    private fun otherRows(db: Connection): Map<String, List<List<String>>> = mapOf(
        "pieces" to query(db, "SELECT * FROM pieces ORDER BY id"),
        "collections" to query(db, "SELECT * FROM collections ORDER BY id"),
        "collection_pieces" to query(db, "SELECT * FROM collection_pieces ORDER BY collectionId, pieceId"),
        "artwork" to query(db, "SELECT * FROM artwork ORDER BY `key`"),
        "studio_generations" to query(db, "SELECT * FROM studio_generations ORDER BY id"),
    )

    /** What Room compares when it opens a database: columns, indices and foreign keys per table, and the views. Sorted. */
    private fun shape(db: Connection): List<String> {
        val tables = query(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").map { it[0] }
        val out = ArrayList<String>()
        for (table in tables) {
            query(db, "PRAGMA table_info(`$table`)").forEach { c -> out += "$table column ${c[1]} ${c[2]} notNull=${c[3]} default=${c[4]} pk=${c[5]}" }
            for (index in query(db, "PRAGMA index_list(`$table`)")) {
                if (index[1].startsWith("sqlite_autoindex")) continue
                val columns = query(db, "PRAGMA index_info(`${index[1]}`)").sortedBy { it[0].toInt() }.joinToString(",") { it[2] }
                out += "$table index ${index[1]} unique=${index[2]} ($columns)"
            }
            query(db, "PRAGMA foreign_key_list(`$table`)").forEach { fk -> out += "$table foreign key ${fk[3]} -> ${fk[2]}.${fk[4]} update=${fk[5]} delete=${fk[6]}" }
        }
        query(db, "SELECT name FROM sqlite_master WHERE type = 'view' ORDER BY name").forEach { out += "view ${it[0]}" }
        return out.sorted()
    }

    private fun connect(name: String): Connection = DriverManager.getConnection("jdbc:sqlite:${tmp.root.resolve(name).path}")

    private fun query(db: Connection, sql: String): List<List<String>> = db.createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            val columns = result.metaData.columnCount
            val out = ArrayList<List<String>>()
            while (result.next()) out += (1..columns).map { result.getString(it) ?: "null" }
            out
        }
    }

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }
}
