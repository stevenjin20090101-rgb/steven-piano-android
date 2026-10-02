// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.channels

import android.content.Context
import android.util.Log
import dev.stevenjin.stevenpiano.data.TextKeys
import dev.stevenjin.stevenpiano.data.builtin.BuiltInList
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which pieces a channel plays from (DESIGN.md › v1.5 — M17): its pool, worked out from the
 * library whenever it changes, never stored.
 */
sealed interface PoolMatcher {
    /** The ids of [pieces] in the pool, in [pieces]' order (a built-in list's: its own). Pure. */
    fun pool(pieces: List<PieceEntity>): List<Long>

    /** Everything in the library. */
    data object All : PoolMatcher {
        override fun pool(pieces: List<PieceEntity>): List<Long> = pieces.map { it.id }
    }

    /** A built-in playlist's pieces (Epic), as the playlist holds them. */
    class BuiltIn(val list: BuiltInList) : PoolMatcher {
        override fun pool(pieces: List<PieceEntity>): List<Long> = list.matches(pieces)
    }

    /**
     * Pieces by one of [composers] (composerKeys) or whose folded title matches [titles], and,
     * with [maxNotesPerSecond], that play no more notes a second than that on average (the density
     * `noteCount / durationMs`; a piece of no length is left out then).
     */
    class Match(val composers: Set<String>, val titles: Regex?, val maxNotesPerSecond: Double?) : PoolMatcher {
        override fun pool(pieces: List<PieceEntity>): List<Long> = pieces.filter(::accepts).map { it.id }

        fun accepts(piece: PieceEntity): Boolean {
            // v1.12 (M30): a Studio piece's title names its seed ("Calm, after Clair de lune"); that isn't Clair de lune,
            // so pieces made on the tablet never join a pool by their title.
            val byTitle = piece.composerKey !in MADE_HERE && titles?.containsMatchIn(piece.titleKey) == true
            val chosen = piece.composerKey in composers || byTitle
            return chosen && (maxNotesPerSecond == null || calmEnough(piece, maxNotesPerSecond))
        }

        private fun calmEnough(piece: PieceEntity, max: Double): Boolean =
            piece.durationMs > 0 && piece.noteCount * MILLIS_PER_SECOND / piece.durationMs <= max
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0

        /** Pieces made on the tablet: Studio's and the recordings. */
        val MADE_HERE = setOf(TextKeys.fold(ComposerNames.STUDIO), TextKeys.fold(ComposerNames.RECORDED_LIVE))
    }
}

/** A channel: endless play from a pool (Calm, Epic, Baroque…). [key] is what the player and the settings know it by. */
class Channel(val key: String, val name: String, val pool: PoolMatcher) {
    fun pool(pieces: List<PieceEntity>): List<Long> = pool.pool(pieces)
}

/** A composer on a channel's card: the key finds the portrait, the name the monogram. */
data class CardComposer(val key: String, val name: String)

/**
 * A channel as its card shows it: the pool's [ids] (what it plays), its size, and the four
 * composers it holds most pieces by, most first ([composers], for the mosaic). Equal when nothing
 * the card shows has changed, so a library change that leaves a pool alone does not re-lay the row.
 */
data class ChannelSummary(val key: String, val name: String, val ids: List<Long>, val composers: List<CardComposer>) {
    val size: Int get() = ids.size

    /** Fewer than [MIN_POOL] pieces: the card says "Add more pieces" and does not play. */
    val playable: Boolean get() = size >= MIN_POOL

    companion object {
        const val MIN_POOL = 3
    }
}

/** The channels and their pools. Pure, but for [load]. */
object Channels {
    const val ASSET = "channels.json"

    /** The card's mosaic holds this many composers. */
    const val CARD_COMPOSERS = 4

    /** Reads `assets/channels.json` (off the main thread); Epic's pool is the built-in list [builtIns] names "epic". */
    fun load(context: Context, builtIns: List<BuiltInList>): List<Channel> =
        context.assets.open(ASSET).bufferedReader().use { parse(it.readText(), builtIns) }

    /**
     * The channels in [json], in their order on screen: each a key, a name, and either `all`, a
     * `builtIn` list's key, or `composers` and `titles` (patterns for the folded title, any one
     * enough) with an optional `maxNotesPerSecond`. A malformed catalogue throws: it ships with the
     * app, and ChannelsTest reads it.
     */
    fun parse(json: String, builtIns: List<BuiltInList>): List<Channel> {
        val channels = JSONObject(json).getJSONArray("channels")
        return (0 until channels.length()).map { i ->
            val channel = channels.getJSONObject(i)
            Channel(channel.getString("key"), channel.getString("name"), poolOf(channel, builtIns))
        }
    }

    private fun poolOf(json: JSONObject, builtIns: List<BuiltInList>): PoolMatcher = when {
        json.optBoolean("all") -> PoolMatcher.All
        json.has("builtIn") -> json.getString("builtIn").let { key ->
            PoolMatcher.BuiltIn(builtIns.firstOrNull { it.key == key } ?: error("No built-in list \"$key\" for channel ${json.getString("key")}"))
        }
        else -> PoolMatcher.Match(
            composers = strings(json.optJSONArray("composers")).toSet(),
            titles = strings(json.optJSONArray("titles")).takeIf { it.isNotEmpty() }?.let { Regex(it.joinToString("|"), RegexOption.IGNORE_CASE) },
            maxNotesPerSecond = if (json.has("maxNotesPerSecond")) json.getDouble("maxNotesPerSecond") else null,
        )
    }

    private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }

    /** Every channel's card for [pieces] (the library), in the channels' order. */
    fun summaries(channels: List<Channel>, pieces: List<PieceEntity>): List<ChannelSummary> {
        val byId = pieces.associateBy { it.id }
        return channels.map { channel ->
            val ids = channel.pool(pieces)
            ChannelSummary(channel.key, channel.name, ids, topComposers(ids.mapNotNull(byId::get)))
        }
    }

    /**
     * The [CARD_COMPOSERS] composers with the most pieces in [pool], most first (their name for
     * ties); pieces with no known composer do not count.
     */
    fun topComposers(pool: List<PieceEntity>): List<CardComposer> =
        pool.filter { it.composerKey.isNotEmpty() }
            .groupBy { it.composerKey }
            .map { (key, pieces) -> Triple(key, pieces.size, pieces.minOf { it.composerShort.ifBlank { it.composer } }) }
            .sortedWith(compareByDescending<Triple<String, Int, String>> { it.second }.thenBy { it.third })
            .take(CARD_COMPOSERS)
            .map { (key, _, name) -> CardComposer(key, name) }
}

/**
 * The channels' cards, kept up to date with the library: worked out off the main thread half a
 * second after the library last changed (a play count changes it each time a piece starts), and
 * passed on only when a card would look different. Null until the first is ready.
 */
class ChannelPools(channels: () -> List<Channel>, pieces: Flow<List<PieceEntity>>, scope: CoroutineScope) {
    @OptIn(FlowPreview::class)
    val summaries: StateFlow<List<ChannelSummary>?> = pieces
        .debounce(SETTLE_MS)
        .map { Channels.summaries(channels(), it) }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "The channels couldn't be worked out", e)
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The channel [key]'s card as last worked out, or null. */
    fun summary(key: String): ChannelSummary? = summaries.value?.firstOrNull { it.key == key }

    private companion object {
        const val TAG = "Channels"
        const val SETTLE_MS = 500L
    }
}
