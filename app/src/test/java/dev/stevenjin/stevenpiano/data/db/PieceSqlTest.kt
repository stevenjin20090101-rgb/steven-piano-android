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
 * A new version of a piece (v1.41, [PieceSql]) on a real SQLite (the JVM's, through sqlite-jdbc), built from Room's export
 * of schema v7, which the release keeps: only the new file's columns are written (the person's favorite, plays, names,
 * genre and playlists stay); a SHA-256 another piece holds writes nothing (the unique index), and a piece that is gone
 * counts no row; of several earlier versions, the first added is the one replaced.
 */
class PieceSqlTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val v7 = ExportedSchema.read(7)

    @Test
    fun `a new version's file is written over the piece's, and nothing else`() {
        connect("pieces.db").use { db ->
            v7.ddl.forEach { db.exec(it) }
            fill(db)
            val others = query(db, "SELECT * FROM pieces WHERE id <> 1 ORDER BY id")
            val links = query(db, "SELECT * FROM collection_pieces ORDER BY collectionId, pieceId")
            assertEquals(1, db.update(replace(id = 1, sha = "n1", size = 1200, durationMs = 310000, notes = 950)))
            assertEquals(
                listOf(
                    listOf(
                        "1", "Clair de lune (ours)", "Claude Debussy", "debussy", "Debussy", "piano-midi.de", "n1", "n1.mid", "new/Clair de lune.mid",
                        "1200", "310000", "950", "10", "1", "4", "99", "clair de lune ours debussy", "clair de lune ours", "1", "null",
                    ),
                ),
                query(db, "SELECT * FROM pieces WHERE id = 1"),
            )
            assertEquals("the other pieces as they were", others, query(db, "SELECT * FROM pieces WHERE id <> 1 ORDER BY id"))
            assertEquals("its playlists as they were", links, query(db, "SELECT * FROM collection_pieces ORDER BY collectionId, pieceId"))
        }
    }

    @Test
    fun `a SHA-256 another piece holds writes nothing, and a piece that is gone counts no row`() {
        connect("pieces.db").use { db ->
            v7.ddl.forEach { db.exec(it) }
            fill(db)
            val before = query(db, "SELECT * FROM pieces ORDER BY id")
            assertEquals("the unique index: no row written", 0, db.update(replace(id = 1, sha = "s2")))
            assertEquals(0, db.update(replace(id = 9, sha = "n9")))
            assertEquals(before, query(db, "SELECT * FROM pieces ORDER BY id"))
        }
    }

    @Test
    fun `of the pieces with the hashes asked for, the first added is the one replaced`() {
        connect("pieces.db").use { db ->
            v7.ddl.forEach { db.exec(it) }
            fill(db)
            assertEquals(listOf("2"), query(db, oldest("s3", "s2", "gone")).map { it[0] })
            assertEquals(listOf("3"), query(db, oldest("s3")).map { it[0] })
            assertEquals(emptyList<List<String>>(), query(db, oldest("gone")))
        }
    }

    /**
     * Three pieces as the app holds them: the first a favorite played 4 times, renamed and moved to Classical, in its
     * imported playlist and one of the person's; the others Modern, with their Apple genres.
     */
    private fun fill(db: Connection) {
        db.exec(
            "INSERT INTO pieces (id, title, composer, composerKey, composerShort, collection, sha256, fileName, sourceName, sizeBytes, " +
                "durationMs, noteCount, addedAt, favorite, playCount, lastPlayedAt, searchText, titleKey, genre, appleGenre) VALUES " +
                "(1, 'Clair de lune (ours)', 'Claude Debussy', 'debussy', 'Debussy', 'piano-midi.de', 's1', 's1.mid', 'piano-midi.de/debussy/deb_clai.mid', " +
                "1000, 300000, 900, 10, 1, 4, 99, 'clair de lune ours debussy', 'clair de lune ours', 1, NULL), " +
                "(2, 'Perfect', 'Ed Sheeran', 'ed sheeran', 'Ed Sheeran', 'Pop', 's2', 's2.mid', 'modern/Ed Sheeran/Perfect.mid', " +
                "800, 240000, 700, 11, 0, 0, NULL, 'perfect ed sheeran', 'perfect', 2, 'Pop'), " +
                "(3, 'Dynamite', 'BTS', 'bts', 'BTS', 'K-pop', 's3', 's3.mid', 'modern/BTS/Dynamite.mid', " +
                "800, 200000, 600, 12, 1, 2, 50, 'dynamite bts', 'dynamite', 2, 'K-Pop')",
        )
        db.exec("INSERT INTO collections (id, name, createdAt, imported, builtIn, builtInKey) VALUES (1, 'piano-midi.de', 10, 1, 0, NULL), (2, 'Calm evenings', 20, 0, 0, NULL)")
        db.exec("INSERT INTO collection_pieces (collectionId, pieceId, addedAt, position) VALUES (1, 1, 10, 0), (2, 3, 20, 0), (2, 1, 21, 1)")
    }

    /** [PieceSql.REPLACE_FILE] for piece [id], as the DAO binds it. */
    private fun replace(id: Long, sha: String, size: Long = 1000, durationMs: Long = 300000, notes: Int = 900) = PieceSql.REPLACE_FILE
        .replace(":sha256", "'$sha'").replace(":fileName", "'$sha.mid'").replace(":sourceName", "'new/Clair de lune.mid'")
        .replace(":sizeBytes", "$size").replace(":durationMs", "$durationMs").replace(":noteCount", "$notes").replace(":id", "$id")

    /** [PieceSql.OLDEST_BY_SHA] for [shas], as the DAO binds a list. */
    private fun oldest(vararg shas: String) = PieceSql.OLDEST_BY_SHA.replace(":shas", shas.joinToString(", ") { "'$it'" })

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
