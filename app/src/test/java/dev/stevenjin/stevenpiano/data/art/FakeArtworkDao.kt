// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.data.db.ArtworkDao
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The artwork table in memory. */
class FakeArtworkDao : ArtworkDao {
    private val table = MutableStateFlow<Map<String, ArtworkEntity>>(emptyMap())
    val rows: Map<String, ArtworkEntity> get() = table.value

    override fun observe(key: String): Flow<ArtworkEntity?> = table.map { it[key] }

    override suspend fun get(key: String): ArtworkEntity? = table.value[key]

    override fun observeAll(): Flow<List<ArtworkEntity>> = table.map { it.values.toList() }

    override suspend fun upsert(artwork: ArtworkEntity) {
        table.value = table.value + (artwork.key to artwork)
    }

    override suspend fun delete(key: String) {
        table.value = table.value - key
    }
}
