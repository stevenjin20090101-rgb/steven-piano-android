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
 * Schema v4 (app 1.12 — M30) against Room's own export, then on a real SQLite (the JVM's, through sqlite-jdbc):
 * a database built exactly as apps 1.5 to 1.11 built it, holding a library, a composition and a transcription
 * Studio made (one without its sheet's line), keeps every row, gives each Studio piece a turn, ends with the
 * shape a database made new at v4 has (what Room checks when it opens one), nulls a turn's piece when the piece
 * is deleted, and runs the history's own statements ([GenerationSql]) as the app does: the one-off import,
 * Keep and Discard, the list of "Made in Studio", the interrupted turns and the trim. No destructive fallback
 * exists, so this is what stands between an upgrade and a lost library.
 */
class SchemaV4Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private val v3 = ExportedSchema.read(3)
    private val v4 = ExportedSchema.read(4)

    @Test
    fun `the migration's statements are 4_json's, and everything else is as v3 left it`() {
        assertEquals(4, v4.version)
        assertEquals(v4.table("studio_generations"), SchemaV4.CREATE_GENERATIONS)
        assertEquals(v4.indices("studio_generations").values.toSet(), SchemaV4.DDL.drop(1).toSet())
        for (table in v3.tables.keys) {
            assertEquals(v3.table(table), v4.table(table))
            assertEquals(v3.indices(table), v4.indices(table))
        }
        assertEquals(v3.views, v4.views)
        assertEquals(v3.tables.keys + "studio_generations", v4.tables.keys)
        val verbs = (SchemaV4.DDL + SchemaV4.BACKFILL).map { it.trimStart().substringBefore(' ').uppercase() }.toSet()
        assertEquals("nothing is dropped or rewritten", setOf("CREATE", "INSERT"), verbs)
    }

    @Test
    fun `a 1_11 database with Studio pieces migrates, keeps every row and gives each Studio piece a turn`() {
        connect("v3.db").use { db ->
            v3.ddl.forEach { db.exec(it) }
            fillAsVersion111(db)
            val before = rows(db)
            migrate(db)
            assertEquals(before, rows(db))
            assertEquals(
                listOf(
                    listOf("1", "20", "compose", "Made in Studio · in the manner of Clair de lune (Claude Debussy)", "4", "Composition · Sep 28, 2026 2:05 PM", "kept", "", "null", "null"),
                    listOf("2", "21", "transcribe", "Made in Studio · Sep 29, 2026", "5", "My take", "kept", "", "null", "null"),
                    listOf("3", "22", "transcribe", "Made in Studio", "6", "Another take", "kept", "", "null", "null"),
                ),
                query(db, "SELECT id, createdAt, kind, understood, pieceId, title, outcome, unused, prompt, coverKind FROM studio_generations ORDER BY id"),
            )
        }
    }

    @Test
    fun `the migrated database is the one Room expects at v4`() {
        val migrated = connect("migrated.db").use { db ->
            v3.ddl.forEach { db.exec(it) }
            fillAsVersion111(db)
            migrate(db)
            shape(db)
        }
        val fresh = connect("fresh.db").use { db ->
            v4.ddl.forEach { db.exec(it) }
            shape(db)
        }
        assertEquals(fresh, migrated)
    }

    @Test
    fun `deleting a piece nulls its turn's piece and seed, and the turn stays`() {
        connect("fk.db").use { db ->
            db.exec("PRAGMA foreign_keys = ON")   // Room turns them on as it opens the database
            v3.ddl.forEach { db.exec(it) }
            fillAsVersion111(db)
            migrate(db)
            db.exec("UPDATE studio_generations SET seedPieceId = 1, parentId = NULL WHERE id = 1")
            db.exec("INSERT INTO studio_generations (createdAt, kind, parentId, outcome) VALUES (30, 'compose', 1, 'failed')")
            db.exec("DELETE FROM pieces WHERE id IN (1, 4)")
            assertEquals(listOf(listOf("null", "null", "kept")), query(db, "SELECT pieceId, seedPieceId, outcome FROM studio_generations WHERE id = 1"))
            db.exec("DELETE FROM studio_generations WHERE id = 1")
            assertEquals(listOf(listOf("null")), query(db, "SELECT parentId FROM studio_generations WHERE id = 4"))
            assertEquals(listOf(listOf("2"), listOf("3"), listOf("4")), query(db, "SELECT id FROM studio_generations ORDER BY id"))
        }
    }

    @Test
    fun `the history's statements - the one-off import, Keep and Discard, Made in Studio, interrupted turns and the trim`() {
        connect("sql.db").use { db ->
            v3.ddl.forEach { db.exec(it) }
            fillAsVersion111(db)
            migrate(db)
            // The old store said pieces 4 and 6 still waited (and 99, a recording, which has no turn).
            assertEquals(2, db.update(GenerationSql.REOPEN.replace(":ids", "4, 6, 99")))
            assertEquals(0, db.update(GenerationSql.REOPEN.replace(":ids", "4, 6, 99")))   // run again: nothing more
            assertEquals(listOf("4", "6"), query(db, GenerationSql.UNDECIDED).map { it[0] })
            assertEquals(1, db.update(GenerationSql.DECIDE.replace(":outcome", "'kept'").replace(":pieceId", "4")))
            assertEquals(0, db.update(GenerationSql.DECIDE.replace(":outcome", "'discarded'").replace(":pieceId", "4")))   // decided already
            assertEquals(listOf("6"), query(db, GenerationSql.UNDECIDED).map { it[0] })
            assertEquals(listOf("6", "5", "4"), query(db, GenerationSql.MADE_HERE).map { it[0] })
            db.exec("INSERT INTO studio_generations (createdAt, kind, outcome) VALUES (40, 'compose', 'pending')")
            assertEquals(1, db.update(GenerationSql.INTERRUPT_PENDING))
            assertEquals(listOf(listOf("interrupted")), query(db, "SELECT outcome FROM studio_generations WHERE createdAt = 40"))
            // The trim keeps the newest and every turn whose piece still waits, however old.
            assertEquals(2, db.update(GenerationSql.TRIM.replace(":keep", "1")))
            assertEquals(listOf(listOf("3", "6", "made"), listOf("4", "null", "interrupted")), query(db, "SELECT id, pieceId, outcome FROM studio_generations ORDER BY id"))
        }
    }

    private fun migrate(db: Connection) {
        SchemaV4.DDL.forEach { db.exec(it) }
        db.exec(SchemaV4.BACKFILL)
    }

    /** What apps 1.7 to 1.11 hold: a library, a playlist, two portraits, and three pieces Studio made with their sheet's lines (one without). */
    private fun fillAsVersion111(db: Connection) {
        db.exec(
            "INSERT INTO pieces (id, title, composer, composerKey, composerShort, collection, sha256, fileName, sourceName, sizeBytes, " +
                "durationMs, noteCount, addedAt, favorite, playCount, lastPlayedAt, searchText, titleKey) VALUES " +
                "(1, 'Clair de lune', 'Claude Debussy', 'debussy', 'Debussy', 'piano-midi.de', 'a1', 'a1.mid', 'debussy/deb_clai.mid', 17112, 301000, 1720, 10, 1, 3, 20, 'clair de lune claude debussy', 'clair de lune'), " +
                "(2, 'Nocturne Op. 9 No. 2', 'Frédéric Chopin', 'chopin', 'Chopin', NULL, 'b2', 'b2.mid', 'nocturne.mid', 9000, 262000, 1300, 11, 0, 0, NULL, 'nocturne op. 9 no. 2 frederic chopin', 'nocturne op. 9 no. 2'), " +
                "(3, 'Recording · Sep 30, 2026 9:15 AM', 'Recorded live', 'recorded live', 'Recorded live', NULL, 'c3', 'c3.mid', 'Recording.mid', 900, 42000, 318, 12, 0, 0, NULL, 'recording recorded live', 'recording · sep 30, 2026 9:15 am'), " +
                "(4, 'Composition · Sep 28, 2026 2:05 PM', 'Made in Studio', 'made in studio', 'Made in Studio', NULL, 'd4', 'd4.mid', 'Composition.mid', 4000, 120000, 700, 20, 0, 1, 30, 'composition made in studio', 'composition · sep 28, 2026 2:05 pm'), " +
                "(5, 'My take', 'Made in Studio', 'made in studio', 'Made in Studio', NULL, 'e5', 'e5.mid', 'My take.mid', 3000, 90000, 500, 21, 0, 0, NULL, 'my take made in studio', 'my take'), " +
                "(6, 'Another take', 'Made in Studio', 'made in studio', 'Made in Studio', NULL, 'f6', 'f6.mid', 'Another take.mid', 3000, 90000, 500, 22, 0, 0, NULL, 'another take made in studio', 'another take')",
        )
        db.exec("INSERT INTO collections (id, name, createdAt, imported, builtIn, builtInKey) VALUES (1, 'piano-midi.de', 10, 1, 0, NULL), (2, 'Recordings', 12, 0, 1, 'recordings')")
        db.exec("INSERT INTO collection_pieces (collectionId, pieceId, addedAt, position) VALUES (1, 1, 10, 0), (2, 3, 12, 0)")
        db.exec(
            "INSERT INTO artwork (`key`, imagePath, description, sourceUrl, sourceTitle, fetchedAt, status) VALUES " +
                "('composer:debussy', 'art/debussy.jpg', 'A French composer.', 'https://en.wikipedia.org/wiki/Claude_Debussy', 'Claude Debussy', 16, 'OK'), " +
                "('piece:3', NULL, 'Recorded live · Sep 30, 2026', NULL, NULL, 12, 'OK'), " +
                "('piece:4', NULL, 'Made in Studio · in the manner of Clair de lune (Claude Debussy)', NULL, NULL, 20, 'OK'), " +
                "('piece:5', NULL, 'Made in Studio · Sep 29, 2026', NULL, NULL, 21, 'OK')",
        )
        db.exec("INSERT INTO schedules (days, startMinute, kind, target, createdAt) VALUES (31, 750, 'CHANNEL', 'calm', 1)")
    }

    /** Every row of v3's five tables, as text, in a fixed order. */
    private fun rows(db: Connection): Map<String, List<List<String>>> = mapOf(
        "pieces" to query(db, "SELECT * FROM pieces ORDER BY id"),
        "collections" to query(db, "SELECT * FROM collections ORDER BY id"),
        "collection_pieces" to query(db, "SELECT * FROM collection_pieces ORDER BY collectionId, pieceId"),
        "artwork" to query(db, "SELECT * FROM artwork ORDER BY `key`"),
        "schedules" to query(db, "SELECT * FROM schedules ORDER BY id"),
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

    private fun Connection.update(sql: String): Int = createStatement().use { it.executeUpdate(sql) }
}
