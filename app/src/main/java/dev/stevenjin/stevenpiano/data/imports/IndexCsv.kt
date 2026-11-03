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
 *
 * v1.10 — M27: an optional last column `sha256` (Steven's library pack writes it: each file's
 * SHA-256), read into [Row.sha256] when it is 64 hex digits (kept lower-case) and null otherwise,
 * so the app knows a pack's pieces before it reads them. An index without it reads as before.
 *
 * v1.41: an optional column `replaces`, found by its header name only: the SHA-256s of earlier versions of the
 * row's file, apart by spaces, read into [Row.replaces] (64 hex digits each, kept lower-case; anything else is left
 * out), so a new version takes its old one's place in the library ([Importer]). An index without it reads as before.
 *
 * v1.43: optional columns `cover` and `cover_album`, found by their header names only: a JPEG or PNG beside the music
 * (its path relative to the index, as `path` is) to be the piece's album cover, and its credit ("album · artist", as
 * a found cover's), read into [Row.cover] and [Row.coverAlbum]. A cover that is not a .jpg, .jpeg or .png, or whose
 * path climbs out with "..", is left out. The importer keeps it as an album cover the lookup found ([Importer]). An
 * index without them reads as before.
 */
class IndexCsv private constructor(private val rows: Map<String, Row>) {
    data class Row(
        val collection: String,
        val composer: String,
        val title: String,
        val path: String,
        val sha256: String? = null,
        val replaces: Set<String> = emptySet(),
        val cover: String? = null,
        val coverAlbum: String = "",
    )

    val size: Int get() = rows.size

    fun lookup(relativePath: String): Row? = rows[normalizePath(relativePath)]

    /** The SHA-256s the rows give (v1.10 — M27): a library pack's pieces. */
    fun sha256s(): Set<String> = rows.values.mapNotNullTo(HashSet()) { it.sha256 }

    companion object {
        const val FILE_NAME = "INDEX.csv"

        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val SPACES = Regex("\\s+")

        fun parse(text: String): IndexCsv {
            val table = CsvReader.parse(text, maxField = TextLimits.CSV_FIELD)
            val header = table.firstOrNull().orEmpty().map { it.trim().lowercase(Locale.ROOT) }
            val hasHeader = "path" in header
            fun column(name: String, position: Int) = if (hasHeader) header.indexOf(name) else position
            val collection = column("collection", 0)
            val composer = column("composer", 1)
            val title = column("title", 2)
            val path = column("path", 4)
            val sha256 = column("sha256", 5)
            val replaces = if (hasHeader) header.indexOf("replaces") else -1
            val cover = if (hasHeader) header.indexOf("cover") else -1
            val coverAlbum = if (hasHeader) header.indexOf("cover_album") else -1
            val rows = (if (hasHeader) table.drop(1) else table).mapNotNull { cells ->
                val filePath = cells.getOrNull(path)?.trim().orEmpty()
                if (filePath.isEmpty()) return@mapNotNull null
                normalizePath(filePath) to Row(
                    collection = cells.getOrNull(collection)?.trim().orEmpty(),
                    composer = cells.getOrNull(composer)?.trim().orEmpty(),
                    title = cells.getOrNull(title)?.trim().orEmpty(),
                    path = filePath,
                    sha256 = cells.getOrNull(sha256)?.trim()?.takeIf { SHA256.matches(it) }?.lowercase(Locale.ROOT),
                    replaces = shas(cells.getOrNull(replaces)),
                    cover = picture(cells.getOrNull(cover)),
                    coverAlbum = cells.getOrNull(coverAlbum)?.trim().orEmpty(),
                )
            }
            return IndexCsv(rows.toMap())
        }

        private val PICTURE = Regex("\\.(jpe?g|png)$", RegexOption.IGNORE_CASE)

        /** A `cover` field's path (v1.43): a .jpg, .jpeg or .png that stays inside the index's folder, else null. */
        private fun picture(field: String?): String? =
            field?.trim()?.replace('\\', '/')?.takeIf { PICTURE.containsMatchIn(it) && ".." !in it.split('/') }

        /** A `replaces` field's SHA-256s (v1.41), in its order; a value that is not 64 hex digits is left out. */
        private fun shas(field: String?): Set<String> =
            field?.split(SPACES)?.filter { SHA256.matches(it) }?.mapTo(LinkedHashSet()) { it.lowercase(Locale.ROOT) }.orEmpty()

        /** Forward slashes, no leading "./" or "/", composed Unicode, case-insensitive. */
        fun normalizePath(path: String): String =
            Normalizer.normalize(path.replace('\\', '/').removePrefix("./").trimStart('/'), Normalizer.Form.NFC)
                .lowercase(Locale.ROOT)
    }
}
