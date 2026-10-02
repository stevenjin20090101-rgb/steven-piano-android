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
 * One turn of Studio's conversation (schema v4, app 1.12 — M30): what was asked and how it went. [kind] is
 * "compose" or "transcribe"; [parentId] the turn it refined. What was asked: the idea as typed ([prompt], at
 * most 200 code points, null for the options sheet, the web panel and a transcription), the line that says
 * what was understood ([understood]), the words not used ([unused], one per line) and the asked spec
 * ([spec], `StyleSpec.encode`). What it came to: [mood], the key ([keyTonic], [keyMinor]), [bpm], [minutes];
 * the seed ([seedPieceId], and a snapshot of its [seedTitle] and [seedComposer] so the credits outlive it;
 * [seedSource] says how it was found); the models; the figures; the piece ([pieceId]), its [title], and the
 * [outcome]: pending, made (waiting for Keep or Discard), kept, discarded, failed, cancelled or interrupted.
 * [coverKind] is "drawn" (pictures may come later). Deleting a piece nulls [pieceId] and [seedPieceId];
 * the turn stays. Nothing typed is ever a title: [title] is built from what was understood.
 */
@Entity(
    tableName = "studio_generations",
    foreignKeys = [
        ForeignKey(entity = GenerationEntity::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = PieceEntity::class, parentColumns = ["id"], childColumns = ["seedPieceId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = PieceEntity::class, parentColumns = ["id"], childColumns = ["pieceId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("parentId"), Index("seedPieceId"), Index(value = ["pieceId"], unique = true), Index("createdAt")],
)
data class GenerationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val kind: String,
    val parentId: Long? = null,
    val prompt: String? = null,
    @ColumnInfo(defaultValue = "") val understood: String = "",
    @ColumnInfo(defaultValue = "") val unused: String = "",
    val spec: String? = null,
    val mood: String? = null,
    val keyTonic: Int? = null,
    val keyMinor: Boolean? = null,
    val bpm: Int? = null,
    val minutes: Int? = null,
    val seedPieceId: Long? = null,
    val seedTitle: String? = null,
    val seedComposer: String? = null,
    val seedSource: String? = null,
    val randomSeed: Long? = null,
    val composerModel: String? = null,
    val composerVersion: Int? = null,
    val textModel: String? = null,
    val textModelVersion: Int? = null,
    val imageModel: String? = null,
    val imageModelVersion: Int? = null,
    val tokens: Int? = null,
    val slides: Int? = null,
    val stop: String? = null,
    val musicMs: Long? = null,
    val wallMs: Long? = null,
    val pieceId: Long? = null,
    val title: String? = null,
    val outcome: String,
    val error: String? = null,
    val coverKind: String? = null,
    val coverSeed: Long? = null,
) {
    /** The piece waits for Keep or Discard. */
    val undecided: Boolean get() = outcome == MADE && pieceId != null

    companion object {
        const val COMPOSE = "compose"
        const val TRANSCRIBE = "transcribe"

        const val PENDING = "pending"
        const val MADE = "made"
        const val KEPT = "kept"
        const val DISCARDED = "discarded"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
        const val INTERRUPTED = "interrupted"

        const val DRAWN = "drawn"
    }
}

/**
 * The history's statements that matter for what it keeps (schema v4): the DAO's queries are these constants, so
 * SchemaV4Test runs the very same SQL on a real SQLite.
 */
object GenerationSql {
    /** The pieces waiting for Keep or Discard. */
    const val UNDECIDED = "SELECT pieceId FROM studio_generations WHERE outcome = 'made' AND pieceId IS NOT NULL ORDER BY createdAt, id"

    /** "Made in Studio": every piece a turn made and the library still holds, newest first. */
    const val MADE_HERE = "SELECT pieceId FROM studio_generations WHERE pieceId IS NOT NULL ORDER BY createdAt DESC, id DESC"

    /** At start: a turn that was running when the app stopped can't finish. */
    const val INTERRUPT_PENDING = "UPDATE studio_generations SET outcome = 'interrupted' WHERE outcome = 'pending'"

    /** The one-off import: pieces the DataStore said were waiting go back from kept (the backfill's guess) to made. */
    const val REOPEN = "UPDATE studio_generations SET outcome = 'made' WHERE outcome = 'kept' AND pieceId IN (:ids)"

    /** Keep or Discard: a waiting piece's turn says which. */
    const val DECIDE = "UPDATE studio_generations SET outcome = :outcome WHERE pieceId = :pieceId AND outcome = 'made'"

    /** The oldest turns past [keep], never one whose piece still waits. */
    const val TRIM = "DELETE FROM studio_generations WHERE id IN (SELECT id FROM studio_generations " +
        "WHERE NOT (outcome = 'made' AND pieceId IS NOT NULL) ORDER BY createdAt DESC, id DESC LIMIT -1 OFFSET :keep)"
}
