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
 * Schema v8 (M82) against Room's own export, then on a real SQLite (the JVM's, through sqlite-jdbc): a database built
 * exactly as apps 1.37 to 1.46 built it migrates with every row of every table kept and each piece's new measures null;
 * the shape is the one a database made new at v8 has (what Room checks when it opens one). Then the sorts' statements on
 * it: the measures written together, the pieces the world-popularity lookup asks for (never one made here, never one
 * already asked, again once renamed), and an answer kept only while the piece is named as it was looked up. No
 * destructive fallback exists, so this is what stands between the upgrade and the school tablet's library.
 */
class SchemaV8Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private val v7 = ExportedSchema.read(7)
    private val v8 = ExportedSchema.read(8)

    @Test
    fun `the migration's columns are 8_json's, and nothing else changes`() {
        assertEquals(8, v8.version)
        assertEquals(v7.table("pieces").removeSuffix(")") + ", " + SchemaV8.COLUMNS.joinToString(", ") + ")", v8.table("pieces"))
        assertEquals(SchemaV8.COLUMNS.map { "ALTER TABLE `pieces` ADD COLUMN $it" }, SchemaV8.STATEMENTS)
        for (table in v7.tables.keys) {
            if (table != "pieces") assertEquals(v7.table(table), v8.table(table))
            assertEquals(v7.indices(table), v8.indices(table))
        }
        assertEquals(v7.tables.keys, v8.tables.keys)
        assertEquals(v7.views, v8.views)
        assertEquals("nothing is dropped or rewritten", setOf("ALTER"), SchemaV8.STATEMENTS.map { it.substringBefore(' ') }.toSet())
    }

    @Test
    fun `a 1_46 database migrates, every row kept, every piece's new measures null`() {
        connect("v7.db").use { db ->
            v7.ddl.forEach { db.exec(it) }
            fillAsVersion146(db)
            val before = everything(db, v7)
            migrate(db)
            val after = everything(db, v7)
            assertEquals("every other table as it was", before - "pieces", after - "pieces")
            assertEquals("every piece kept, the nine new columns null", before.getValue("pieces").map { it + List(9) { "null" } }, after.getValue("pieces"))
        }
    }

    @Test
    fun `the migrated database is the one Room expects at v8`() {
        val migrated = connect("migrated.db").use { db ->
            v7.ddl.forEach { db.exec(it) }
            fillAsVersion146(db)
            migrate(db)
            shape(db)
        }
        val fresh = connect("fresh.db").use { db ->
            v8.ddl.forEach { db.exec(it) }
            shape(db)
        }
        assertEquals(fresh, migrated)
    }

    @Test
    fun `the sorts' statements - the measures together, the lookups asked once, again after a rename`() {
        connect("sorts.db").use { db ->
            v7.ddl.forEach { db.exec(it) }
            fillAsVersion146(db)
            migrate(db)
            db.exec(bind(PieceSql.SET_STATS, "busiest" to "18.5", "mostKeys" to "10", "moments" to "3", "leaps" to "42.0", "range" to "61", "id" to "1"))
            assertEquals(
                listOf(listOf("18.5", "10", "3", "42.0", "61")),
                query(db, "SELECT busiestPerSecond, mostKeys, mostKeysMoments, leapsPerSecond, keyRange FROM pieces WHERE id = 1"),
            )
            // Every Classical or Modern piece, never the recording (genre 0), by id after :after.
            assertEquals(listOf("1", "2", "3"), candidates(db, after = 0).map { it[0] })
            assertEquals(listOf("2", "perfect|ed sheeran"), candidates(db, after = 1).first().let { listOf(it[0], it[4]) })
            // Piece 2 is looked up: kept, and not asked again; piece 3's answer, looked up as it was named, is not kept
            // once it was renamed meanwhile, so it is asked again under its new name.
            assertEquals(1, db.update(rank(id = 2, rank = "812345", disney = "0", key = "perfect|ed sheeran")))
            db.exec("UPDATE pieces SET title = 'Dynamite (live)', titleKey = 'dynamite (live)' WHERE id = 3")
            assertEquals(0, db.update(rank(id = 3, rank = "900000", disney = "0", key = "dynamite|bts")))
            assertEquals(listOf("1", "3"), candidates(db, after = 0).map { it[0] })
            assertEquals(listOf("dynamite (live)|bts"), candidates(db, after = 2).map { it[4] })
            assertEquals(1, db.update(rank(id = 3, rank = "NULL", disney = "NULL", key = "dynamite (live)|bts")))   // none matched
            assertEquals(listOf("1"), candidates(db, after = 0).map { it[0] })
            assertEquals(
                listOf(listOf("2", "812345", "0"), listOf("3", "null", "null")),
                query(db, "SELECT id, worldRank, disneyLabel FROM pieces WHERE worldRankAt IS NOT NULL ORDER BY id"),
            )
        }
    }

    private fun migrate(db: Connection) = SchemaV8.STATEMENTS.forEach { db.exec(it) }

    private fun candidates(db: Connection, after: Long) = query(db, bind(PieceSql.RANK_CANDIDATES, "after" to "$after", "limit" to "50"))

    private fun rank(id: Long, rank: String, disney: String, key: String) =
        bind(PieceSql.SET_RANK, "rank" to rank, "disney" to disney, "at" to "1000", "key" to "'${key.replace("'", "''")}'", "id" to "$id")

    /** [sql] with each `:name` replaced by its value, as Room binds them (longest names first, so `:at` is not `:after`'s start). */
    private fun bind(sql: String, vararg values: Pair<String, String>): String =
        values.sortedByDescending { it.first.length }.fold(sql) { text, (name, value) -> text.replace(":$name", value) }

    /**
     * What apps 1.37 to 1.46 hold: a Classical piece played 4 times, two Modern ones with their Apple genres, a recording;
     * two playlists, a cover, a quiet time and a Studio turn.
     */
    private fun fillAsVersion146(db: Connection) {
        db.exec(
            "INSERT INTO pieces (id, title, composer, composerKey, composerShort, collection, sha256, fileName, sourceName, sizeBytes, " +
                "durationMs, noteCount, addedAt, favorite, playCount, lastPlayedAt, searchText, titleKey, genre, appleGenre) VALUES " +
                "(1, 'Clair de lune', 'Claude Debussy', 'debussy', 'Debussy', 'piano-midi.de', 's1', 's1.mid', 'piano-midi.de/deb_clai.mid', " +
                "1000, 300000, 900, 10, 1, 4, 99, 'clair de lune claude debussy', 'clair de lune', 1, NULL), " +
                "(2, 'Perfect', 'Ed Sheeran', 'ed sheeran', 'Ed Sheeran', NULL, 's2', 's2.mid', 'modern/Perfect.mid', " +
                "800, 240000, 700, 11, 0, 0, NULL, 'perfect ed sheeran', 'perfect', 2, 'Pop'), " +
                "(3, 'Dynamite', 'BTS', 'bts', 'BTS', 'K-pop', 's3', 's3.mid', 'modern/Dynamite.mid', " +
                "800, 200000, 600, 12, 1, 2, 50, 'dynamite bts', 'dynamite', 2, 'K-Pop'), " +
                "(4, 'Recording 1', 'Recorded live', 'recorded live', 'Recorded live', NULL, 's4', 's4.mid', 'recording-1.mid', " +
                "300, 60000, 120, 13, 0, 1, 70, 'recording 1 recorded live', 'recording 1', 0, NULL)",
        )
        db.exec("INSERT INTO collections (id, name, createdAt, imported, builtIn, builtInKey) VALUES (1, 'piano-midi.de', 10, 1, 0, NULL), (2, 'Recordings', 13, 0, 1, 'recordings')")
        db.exec("INSERT INTO collection_pieces (collectionId, pieceId, addedAt, position) VALUES (1, 1, 10, 0), (2, 4, 13, 0)")
        db.exec("INSERT INTO artwork (`key`, imagePath, description, sourceUrl, sourceTitle, fetchedAt, status) VALUES ('cover:2', 'art/cover-2.jpg', NULL, 'https://music.apple.com/x', 'Divide · Ed Sheeran', 19, 'OK')")
        db.exec("INSERT INTO schedules (days, startMinute, kind, target, endMinute, createdAt) VALUES (31, 720, 'QUIET', '', 780, 1)")
        db.exec("INSERT INTO studio_generations (createdAt, kind, understood, pieceId, title, outcome) VALUES (20, 'compose', 'Calm', NULL, 'Calm', 'kept')")
    }

    /** Every row of [schema]'s tables as text, in a fixed order. */
    private fun everything(db: Connection, schema: ExportedSchema): Map<String, List<List<String>>> =
        schema.tables.keys.associateWith { table -> query(db, "SELECT * FROM `$table` ORDER BY 1, 2") }

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
