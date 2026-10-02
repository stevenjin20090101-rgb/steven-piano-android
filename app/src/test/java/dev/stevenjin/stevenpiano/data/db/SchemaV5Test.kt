// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import dev.stevenjin.stevenpiano.data.Genres
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager

/**
 * Schema v5 (app 1.14 — M37) against Room's own export, then on a real SQLite (the JVM's, through sqlite-jdbc): a
 * database built exactly as apps 1.12 to 1.13.1 built it, holding pack pieces, a canonical composer's upload, an
 * artist's, unnamed ones, a Studio piece and a recording, keeps every row, ends with each piece sorted exactly as the
 * pure rule ([Genres.of]) sorts it, and has the shape a database made new at v5 has (what Room checks when it opens
 * one). No destructive fallback exists, so this is what stands between the upgrade and the school tablet's library.
 */
class SchemaV5Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private val v4 = ExportedSchema.read(4)
    private val v5 = ExportedSchema.read(5)

    /** The 1.13 library's pieces, ids 1 to 17: their key and collection, and the genre the upgrade gives them. */
    private val cases = listOf(
        Triple("medtner", "maestro-classical-performances", 1),
        Triple("debussy", null, 1),
        Triple("ed sheeran", null, 2),
        Triple("", null, 2),
        Triple("", "mutopia-public-domain", 1),
        Triple("made in studio", null, 0),
        Triple("recorded live", null, 0),
        Triple("medtner", null, 1),
        Triple("bach cpe", "piano-midi.de", 1),
        Triple("bach jc", null, 1),
        Triple("adam levine", null, 2),
        Triple("adam ant", null, 2),
        Triple("anonymous", "piano-midi.de", 1),
        Triple("anonymous", null, 1),   // by the second pass: another Anonymous is the pack's
        Triple("stay", null, 2),
        Triple("kapustin", null, 2),
        Triple("traditional", null, 1),
    )

    @Test
    fun `the migration's statements are 5_json's, and nothing else changes`() {
        assertEquals(5, v5.version)
        assertEquals(v4.table("pieces").removeSuffix(")") + ", " + SchemaV5.GENRE_COLUMN + ")", v5.table("pieces"))
        assertEquals("ALTER TABLE `pieces` ADD COLUMN " + SchemaV5.GENRE_COLUMN, SchemaV5.ADD_GENRE)
        for (table in v4.tables.keys) {
            if (table != "pieces") assertEquals(v4.table(table), v5.table(table))
            assertEquals(v4.indices(table), v5.indices(table))
        }
        assertEquals(v4.views, v5.views)
        assertEquals(v4.tables.keys, v5.tables.keys)
        val verbs = SchemaV5.STATEMENTS.map { it.trimStart().substringBefore(' ').uppercase() }.toSet()
        assertEquals("nothing is dropped, made or inserted", setOf("ALTER", "UPDATE"), verbs)
    }

    @Test
    fun `a 1_13 database migrates, keeps every row and sorts each piece as the rule does`() {
        connect("v4.db").use { db ->
            v4.ddl.forEach { db.exec(it) }
            fillAsVersion113(db)
            val before = rows(db)
            migrate(db)
            assertEquals(before, rows(db))
            assertEquals(
                cases.map { (key, collection, genre) -> listOf(key, collection ?: "null", "$genre") },
                query(db, "SELECT composerKey, collection, genre FROM pieces ORDER BY id"),
            )
            // The pure rule agrees, the library's artists being what the first pass taught (the second pass's rule 2).
            val taught = cases.filter { (key, collection, _) -> key.isNotBlank() && Genres.strong(key, collection) == Genres.CLASSICAL }
                .associate { it.first to Genres.CLASSICAL }
            for ((key, collection, genre) in cases) assertEquals("$key, $collection", genre, Genres.of(key, collection, taught))
        }
    }

    @Test
    fun `the migrated database is the one Room expects at v5`() {
        val migrated = connect("migrated.db").use { db ->
            v4.ddl.forEach { db.exec(it) }
            fillAsVersion113(db)
            migrate(db)
            shape(db)
        }
        val fresh = connect("fresh.db").use { db ->
            v5.ddl.forEach { db.exec(it) }
            shape(db)
        }
        assertEquals(fresh, migrated)
    }

    private fun migrate(db: Connection) = SchemaV5.STATEMENTS.forEach { db.exec(it) }

    /** What apps 1.12 to 1.13.1 hold: [cases]' pieces, two playlists, a portrait and a cover, a schedule and a Studio turn. */
    private fun fillAsVersion113(db: Connection) {
        val values = cases.mapIndexed { i, (key, collection, _) ->
            val id = i + 1
            val name = key.replace("'", "''")
            "($id, 'Piece $id', '$name', '$name', '$name', ${collection?.let { "'$it'" } ?: "NULL"}, 's$id', 's$id.mid', 'folder/$id.mid', 1000, 60000, 300, ${100 + id}, 0, 0, NULL, 'piece $id $name', 'piece $id')"
        }
        db.exec(
            "INSERT INTO pieces (id, title, composer, composerKey, composerShort, collection, sha256, fileName, sourceName, sizeBytes, " +
                "durationMs, noteCount, addedAt, favorite, playCount, lastPlayedAt, searchText, titleKey) VALUES " + values.joinToString(", "),
        )
        db.exec("INSERT INTO collections (id, name, createdAt, imported, builtIn, builtInKey) VALUES (1, 'piano-midi.de', 10, 1, 0, NULL), (2, 'Recordings', 12, 0, 1, 'recordings')")
        db.exec("INSERT INTO collection_pieces (collectionId, pieceId, addedAt, position) VALUES (1, 9, 10, 0), (1, 13, 10, 1), (2, 7, 12, 0)")
        db.exec(
            "INSERT INTO artwork (`key`, imagePath, description, sourceUrl, sourceTitle, fetchedAt, status) VALUES " +
                "('composer:debussy', 'art/debussy.jpg', 'A French composer.', 'https://en.wikipedia.org/wiki/Claude_Debussy', 'Claude Debussy', 16, 'OK'), " +
                "('piece:6', 'art/piece-6.png', 'Made in Studio · in the manner of Clair de lune (Claude Debussy)', NULL, NULL, 20, 'OK'), " +
                "('piece:7', NULL, 'Recorded live · Sep 30, 2026', NULL, NULL, 12, 'OK')",
        )
        db.exec("INSERT INTO schedules (days, startMinute, kind, target, createdAt) VALUES (31, 750, 'CHANNEL', 'calm', 1)")
        db.exec("INSERT INTO studio_generations (createdAt, kind, understood, pieceId, title, outcome) VALUES (20, 'compose', 'Calm', 6, 'Piece 6', 'kept')")
    }

    /** Every row of v4's six tables as text, in a fixed order; the pieces' v4 columns only. */
    private fun rows(db: Connection): Map<String, List<List<String>>> = mapOf(
        "pieces" to query(
            db,
            "SELECT id, title, composer, composerKey, composerShort, collection, sha256, fileName, sourceName, sizeBytes, durationMs, " +
                "noteCount, addedAt, favorite, playCount, lastPlayedAt, searchText, titleKey FROM pieces ORDER BY id",
        ),
        "collections" to query(db, "SELECT * FROM collections ORDER BY id"),
        "collection_pieces" to query(db, "SELECT * FROM collection_pieces ORDER BY collectionId, pieceId"),
        "artwork" to query(db, "SELECT * FROM artwork ORDER BY `key`"),
        "schedules" to query(db, "SELECT * FROM schedules ORDER BY id"),
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
