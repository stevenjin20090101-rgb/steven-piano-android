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

@Database(
    entities = [PieceEntity::class, CollectionEntity::class, CollectionPieceEntity::class],
    views = [ComposerGroup::class],
    version = 1,
    exportSchema = true,
)
abstract class PianoDatabase : RoomDatabase() {
    abstract fun pieces(): PieceDao

    abstract fun collections(): CollectionDao

    companion object {
        fun open(context: Context): PianoDatabase =
            Room.databaseBuilder(context, PianoDatabase::class.java, "steven-piano.db").build()
    }
}
