// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

import dev.stevenjin.stevenpiano.data.TextLimits
import java.text.Normalizer
import java.util.Locale

/**
 * The library's INDEX.csv (`collection,composer,title,size_kb,path`), looked up by the path
 * of a file relative to the folder or zip the index sits in. Columns are found by their
 * header names; without a header they are taken in that order. Each field keeps at most
 * [TextLimits.CSV_FIELD] characters (the importer cuts titles and names further).
 */
class IndexCsv private constructor(private val rows: Map<String, Row>) {
    data class Row(val collection: String, val composer: String, val title: String, val path: String)

    val size: Int get() = rows.size

    fun lookup(relativePath: String): Row? = rows[normalizePath(relativePath)]

    companion object {
        const val FILE_NAME = "INDEX.csv"

        fun parse(text: String): IndexCsv {
            val table = CsvReader.parse(text, maxField = TextLimits.CSV_FIELD)
            val header = table.firstOrNull().orEmpty().map { it.trim().lowercase(Locale.ROOT) }
            val hasHeader = "path" in header
            fun column(name: String, position: Int) = if (hasHeader) header.indexOf(name) else position
            val collection = column("collection", 0)
            val composer = column("composer", 1)
            val title = column("title", 2)
            val path = column("path", 4)
            val rows = (if (hasHeader) table.drop(1) else table).mapNotNull { cells ->
                val filePath = cells.getOrNull(path)?.trim().orEmpty()
                if (filePath.isEmpty()) return@mapNotNull null
                normalizePath(filePath) to Row(
                    collection = cells.getOrNull(collection)?.trim().orEmpty(),
                    composer = cells.getOrNull(composer)?.trim().orEmpty(),
                    title = cells.getOrNull(title)?.trim().orEmpty(),
                    path = filePath,
                )
            }
            return IndexCsv(rows.toMap())
        }

        /** Forward slashes, no leading "./" or "/", composed Unicode, case-insensitive. */
        fun normalizePath(path: String): String =
            Normalizer.normalize(path.replace('\\', '/').removePrefix("./").trimStart('/'), Normalizer.Form.NFC)
                .lowercase(Locale.ROOT)
    }
}
