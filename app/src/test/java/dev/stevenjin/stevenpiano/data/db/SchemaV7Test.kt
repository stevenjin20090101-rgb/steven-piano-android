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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager

/**
 * Schema v7 (app 1.37 — M73) against Room's own export, then on a real SQLite (the JVM's, through sqlite-jdbc): a database
 * built exactly as apps 1.24 to 1.36 built it, holding Steven's imported Pop and MIDI among other playlists, migrates with
 * every piece kept (each with no Apple genre yet), Pop and MIDI gone with their links and their photo's row, every other
 * playlist as it was; the merge run again changes nothing; and the shape is the one a database made new at v7 has (what
 * Room checks when it opens one). No destructive fallback exists, so this is what stands between the upgrade and the
 * school tablet's library.
 */
class SchemaV7Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private val v6 = ExportedSchema.read(6)
    private val v7 = ExportedSchema.read(7)

    @Test
    fun `the migration's column is 7_json's, and nothing else changes`() {
        assertEquals(7, v7.version)
        assertEquals(v6.table("pieces").removeSuffix(")") + ", " + SchemaV7.APPLE_GENRE_COLUMN + ")", v7.table("pieces"))
        assertEquals("ALTER TABLE `pieces` ADD COLUMN " + SchemaV7.APPLE_GENRE_COLUMN, SchemaV7.ADD_APPLE_GENRE)
        for (table in v6.tables.keys) {
            if (table != "pieces") assertEquals(v6.table(table), v7.table(table))
            assertEquals(v6.indices(table), v7.indices(table))
        }
        assertEquals(v6.tables.keys, v7.tables.keys)
        assertEquals(v6.views, v7.views)
    }

    @Test
    fun `a 1_36 database migrates, every piece kept, Pop and MIDI gone, every other playlist as it was`() {
        connect("v6.db").use { db ->
            v6.ddl.forEach { db.exec(it) }
            fillAsVersion136(db)
            val pieces = query(db, "SELECT * FROM pieces ORDER BY id")
            val others = query(db, "SELECT * FROM collections WHERE id NOT IN (1, 2, 7) ORDER BY id")
            val otherLinks = query(db, "SELECT * FROM collection_pieces WHERE collectionId NOT IN (1, 2, 7) ORDER BY collectionId, pieceId")
            val otherArt = query(db, "SELECT * FROM artwork WHERE `key` NOT IN ('playlist:1') ORDER BY `key`")

            assertEquals("the imported Pop, MIDI and \"m.i.d.i\" go", listOf(1L, 2L, 7L), migrate(db))

            assertEquals("every piece kept, each with no Apple genre yet", pieces.map { it + "null" }, query(db, "SELECT * FROM pieces ORDER BY id"))
            assertEquals("every other playlist as it was", others, query(db, "SELECT * FROM collections ORDER BY id"))
            assertEquals(otherLinks, query(db, "SELECT * FROM collection_pieces ORDER BY collectionId, pieceId"))
            assertEquals("Pop's photo row goes, nothing else", otherArt, query(db, "SELECT * FROM artwork ORDER BY `key`"))

            // A second run (the merge alone: the column is there) changes nothing, and Apple's own Pop, made later, stays.
            db.exec("INSERT INTO collections (id, name, createdAt, imported, builtIn, builtInKey) VALUES (20, 'Pop', 900, 0, 1, 'genre:pop')")
            db.exec("INSERT INTO collection_pieces (collectionId, pieceId, addedAt, position) VALUES (20, 2, 900, 0)")
            val before = everything(db)
            assertEquals(emptyList<Long>(), merge(db))
            assertEquals(before, everything(db))
        }
    }

    @Test
    fun `only an imported playlist called Pop or MIDI, as names compare, is merged`() {
        val rows = listOf(1L to "Pop", 2L to "MIDI", 3L to "Popular songs", 4L to " pop ", 5L to "K-pop", 6L to "M.I.D.I", 7L to "MIDI 2", 8L to "Pops")
        assertEquals(listOf(1L, 2L, 4L, 6L), SchemaV7.merged(rows))
    }

    @Test
    fun `the migrated database is the one Room expects at v7`() {
        val migrated = connect("migrated.db").use { db ->
            v6.ddl.forEach { db.exec(it) }
            fillAsVersion136(db)
            migrate(db)
            shape(db)
        }
        val fresh = connect("fresh.db").use { db ->
            v7.ddl.forEach { db.exec(it) }
            shape(db)
        }
        assertEquals(fresh, migrated)
    }

    /** What MIGRATION_6_7 does, through JDBC: the column, then the merge. Returns the playlists merged. */
    private fun migrate(db: Connection): List<Long> {
        db.exec(SchemaV7.ADD_APPLE_GENRE)
        return merge(db)
    }

    private fun merge(db: Connection): List<Long> {
        val rows = query(db, SchemaV7.IMPORTED_PLAYLISTS).map { it[0].toLong() to it[1] }
        return SchemaV7.merged(rows).onEach { id -> SchemaV7.deletes(id).forEach { db.exec(it) } }
    }

    /**
     * What apps 1.24 to 1.36 hold: four pieces; Steven's imported Pop (with a photo) and MIDI, a third import spelt
     * "m.i.d.i", his imported K-pop and House & Dance, a MIDI playlist of his own making (not an import: it stays), and
     * the built-in Popular; a piece in several of them.
     */
    private fun fillAsVersion136(db: Connection) {
        db.exec(
            "INSERT INTO pieces (id, title, composer, composerKey, composerShort, collection, sha256, fileName, sourceName, sizeBytes, " +
                "durationMs, noteCount, addedAt, favorite, playCount, lastPlayedAt, searchText, titleKey, genre) VALUES " +
                "(1, 'Clair de lune', 'Claude Debussy', 'debussy', 'Debussy', NULL, 's1', 's1.mid', 'Clair de lune.mid', 1000, 300000, 900, 10, 1, 4, 99, 'clair de lune debussy', 'clair de lune', 1), " +
                "(2, 'Perfect', 'Ed Sheeran', 'ed sheeran', 'Ed Sheeran', NULL, 's2', 's2.mid', 'Pop/Ed Sheeran/Perfect.mid', 800, 240000, 700, 11, 0, 0, NULL, 'perfect ed sheeran', 'perfect', 2), " +
                "(3, 'Dynamite', 'BTS', 'bts', 'BTS', NULL, 's3', 's3.mid', 'K-pop/BTS/Dynamite.mid', 800, 200000, 600, 12, 1, 2, 50, 'dynamite bts', 'dynamite', 2), " +
                "(4, 'Titanium', 'David Guetta', 'david guetta', 'David Guetta', NULL, 's4', 's4.mid', 'MIDI/Titanium.mid', 800, 245000, 650, 13, 0, 0, NULL, 'titanium david guetta', 'titanium', 2)",
        )
        db.exec(
            "INSERT INTO collections (id, name, createdAt, imported, builtIn, builtInKey) VALUES " +
                "(1, 'Pop', 10, 1, 0, NULL), (2, 'MIDI', 11, 1, 0, NULL), (3, 'K-pop', 12, 1, 0, NULL), (4, 'House & Dance', 13, 1, 0, NULL), " +
                "(5, 'Midi', 14, 0, 0, NULL), (6, 'Popular', 15, 0, 1, 'popular'), (7, 'm.i.d.i', 16, 1, 0, NULL)",
        )
        db.exec(
            "INSERT INTO collection_pieces (collectionId, pieceId, addedAt, position) VALUES " +
                "(1, 2, 10, 0), (1, 3, 10, 1), (2, 4, 11, 0), (2, 2, 11, 1), (3, 3, 12, 0), (4, 4, 13, 0), (5, 2, 14, 0), (6, 1, 15, 0), (7, 4, 16, 0)",
        )
        db.exec(
            "INSERT INTO artwork (`key`, imagePath, description, sourceUrl, sourceTitle, fetchedAt, status) VALUES " +
                "('composer:debussy', 'art/debussy.jpg', 'A French composer.', 'https://en.wikipedia.org/wiki/Claude_Debussy', 'Claude Debussy', 16, 'OK'), " +
                "('playlist:1', 'art/playlist-1.jpg', NULL, NULL, NULL, 17, 'OK'), " +
                "('playlist:3', 'art/playlist-3.jpg', NULL, NULL, NULL, 18, 'OK'), " +
                "('cover:2', NULL, NULL, 'https://music.apple.com/x', 'Divide · Ed Sheeran', 19, 'OK')",
        )
    }

    /** Every row of v7's tables as text. */
    private fun everything(db: Connection): Map<String, List<List<String>>> =
        v7.tables.keys.associateWith { table -> query(db, "SELECT * FROM `$table` ORDER BY 1, 2") }

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
