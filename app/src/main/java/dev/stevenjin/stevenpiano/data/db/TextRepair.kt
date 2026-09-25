// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.stevenjin.stevenpiano.data.TextLimits

/**
 * The one-off repair for libraries imported before text was capped (the v1.2 audit, finding F1):
 * a row whose text outgrew Android's 2 MB cursor window made every Library query throw. SQLite
 * itself reads such rows fine, so the repair is plain SQL, run when the database opens, before
 * the first query: each over-long value is cut to its [TextLimits] cap (`substr` counts code
 * points, as [TextLimits.clip] does). A playlist name is cut too; if the cut name is taken, it
 * keeps its id as a suffix so the names stay unique. Only rows over a cap change, so running it
 * twice is harmless; it runs once all the same, guarded by a DataStore flag.
 */
object TextRepair {
    val STATEMENTS: List<String> = listOf(
        "UPDATE pieces SET " +
            "title = substr(title, 1, ${TextLimits.TITLE}), " +
            "composer = substr(composer, 1, ${TextLimits.COMPOSER}), " +
            "collection = substr(collection, 1, ${TextLimits.COLLECTION}), " +
            "sourceName = substr(sourceName, 1, ${TextLimits.SOURCE_NAME}), " +
            "searchText = substr(searchText, 1, ${TextLimits.SEARCH_TEXT}), " +
            "titleKey = substr(titleKey, 1, ${TextLimits.TITLE_KEY}), " +
            "composerShort = substr(composerShort, 1, ${TextLimits.COMPOSER}), " +
            "composerKey = substr(composerKey, 1, ${TextLimits.COMPOSER}) " +
            "WHERE length(title) > ${TextLimits.TITLE} OR length(composer) > ${TextLimits.COMPOSER} " +
            "OR length(collection) > ${TextLimits.COLLECTION} OR length(sourceName) > ${TextLimits.SOURCE_NAME} " +
            "OR length(searchText) > ${TextLimits.SEARCH_TEXT} OR length(titleKey) > ${TextLimits.TITLE_KEY} " +
            "OR length(composerShort) > ${TextLimits.COMPOSER} OR length(composerKey) > ${TextLimits.COMPOSER}",
        "UPDATE OR IGNORE collections SET name = substr(name, 1, ${TextLimits.COLLECTION}) " +
            "WHERE length(name) > ${TextLimits.COLLECTION}",
        "UPDATE OR IGNORE collections SET name = substr(name, 1, ${TextLimits.COLLECTION - 20}) || ' (' || id || ')' " +
            "WHERE length(name) > ${TextLimits.COLLECTION}",
    )

    /**
     * Runs [STATEMENTS] in one transaction on [db] unless [due] says it has run already, then
     * [done] records that it has. A failure is reported to [log] and leaves it due, so the next
     * start tries again; the database opens either way.
     */
    fun runOnce(db: SupportSQLiteDatabase, due: () -> Boolean, done: () -> Unit, log: (String) -> Unit) =
        runOnce(due, done, log) {
            db.beginTransaction()
            try {
                STATEMENTS.forEach(db::execSQL)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

    /** [apply] once: only when [due], and [done] only after it succeeds. Pure, for tests. */
    fun runOnce(due: () -> Boolean, done: () -> Unit, log: (String) -> Unit, apply: () -> Unit) {
        try {
            if (!due()) return
            apply()
            done()
            log("Library text repair done")
        } catch (e: RuntimeException) {
            log("Library text repair failed: ${e.message}")
        }
    }
}
