// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.stevenjin.stevenpiano.data.TextKeys
import dev.stevenjin.stevenpiano.data.TextLimits
import dev.stevenjin.stevenpiano.data.imports.ComposerNames

/**
 * One piece in the library. Its file is `filesDir/pieces/<sha256>.mid`. [composerKey] groups
 * composers (the folded surname, "" when unknown), [composerShort] is what rows show,
 * [titleKey] sorts, [searchText] is what search matches.
 */
@Entity(
    tableName = "pieces",
    indices = [Index(value = ["sha256"], unique = true), Index("titleKey"), Index("composerKey")],
)
data class PieceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val composer: String,
    val composerKey: String,
    val composerShort: String,
    /** The playlist its INDEX.csv row named (the CSV's `collection` column), if any. */
    val collection: String?,
    val sha256: String,
    val fileName: String,
    /** Where it was imported from, relative to the folder or zip. */
    val sourceName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val noteCount: Int,
    val addedAt: Long,
    val favorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    val searchText: String,
    val titleKey: String,
)

/** What a queue or a list needs to name a piece without loading all of it. */
data class PieceSummary(val id: Long, val title: String, val composerShort: String, val durationMs: Long, val composerKey: String)

/** One of a playlist's first pieces: what its cover is made of (v1.14 — M37). */
data class PieceHead(val id: Long, val composerKey: String)

/** A piece's id by its bytes' SHA-256: how an import finds the pieces it brought, new or there already. */
data class PieceSha(val id: Long, val sha256: String)

/**
 * This piece with a new title and composer, and every key derived from them. Title and composer
 * are cut to [TextLimits] first and the keys are derived from what is kept, so no row can outgrow
 * Android's cursor window, whether the text came from a file, INDEX.csv or the Rename dialog.
 */
fun PieceEntity.named(title: String, composer: ComposerNames.Name): PieceEntity {
    val keptTitle = TextLimits.clip(title, TextLimits.TITLE)
    val keptComposer = TextLimits.clip(composer.display, TextLimits.COMPOSER)
    return copy(
        title = keptTitle,
        composer = keptComposer,
        composerKey = TextLimits.clip(composer.key, TextLimits.COMPOSER),
        composerShort = TextLimits.clip(composer.short, TextLimits.COMPOSER),
        searchText = TextLimits.clip(TextKeys.searchText(keptTitle, keptComposer), TextLimits.SEARCH_TEXT),
        titleKey = TextLimits.clip(TextKeys.fold(keptTitle), TextLimits.TITLE_KEY),
    )
}
