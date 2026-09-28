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
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Schema v3 against Room's own export: the migration's statements are the ones Room generated in
 * `app/schemas/…/3.json`, v2's export is kept beside it, and everything but the playlists table
 * and the new schedules table is as v2 left it. Then the migration runs on a real SQLite (the
 * JVM's, through sqlite-jdbc): a database built exactly as apps 1.2 to 1.4 built it, holding
 * pieces, playlists, their links and a picture, keeps every row, reads its playlists as the
 * person's own, and ends with the tables, columns, defaults, indices and foreign keys a database
 * made new at v3 has, which is what Room checks when it opens one. The whole-app check (install
 * 1.4, import, install 1.5 over it) runs on the emulator.
 */
class SchemaV3Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private val v2 = ExportedSchema.read(2)
    private val v3 = ExportedSchema.read(3)

    @Test
    fun `the migration adds the two built-in columns exactly as 3_json declares them`() {
        val playlists = v3.table("collections")
        assertTrue(playlists, playlists.endsWith(", ${SchemaV3.BUILT_IN_COLUMN}, ${SchemaV3.BUILT_IN_KEY_COLUMN})"))
        assertEquals(v2.table("collections"), playlists.replace(", ${SchemaV3.BUILT_IN_COLUMN}, ${SchemaV3.BUILT_IN_KEY_COLUMN}", ""))
        assertEquals("ALTER TABLE `collections` ADD COLUMN ${SchemaV3.BUILT_IN_COLUMN}", SchemaV3.ADD_BUILT_IN)
        assertEquals("ALTER TABLE `collections` ADD COLUMN ${SchemaV3.BUILT_IN_KEY_COLUMN}", SchemaV3.ADD_BUILT_IN_KEY)
    }

    @Test
    fun `the migration creates the unique key index and the schedules table with 3_json's statements`() {
        assertEquals(v3.index("collections", "index_collections_builtInKey"), SchemaV3.CREATE_BUILT_IN_KEY_INDEX)
        assertTrue(SchemaV3.CREATE_BUILT_IN_KEY_INDEX.startsWith("CREATE UNIQUE INDEX"))
        assertEquals(v3.table("schedules"), SchemaV3.CREATE_SCHEDULES)
        assertEquals(
            listOf(SchemaV3.ADD_BUILT_IN, SchemaV3.ADD_BUILT_IN_KEY, SchemaV3.CREATE_BUILT_IN_KEY_INDEX, SchemaV3.CREATE_SCHEDULES),
            SchemaV3.DDL,
        )
        assertTrue("nothing is dropped or rewritten", SchemaV3.DDL.none { it.contains("DROP", ignoreCase = true) || it.contains("DELETE", ignoreCase = true) })
    }

    @Test
    fun `everything else is as v2 left it`() {
        assertEquals(2, v2.version)
        assertEquals(3, v3.version)
        for (table in listOf("pieces", "collection_pieces", "artwork")) {
            assertEquals(v2.table(table), v3.table(table))
            assertEquals(v2.indices(table), v3.indices(table))
        }
        assertEquals(v2.index("collections", "index_collections_name"), v3.index("collections", "index_collections_name"))
        assertEquals(v2.views, v3.views)
        assertEquals(v2.tables.keys + "schedules", v3.tables.keys)
    }

    @Test
    fun `a 1_4 database migrates on a real SQLite and keeps every row`() {
        connect("v2.db").use { db ->
            v2.ddl.forEach { db.exec(it) }
            fillAsVersion14(db)
            val before = rows(db)
            SchemaV3.DDL.forEach { db.exec(it) }
            assertEquals(before, rows(db).mapValues { (table, list) -> if (table == "collections") list.map { it.dropLast(2) } else list })
            // Every playlist that was there reads as the person's own.
            assertEquals(listOf(listOf("0", "null"), listOf("0", "null")), query(db, "SELECT builtIn, builtInKey FROM collections ORDER BY id"))
            // The view still reads the pieces.
            assertEquals(listOf(listOf("chopin", "2"), listOf("debussy", "1")), query(db, "SELECT composerKey, pieceCount FROM composer_groups ORDER BY composerKey"))
        }
    }

    @Test
    fun `the migrated database is the one Room expects at v3`() {
        val migrated = connect("migrated.db").use { db ->
            v2.ddl.forEach { db.exec(it) }
            fillAsVersion14(db)
            SchemaV3.DDL.forEach { db.exec(it) }
            shape(db)
        }
        val fresh = connect("fresh.db").use { db ->
            v3.ddl.forEach { db.exec(it) }
            shape(db)
        }
        assertEquals(fresh, migrated)
    }

    @Test
    fun `after the migration a built-in key names one playlist, and playlists without one are many`() {
        connect("keys.db").use { db ->
            v2.ddl.forEach { db.exec(it) }
            fillAsVersion14(db)
            SchemaV3.DDL.forEach { db.exec(it) }
            // The person's own "Popular" stays; the built-in list takes the name beside it.
            db.exec("INSERT INTO collections (name, createdAt, imported, builtIn, builtInKey) VALUES ('Popular · built in', 3, 0, 1, 'popular')")
            db.exec("INSERT INTO collections (name, createdAt, imported) VALUES ('Road trip', 4, 0)")
            try {
                db.exec("INSERT INTO collections (name, createdAt, imported, builtIn, builtInKey) VALUES ('Popular again', 5, 0, 1, 'popular')")
                fail("A second playlist took the built-in key 'popular'")
            } catch (e: SQLException) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("collections.builtInKey"))
            }
            assertEquals(listOf(listOf("3")), query(db, "SELECT COUNT(*) FROM collections WHERE builtInKey IS NULL"))
        }
    }

    @Test
    fun `a schedule is enabled unless it says otherwise`() {
        connect("schedules.db").use { db ->
            v2.ddl.forEach { db.exec(it) }
            SchemaV3.DDL.forEach { db.exec(it) }
            db.exec("INSERT INTO schedules (days, startMinute, kind, target, createdAt) VALUES (31, 750, 'CHANNEL', 'calm', 1)")
            assertEquals(
                listOf(listOf("1", "31", "750", "CHANNEL", "calm", "null", "null", "1", "1")),
                query(db, "SELECT id, days, startMinute, kind, target, endMinute, volumePct, enabled, createdAt FROM schedules"),
            )
        }
    }

    /** What apps 1.2 to 1.4 would hold after an import and a playlist of the person's own. */
    private fun fillAsVersion14(db: Connection) {
        db.exec(
            "INSERT INTO pieces (id, title, composer, composerKey, composerShort, collection, sha256, fileName, sourceName, sizeBytes, " +
                "durationMs, noteCount, addedAt, favorite, playCount, lastPlayedAt, searchText, titleKey) VALUES " +
                "(1, 'Clair de lune', 'Claude Debussy', 'debussy', 'Debussy', 'piano-midi.de', 'a1', 'a1.mid', 'debussy/deb_clai.mid', 17112, 301000, 1720, 10, 1, 3, 20, 'clair de lune claude debussy', 'clair de lune'), " +
                "(2, 'Nocturne Op. 9 No. 2', 'Frédéric Chopin', 'chopin', 'Chopin', NULL, 'b2', 'b2.mid', 'nocturne.mid', 9000, 262000, 1300, 11, 0, 0, NULL, 'nocturne op. 9 no. 2 frederic chopin', 'nocturne op. 9 no. 2'), " +
                "(3, 'Ballade No. 1', 'Frédéric Chopin', 'chopin', 'Chopin', 'piano-midi.de', 'c3', 'c3.mid', 'chopin/chpn_op23.mid', 58000, 540000, 4100, 12, 0, 1, 30, 'ballade no. 1 frederic chopin', 'ballade no. 1')",
        )
        db.exec("INSERT INTO collections (id, name, createdAt, imported) VALUES (1, 'piano-midi.de', 10, 1), (2, 'Popular', 13, 0)")
        db.exec("INSERT INTO collection_pieces (collectionId, pieceId, addedAt, position) VALUES (1, 1, 10, 0), (1, 3, 12, 1), (2, 2, 14, 0), (2, 1, 15, 1)")
        db.exec("INSERT INTO artwork (`key`, imagePath, description, sourceUrl, sourceTitle, fetchedAt, status) VALUES ('composer:debussy', 'art/debussy.jpg', 'A French composer.', 'https://en.wikipedia.org/wiki/Claude_Debussy', 'Claude Debussy', 16, 'OK')")
    }

    /** Every row of the four v2 tables, as text, in a fixed order. */
    private fun rows(db: Connection): Map<String, List<List<String>>> = mapOf(
        "pieces" to query(db, "SELECT * FROM pieces ORDER BY id"),
        "collections" to query(db, "SELECT * FROM collections ORDER BY id"),
        "collection_pieces" to query(db, "SELECT * FROM collection_pieces ORDER BY collectionId, pieceId"),
        "artwork" to query(db, "SELECT * FROM artwork ORDER BY `key`"),
    )

    /**
     * What Room compares when it opens a database, per table: the columns (name, type, not null,
     * default, place in the primary key), the indices (name, unique, columns) and the foreign keys;
     * and the views by name. Sorted, so the order things were made in does not matter.
     */
    private fun shape(db: Connection): List<String> {
        val tables = query(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").map { it[0] }
        val out = ArrayList<String>()
        for (table in tables) {
            query(db, "PRAGMA table_info(`$table`)").forEach { (_, name, type, notNull, default, pk) -> out += "$table column $name $type notNull=$notNull default=$default pk=$pk" }
            for ((_, index, unique) in query(db, "PRAGMA index_list(`$table`)")) {
                if (index.startsWith("sqlite_autoindex")) continue
                val columns = query(db, "PRAGMA index_info(`$index`)").sortedBy { it[0].toInt() }.joinToString(",") { it[2] }
                out += "$table index $index unique=$unique ($columns)"
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

    private operator fun <T> List<T>.component6(): T = this[5]
}

/** One statement, no result. */
private fun Connection.exec(sql: String) {
    createStatement().use { it.execute(sql) }
}
