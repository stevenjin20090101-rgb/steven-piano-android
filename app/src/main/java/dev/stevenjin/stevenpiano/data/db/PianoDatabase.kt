// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The library. Schema v4 (app 1.12 — M30): Studio's history has its table, `studio_generations`. Schema v3
 * (app 1.5): the built-in playlists are marked in the playlists table and the schedules have their table
 * (v2, app 1.2: playlists keep an order and artwork has its table). A v1 database (app 1.1) is migrated by
 * [MIGRATION_1_2], [MIGRATION_2_3] and [MIGRATION_3_4] in turn, a v2 one (apps 1.2 to 1.4) from
 * [MIGRATION_2_3], a v3 one (apps 1.5 to 1.11) by [MIGRATION_3_4]; there is deliberately no destructive
 * fallback, so a migration problem fails loudly instead of wiping the library.
 */
@Database(
    entities = [PieceEntity::class, PlaylistEntity::class, PlaylistPieceEntity::class, ArtworkEntity::class, ScheduleEntity::class, GenerationEntity::class],
    views = [ComposerGroup::class],
    version = 4,
    exportSchema = true,
)
abstract class PianoDatabase : RoomDatabase() {
    abstract fun pieces(): PieceDao

    abstract fun playlists(): PlaylistDao

    abstract fun artwork(): ArtworkDao

    abstract fun schedules(): ScheduleDao

    abstract fun generations(): GenerationDao

    companion object {
        /** [onOpen] runs as the database first opens, before any query (the one-off [TextRepair]). */
        fun open(context: Context, onOpen: (SupportSQLiteDatabase) -> Unit = {}): PianoDatabase =
            Room.databaseBuilder(context, PianoDatabase::class.java, "steven-piano.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(
                    object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) = onOpen(db)
                    },
                )
                .build()
    }
}
