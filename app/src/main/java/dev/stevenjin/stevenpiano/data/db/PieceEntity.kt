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
    /** The INDEX.csv collection it came with, if any. */
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

/** This piece with a new title and composer, and every key derived from them. */
fun PieceEntity.named(title: String, composer: ComposerNames.Name): PieceEntity = copy(
    title = title,
    composer = composer.display,
    composerKey = composer.key,
    composerShort = composer.short,
    searchText = TextKeys.searchText(title, composer.display),
    titleKey = TextKeys.fold(title),
)
