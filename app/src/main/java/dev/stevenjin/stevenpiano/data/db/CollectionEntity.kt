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
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A collection: one the person made, or one an imported INDEX.csv named ([imported]). */
@Entity(tableName = "collections", indices = [Index(value = ["name"], unique = true)])
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val imported: Boolean,
)

/** A piece in a collection. Deleting either side removes the link. */
@Entity(
    tableName = "collection_pieces",
    primaryKeys = ["collectionId", "pieceId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
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
    indices = [Index("pieceId")],
)
data class CollectionPieceEntity(val collectionId: Long, val pieceId: Long, val addedAt: Long)

/** A collection with its size, for the Collections chip. */
data class CollectionSummary(val id: Long, val name: String, val imported: Boolean, val pieceCount: Int)
