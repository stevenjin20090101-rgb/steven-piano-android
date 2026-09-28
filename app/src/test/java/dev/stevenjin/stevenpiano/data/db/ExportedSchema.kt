// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import java.io.File

/**
 * One of Room's exported schemas (`app/schemas/…/<version>.json`), read with [MiniJson]: the
 * createSql of every table, index and view with their names filled in, in the file's order.
 * Shared by SchemaV2Test and SchemaV3Test.
 */
class ExportedSchema(
    val version: Int,
    val tables: Map<String, String>,
    private val indexSql: Map<String, Map<String, String>>,
    val views: List<String>,
) {
    fun table(name: String): String = tables[name] ?: error("No table $name in schema v$version")

    fun indices(table: String): Map<String, String> = indexSql[table].orEmpty()

    fun index(table: String, name: String): String = indices(table)[name] ?: error("No index $name in schema v$version")

    /** Every statement that builds this schema from nothing: tables, their indices, then the views. */
    val ddl: List<String> get() = tables.keys.flatMap { listOf(table(it)) + indices(it).values } + views

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun read(version: Int): ExportedSchema {
            val root = MiniJson.parse(file(version).readText()) as Map<String, Any?>
            val database = root["database"] as Map<String, Any?>
            val tables = LinkedHashMap<String, String>()
            val indices = LinkedHashMap<String, Map<String, String>>()
            for (entity in database["entities"] as List<Map<String, Any?>>) {
                val name = entity["tableName"] as String
                tables[name] = (entity["createSql"] as String).replace("\${TABLE_NAME}", name)
                indices[name] = (entity["indices"] as List<Map<String, Any?>>? ?: emptyList())
                    .associate { (it["name"] as String) to (it["createSql"] as String).replace("\${TABLE_NAME}", name) }
            }
            val views = (database["views"] as List<Map<String, Any?>>).map { (it["createSql"] as String).replace("\${VIEW_NAME}", it["viewName"] as String) }
            return ExportedSchema((database["version"] as Double).toInt(), tables, indices, views)
        }

        /** `app/schemas/…/<version>.json`, found from wherever the tests run. */
        private fun file(version: Int): File {
            val relative = "schemas/dev.stevenjin.stevenpiano.data.db.PianoDatabase/$version.json"
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            error("Room's exported schema $relative is missing: it is part of the repo")
        }
    }
}
