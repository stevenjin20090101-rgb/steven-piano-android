// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A playlist: one the person made, or one an imported INDEX.csv named ([imported]). The table
 * keeps its v1 name, `collections`, so v1.1 libraries carry over untouched.
 */
@Entity(tableName = "collections", indices = [Index(value = ["name"], unique = true)])
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val imported: Boolean,
)

/**
 * A piece in a playlist, at [position] (0 first; the order the person set, then the title for
 * ties). Deleting either side removes the link. Table and column names are v1's; [position] is
 * new in schema v2 and was filled in by `MIGRATION_1_2`.
 */
@Entity(
    tableName = "collection_pieces",
    primaryKeys = ["collectionId", "pieceId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PieceEntity::class,
            parentColumns = ["id"],
            childColumns = ["pieceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pieceId"), Index(value = ["collectionId", "position"])],
)
data class PlaylistPieceEntity(
    /** The playlist ([PlaylistEntity.id]); the column keeps its v1 name. */
    val collectionId: Long,
    val pieceId: Long,
    val addedAt: Long,
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
)

/** A playlist with its size and total length, for the Playlists tiles and a playlist's page. */
data class PlaylistSummary(val id: Long, val name: String, val imported: Boolean, val pieceCount: Int, val durationMs: Long)

/** Where one piece sits in a playlist. */
data class PiecePosition(val pieceId: Long, val position: Int)
