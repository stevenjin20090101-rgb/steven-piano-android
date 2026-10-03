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
import dev.stevenjin.stevenpiano.data.db.ArtworkStatus
import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import dev.stevenjin.stevenpiano.net.WikiApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * How a run of fetches is going ("Fetching artwork 12 of 61"): [done] of [total] queued in the
 * background (a sheet's own request is not counted), [current] the name being fetched. [idle]
 * when nothing is queued or under way.
 */
data class ArtworkProgress(val done: Int = 0, val total: Int = 0, val current: String? = null, val idle: Boolean = true) {
    companion object {
        val Idle = ArtworkProgress()
    }
}

/** Keeps at least [gapMs] between the starts of consecutive requests: at most four a second. */
class RequestPacer(private val now: () -> Long, private val gapMs: Long = MIN_GAP_MS, private val sleep: suspend (Long) -> Unit = { delay(it) }) {
    private var lastStart: Long? = null

    /** Returns when the next request may start, and counts it as started. */
    suspend fun await() {
        lastStart?.let { last ->
            val wait = last + gapMs - now()
            if (wait > 0) sleep(wait)
        }
        lastStart = now()
    }

    companion object {
        const val MIN_GAP_MS = 250L
    }
}

/** [inner] with every request paced by [pacer]. */
class PacedWikiApi(private val inner: WikiApi, private val pacer: RequestPacer) : WikiApi {
    override suspend fun summary(title: String) = pacer.await().let { inner.summary(title) }

    override suspend fun search(query: String) = pacer.await().let { inner.search(query) }

    override suspend fun download(url: String, maxBytes: Int) = pacer.await().let { inner.download(url, maxBytes) }
}

/** Where fetched images are kept. [save] returns the stored path, or null when the bytes are not an image Android can show. */
fun interface ImageStore {
    suspend fun save(storageKey: String, bytes: ByteArray): String?
}

/**
 * The one artwork worker: a queue of keys fetched strictly one after another by [fetcher] (whose
 * requests are paced), recorded in [store] with images in [images]. [request] puts a key last,
 * or first when the person is waiting on it (a sheet just opened); in the background Wikipedia's
 * keys go ahead of the album covers queued (v1.15 — M40), so an import's composers never wait
 * out a library's covers. Before each fetch [ArtworkPolicy] decides whether it is due (a piece's
 * notes as [ArtworkPolicy.recordOf] reads its row); offline ([online] false) a key is skipped and
 * nothing is recorded, and a failure seen while offline is not recorded either. Wikimedia asking
 * to slow down, or Apple's lookups waiting out a 403 or 429, records nothing: the worker pauses as
 * asked, or, asked for more than a minute, leaves the rest of that source's background run for the
 * next start. Rows are written under [writing] (the repository's lock) and merged: a piece's cover
 * stays when its notes arrive, and a row found while its fetch was under way (a cover chosen by
 * hand) is never overwritten.
 *
 * Confined to [scope]'s thread (the app's main thread; a test's scheduler): the queue is only
 * touched there, and finding a queued key is a hash lookup, not a scan (only a Wikipedia key
 * queued in the background scans, for the first cover to stand before). Fetching suspends; it
 * never blocks that thread. An automatic run asks about at most [MAX_OTHER_COMPOSERS] composers
 * outside the library's list of well-known ones ([ComposerNames.canonical]); the rest are left
 * due for the next run, so a 10,000-file import does not queue hours of lookups.
 */
class ArtworkWorker(
    private val store: ArtworkDao,
    private val fetcher: ArtworkFetcher,
    private val images: ImageStore,
    private val online: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val writing: Mutex = Mutex(),
    /** When the wider album-cover match began (v1.17 — M45; 0 unset): a cover's lookup that found nothing before it is due once more. */
    private val coverRuleSince: suspend () -> Long = { 0L },
) {
    private class Task(val key: ArtKey, var force: Boolean, val background: Boolean)

    /** The scope's own thread, where the queue lives. */
    private val confined: CoroutineContext = scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext
    private val queue = ArrayDeque<Task>()

    /** Every queued task by its storage key: the queue's index, kept in step with it. */
    private val queued = HashMap<String, Task>()
    private var worker: Job? = null

    /** Composers outside the canonical list that this run's automatic requests have queued. */
    private var othersThisRun = 0
    private var inFlight: String? = null
    private var done = 0
    private var total = 0
    private val state = MutableStateFlow(ArtworkProgress.Idle)
    val progress: StateFlow<ArtworkProgress> = state.asStateFlow()

    /** Queues [key]: first when [priority] (it jumps ahead of anything queued), otherwise last. */
    fun request(key: ArtKey, priority: Boolean, force: Boolean) {
        scope.launch { enqueue(key, priority, force) }
    }

    /**
     * Queues every key of [keys] that is due ([ArtworkPolicy]), in order, behind whatever is
     * queued: unless [force] (the person asked for every composer), at most
     * [MAX_OTHER_COMPOSERS] composers a run from outside the canonical list. Returns how many were
     * queued; by then [progress] shows them.
     */
    suspend fun requestAll(keys: List<ArtKey>, force: Boolean): Int = withContext(confined) {
        val now = clock()
        val since = coverRuleSince()
        var count = 0
        for (key in keys) {
            val other = key is ArtKey.Composer && ComposerNames.canonical(key.composerKey) == null
            if (other && !force && othersThisRun >= MAX_OTHER_COMPOSERS) continue
            if (!ArtworkPolicy.shouldFetch(ArtworkPolicy.recordOf(key, store.get(key.storageKey)), now, force, since)) continue
            if (other && !force) othersThisRun++
            enqueue(key, priority = false, force = force)
            count++
        }
        count
    }

    /** Drops everything queued in the background (the fetch under way finishes); nothing is recorded for them. */
    fun cancelBackground() {
        scope.launch {
            dropBackground()
            if (queue.isEmpty() && inFlight == null) finish() else publish()
        }
    }

    /**
     * Takes the background tasks out of the queue, and out of the run's count: all of them, or only those from [like]'s
     * source (Apple's covers, or Wikipedia's keys; v1.15 — M40). Returns how many.
     */
    private fun dropBackground(like: ArtKey? = null): Int {
        val drop = { task: Task -> task.background && (like == null || (task.key is ArtKey.Cover) == (like is ArtKey.Cover)) }
        val dropped = queue.count(drop)
        queue.removeAll(drop)
        queued.values.removeAll(drop)
        total -= dropped
        return dropped
    }

    private fun enqueue(key: ArtKey, priority: Boolean, force: Boolean) {
        val storageKey = key.storageKey
        if (storageKey == inFlight) return
        val existing = queued[storageKey]
        if (existing != null) {
            existing.force = existing.force || force
            if (priority) {
                queue.remove(existing)   // a person waiting on a key: rare, so this one scan is fine
                queue.addFirst(existing)
            }
        } else {
            val task = Task(key, force, background = !priority)
            when {
                priority -> queue.addFirst(task)
                key is ArtKey.Cover -> queue.addLast(task)
                else -> {   // ahead of the covers queued in the background (v1.15 — M40): rare, and at most a few hundred a run
                    val covers = queue.indexOfFirst { it.background && it.key is ArtKey.Cover }
                    if (covers < 0) queue.addLast(task) else queue.add(covers, task)
                }
            }
            queued[storageKey] = task
            if (task.background) total++
        }
        publish()
        if (worker?.isActive != true) worker = scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val task = queue.removeFirstOrNull() ?: break
            queued.remove(task.key.storageKey)
            inFlight = task.key.storageKey
            publish(task.key.label)
            try {
                process(task)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {   // a database or file problem: this key is left for later, the rest go on
                log("Artwork for ${task.key.storageKey} stopped: ${e.message}")
            } finally {
                inFlight = null
                if (task.background) done++
            }
        }
        finish()
    }

    private suspend fun process(task: Task) {
        val key = task.key.storageKey
        if (!ArtworkPolicy.shouldFetch(ArtworkPolicy.recordOf(task.key, store.get(key)), clock(), task.force, coverRuleSince())) return
        if (!online()) {
            log("Offline: $key skipped, nothing recorded")
            return
        }
        when (val outcome = fetcher.fetch(task.key)) {
            is Fetched.Found -> {
                val path = outcome.image?.let { images.save(key, it) }
                record(task.key) { kept -> ArtworkEntity(key, path ?: kept?.imagePath, outcome.description, outcome.sourceUrl, outcome.sourceTitle, clock(), ArtworkStatus.OK) }
                log("$key: found${if (path != null) " with a picture" else ""}")
            }
            Fetched.NotFound -> {
                record(task.key) { kept -> ArtworkEntity(key, kept?.imagePath, fetchedAt = clock(), status = ArtworkStatus.NOT_FOUND) }
                log("$key: not found")
            }
            is Fetched.Failed -> if (online()) {
                record(task.key) { kept -> ArtworkEntity(key, kept?.imagePath, fetchedAt = clock(), status = ArtworkStatus.FAILED) }
                log("$key: failed (${outcome.reason}); retried after a day")
            } else {
                log("$key: offline mid-fetch, nothing recorded")
            }
            Fetched.Saved -> log("$key: found with a picture")
            Fetched.Skipped -> log("$key: nothing to look up")
            is Fetched.Busy -> {
                val source = if (task.key is ArtKey.Cover) "Apple" else "Wikimedia"
                if (outcome.retryAfterMs > ArtworkFetcher.MAX_WAIT_MS) {
                    val dropped = dropBackground(like = task.key)
                    log("$key: $source asked to wait ${outcome.retryAfterMs} ms; this run stops, $dropped more left for the next")
                } else {
                    log("$key: $source asked to wait ${outcome.retryAfterMs} ms; left for later")
                    pause(outcome.retryAfterMs.coerceAtLeast(MIN_BUSY_PAUSE_MS))
                }
            }
        }
    }

    /**
     * Writes [key]'s row as [row] makes it from the row there now (whose picture it keeps: a piece's cover stays when its
     * notes arrive), under the repository's lock. A row its lookup reads as found was written while the fetch was under
     * way (a cover chosen by hand, a Studio piece's line): it stays, and nothing is written.
     */
    private suspend fun record(key: ArtKey, row: (kept: ArtworkEntity?) -> ArtworkEntity) = writing.withLock {
        val now = store.get(key.storageKey)
        if (ArtworkPolicy.recordOf(key, now)?.status == ArtworkStatus.OK) return@withLock
        store.upsert(row(now))
    }

    private fun publish(current: String? = state.value.current) {
        state.value = ArtworkProgress(done, total, current, idle = false)
    }

    private fun finish() {
        done = 0
        total = 0
        othersThisRun = 0
        worker = null
        state.value = ArtworkProgress.Idle
    }

    companion object {
        private const val MIN_BUSY_PAUSE_MS = 1_000L

        /** Automatic lookups a run makes for composers the library does not know by name. */
        const val MAX_OTHER_COMPOSERS = 200
    }
}
