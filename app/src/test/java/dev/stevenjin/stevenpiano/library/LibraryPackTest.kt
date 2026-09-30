// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.library

import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.data.imports.openLocalZip
import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.update.FakeUpdateServer
import dev.stevenjin.stevenpiano.update.UpdateSource
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Steven's library pack end to end on the JVM (v1.10 — M27): the version compare and the offer, the
 * first load and an update that brings only the pieces no earlier pack offered (never deleting one),
 * the pack's bookkeeping, a failed or cancelled load that leaves no file and records nothing, and the
 * start of a load.
 */
class LibraryPackTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var cache: File
    private lateinit var offeredDir: File
    private val server = FakeUpdateServer("").apply { chunk = 4_096 }
    private val online = MutableStateFlow(true)
    private val loaded = MutableStateFlow(0)
    private val starts = mutableListOf<Boolean>()
    private val logs = mutableListOf<String>()
    private var clock = 1_000_000L
    private var space = 10L shl 30

    /** The library as the fake import keeps it: the pieces' hashes. */
    private val library = mutableSetOf<String>()
    private val imports = mutableListOf<ImportSource.LocalZip>()
    private var importFails = false
    private var importHangs = false

    @Before
    fun folders() {
        cache = File(tmp.root, "cache/library")
        offeredDir = File(tmp.root, "files/library")
    }

    private fun midi(key: Int) = SmfBuilder(format = 0).track {
        noteOn(0, key)
        noteOff(480, key)
    }.build()

    private fun sha(bytes: ByteArray) = VerifiedDownloader.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private val v1Pieces = (0 until 5).map { "collection/piece$it.mid" to midi(40 + it) }
    private val v2Pieces = v1Pieces + listOf("collection/new1.mid" to midi(60), "collection/new2.mid" to midi(61))

    private fun url(version: Int) = "https://github.com/${UpdateSource.REPOSITORY}/releases/download/library/library-v$version.zip"

    /** A pack as tools/publish_library.py writes it: INDEX.csv with its sha256 column, then the files. */
    private fun packOf(pieces: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val index = StringBuilder("collection,composer,title,size_kb,path,sha256\n")
            for ((path, bytes) in pieces) index.append("collection,Steven,${path.substringAfterLast('/')},1,$path,${sha(bytes)}\n")
            zip.putNextEntry(ZipEntry("INDEX.csv"))
            zip.write(index.toString().toByteArray())
            zip.closeEntry()
            for ((path, bytes) in pieces) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Publishes pack [version] of [pieces] on the fake server: its zip, and the manifest naming it. */
    private fun publish(version: Int, pieces: List<Pair<String, ByteArray>>, sha: String? = null): ByteArray {
        val bytes = packOf(pieces)
        server.files[url(version)] = bytes
        server.manifestText = JSONObject()
            .put("version", version).put("file", "library-v$version.zip").put("url", url(version))
            .put("sizeBytes", bytes.size).put("sha256", sha ?: sha(bytes)).put("pieces", pieces.size)
            .toString()
        return bytes
    }

    /** The import as the app runs it (the pack's zip through `openLocalZip`), each piece's hash into [library]. */
    private suspend fun import(source: ImportSource.LocalZip): ImportProgress {
        imports += source
        if (importHangs) awaitCancellation()
        openLocalZip(source).use { opened ->
            var imported = 0
            var duplicates = 0
            var failed = 0
            for (item in opened.items) {
                when {
                    importFails -> failed++
                    library.add(sha(item.open().use { it.readBytes() })) -> imported++
                    else -> duplicates++
                }
            }
            val n = opened.items.size
            return ImportProgress(done = n, total = n, imported = imported, duplicates = duplicates, failed = failed, finished = true)
        }
    }

    private fun TestScope.pack(start: (Boolean) -> Unit = { starts += it }) = LibraryPack(
        source = UpdateSource.library,
        server = server,
        downloader = VerifiedDownloader(server, Dispatchers.Unconfined) { space },
        offered = OfferedPacks(offeredDir),
        cacheDir = cache,
        loadedVersion = loaded,
        recordLoaded = { loaded.value = it },
        import = ::import,
        online = online,
        scope = backgroundScope,
        start = start,
        io = Dispatchers.Unconfined,
        now = { clock },
        log = { logs += it },
    )

    private fun offeredIn(version: Int): List<String> = File(offeredDir, "offered-v$version.txt").readLines()

    private fun leftInCache(): List<String> = cache.list().orEmpty().toList()

    @Test
    fun `a check offers a pack newer than the one loaded, and nothing when it is not newer`() = runTest {
        val bytes = publish(1, v1Pieces)
        val pack = pack()
        runCurrent()
        assertFalse(pack.newerAvailable.value)
        pack.check()
        runCurrent()
        val offer = PackState.Offered(version = 1, pieces = 5, sizeBytes = bytes.size.toLong(), newPieces = 5)
        assertEquals(offer, pack.state.value)
        assertEquals(offer, pack.offer.value)
        assertTrue("a fresh tablet: any pack is newer", pack.newerAvailable.value)
        assertEquals(1, server.manifestCalls)
        assertTrue("a check downloads nothing", server.downloads.isEmpty())

        loaded.value = 1   // the same version, loaded
        pack.check()
        runCurrent()
        assertEquals(PackState.Idle, pack.state.value)
        assertNull(pack.offer.value)
        assertFalse(pack.newerAvailable.value)

        loaded.value = 3   // an older pack than the one loaded (a manifest never goes back; if it did, it is not offered)
        pack.check()
        runCurrent()
        assertEquals(PackState.Idle, pack.state.value)
        assertFalse(pack.newerAvailable.value)
    }

    @Test
    fun `a check asks only when online, and not again within its age`() = runTest {
        publish(1, v1Pieces)
        val pack = pack()
        online.value = false
        pack.check()
        assertEquals("offline, nothing is asked", 0, server.manifestCalls)
        online.value = true
        pack.check(maxAgeMs = 600_000)
        assertEquals(1, server.manifestCalls)
        clock += 60_000
        pack.check(maxAgeMs = 600_000)
        assertEquals("asked a minute ago", 1, server.manifestCalls)
        clock += 600_000
        pack.check(maxAgeMs = 600_000)
        assertEquals(2, server.manifestCalls)
        pack.check()
        assertEquals("on demand with no age: asked now", 3, server.manifestCalls)
    }

    @Test
    fun `a check that fails changes nothing on screen`() = runTest {
        publish(1, v1Pieces)
        val pack = pack()
        pack.check()
        val offered = pack.state.value
        server.failing = true
        clock += 1
        pack.check()
        assertEquals(offered, pack.state.value)
        server.failing = false
        server.manifestText = "not json"
        pack.check()
        assertEquals(offered, pack.state.value)
    }

    @Test
    fun `the first load brings every piece in and records the pack`() = runTest {
        val bytes = publish(1, v1Pieces)
        val pack = pack()
        pack.run(everything = false)
        assertEquals(PackState.Done(5), pack.state.value)
        assertEquals(listOf(url(1)), server.downloads)
        assertEquals(emptySet<String>(), imports.single().skipShas)
        assertEquals(File(cache, "library-v1.zip"), imports.single().file)
        assertEquals(v1Pieces.map { sha(it.second) }.toSet(), library)
        assertEquals(1, loaded.value)
        assertEquals("the pack's pieces, from its own index", v1Pieces.map { sha(it.second) }.sorted(), offeredIn(1))
        assertEquals("the zip is gone, and no part file is left", emptyList<String>(), leftInCache())
        assertNull("nothing newer to offer", pack.offer.value)
        assertTrue("the pack arrived whole (${bytes.size} bytes) before it was read", imports.single().file.name == "library-v1.zip")
        assertTrue(logs.any { "version 1 loaded, 5 pieces added" in it })
    }

    @Test
    fun `an update brings only the pieces no earlier pack offered, and never brings back a deleted one`() = runTest {
        publish(1, v1Pieces)
        val pack = pack()
        pack.run(everything = false)
        val deleted = sha(v1Pieces[2].second)
        library -= deleted   // the person deleted a piece of pack 1

        val bytes = publish(2, v2Pieces)
        pack.check()
        runCurrent()
        assertEquals(PackState.Offered(version = 2, pieces = 7, sizeBytes = bytes.size.toLong(), newPieces = 2), pack.state.value)
        assertTrue(pack.newerAvailable.value)

        pack.run(everything = false)
        assertEquals(PackState.Done(2), pack.state.value)
        assertEquals("pack 1's pieces are left out of the import", v1Pieces.map { sha(it.second) }.toSet(), imports.last().skipShas)
        assertFalse("the deleted piece stays deleted", deleted in library)
        assertEquals(6, library.size)
        assertEquals(2, loaded.value)
        assertEquals(v2Pieces.map { sha(it.second) }.sorted(), offeredIn(2))
        assertEquals("pack 1's record stays", 5, offeredIn(1).size)
        runCurrent()
        assertFalse(pack.newerAvailable.value)
        assertEquals(emptyList<String>(), leftInCache())
    }

    @Test
    fun `everything brings every piece of the pack again, even the pack loaded`() = runTest {
        publish(1, v1Pieces)
        val pack = pack()
        pack.run(everything = false)
        val deleted = sha(v1Pieces[0].second)
        library -= deleted
        pack.run(everything = true)
        assertEquals(PackState.Done(1), pack.state.value)
        assertEquals(emptySet<String>(), imports.last().skipShas)
        assertTrue(deleted in library)
        assertEquals(2, server.downloads.size)
        assertEquals(1, loaded.value)
    }

    @Test
    fun `a load when the pack loaded is the newest asks and downloads nothing`() = runTest {
        publish(1, v1Pieces)
        loaded.value = 1
        val pack = pack()
        pack.run(everything = false)
        assertEquals(PackState.Idle, pack.state.value)
        assertEquals(1, server.manifestCalls)
        assertTrue(server.downloads.isEmpty())
        assertTrue(imports.isEmpty())
    }

    @Test
    fun `a failed download leaves no part file, records nothing, and the pack stays on offer`() = runTest {
        val bytes = publish(1, v1Pieces)
        val pack = pack()
        server.chunk = 256
        server.breakAfter = bytes.size / 2   // the connection drops half-way
        pack.run(everything = false)
        assertEquals(PackState.Failed(LibraryFailures.STOPPED), pack.state.value)
        assertEquals(emptyList<String>(), leftInCache())
        assertFalse(offeredDir.exists())
        assertEquals(0, loaded.value)
        assertTrue(imports.isEmpty())
        val offer = PackState.Offered(1, 5, bytes.size.toLong(), 5)
        assertEquals(offer, pack.offer.value)
        pack.dismiss()
        assertEquals("dismissed, the offer is back", offer, pack.state.value)

        server.breakAfter = null
        publish(1, v1Pieces, sha = "0".repeat(64))
        pack.run(everything = false)
        assertEquals(PackState.Failed(LibraryFailures.MISMATCH), pack.state.value)
        assertEquals(emptyList<String>(), leftInCache())

        publish(1, v1Pieces)
        space = 1_000
        pack.run(everything = false)
        assertEquals(PackState.Failed(LibraryFailures.NO_ROOM), pack.state.value)
        assertEquals(emptyList<String>(), leftInCache())

        space = 10L shl 30
        server.files.clear()   // the release's file gone (a 404)
        pack.run(everything = false)
        assertEquals(PackState.Failed(LibraryFailures.UNREACHABLE), pack.state.value)
        assertEquals(emptyList<String>(), leftInCache())
        assertEquals(0, loaded.value)
    }

    @Test
    fun `a manifest that can't be had or read is one line in words`() = runTest {
        publish(1, v1Pieces)
        val pack = pack()
        online.value = false
        pack.run(everything = false)
        assertEquals(PackState.Failed(LibraryFailures.OFFLINE), pack.state.value)
        assertEquals(0, server.manifestCalls)
        online.value = true
        server.failing = true
        pack.run(everything = false)
        assertEquals(PackState.Failed(LibraryFailures.UNREACHABLE), pack.state.value)
        server.failing = false
        server.manifestText = "not json"
        pack.run(everything = false)
        assertEquals(PackState.Failed(LibraryFailures.UNREADABLE), pack.state.value)
        server.manifestText = JSONObject(publishText()).put("url", "https://evil.example/library-v1.zip").toString()
        pack.run(everything = false)
        assertEquals(PackState.Failed(LibraryFailures.UNREADABLE), pack.state.value)
        assertTrue(server.downloads.isEmpty())
    }

    private fun publishText(): String {
        publish(1, v1Pieces)
        return server.manifestText
    }

    @Test
    fun `an import that reads no piece records nothing`() = runTest {
        publish(1, v1Pieces)
        val pack = pack()
        importFails = true
        pack.run(everything = false)
        assertEquals(PackState.Failed(LibraryFailures.NOT_READ), pack.state.value)
        assertEquals(0, loaded.value)
        assertFalse(File(offeredDir, "offered-v1.txt").exists())
        assertEquals(emptyList<String>(), leftInCache())
    }

    @Test
    fun `a cancelled load goes back to the offer, leaves no file and records nothing`() = runTest {
        val bytes = publish(1, v1Pieces)
        val pack = pack()
        importHangs = true
        val job = launch { pack.run(everything = false) }
        runCurrent()
        assertEquals(PackState.Importing, pack.state.value)
        job.cancel()
        runCurrent()
        assertEquals(PackState.Offered(1, 5, bytes.size.toLong(), 5), pack.state.value)
        assertEquals(emptyList<String>(), leftInCache())
        assertEquals(0, loaded.value)
        assertFalse(offeredDir.exists())
    }

    @Test
    fun `load starts one run at a time, and a start Android refuses is said in words`() = runTest {
        publish(1, v1Pieces)
        val pack = pack()
        assertTrue(pack.load())
        assertEquals(listOf(false), starts)
        assertEquals(PackState.Checking, pack.state.value)
        assertFalse("a load is under way", pack.load(everything = true))
        assertEquals(listOf(false), starts)
        pack.run(everything = false)
        assertEquals(PackState.Done(5), pack.state.value)
        assertTrue(pack.load(everything = true))
        assertEquals(listOf(false, true), starts)

        val refused = pack { throw IllegalStateException("not allowed") }
        assertFalse(refused.load())
        assertEquals(PackState.Failed(LibraryFailures.NOT_STARTED), refused.state.value)
    }

    @Test
    fun `a check never replaces a load under way`() = runTest {
        publish(1, v1Pieces)
        val pack = pack()
        pack.load()
        pack.check()
        assertEquals(PackState.Checking, pack.state.value)
        assertEquals("a load asks for its own manifest", 0, server.manifestCalls)
    }

    @Test
    fun `the schedule's check is due at once, then a day later`() = runTest {
        publish(1, v1Pieces)
        val pack = pack()
        assertEquals(0L, pack.untilDue())
        pack.check()
        assertEquals(LibraryPack.INTERVAL_MS, pack.untilDue())
        clock += 3_600_000
        assertEquals(LibraryPack.INTERVAL_MS - 3_600_000, pack.untilDue())
        clock -= 7_200_000   // the clock went back
        assertEquals(0L, pack.untilDue())
    }

    @Test
    fun `the offered packs are each pack's own file, read together`() {
        val offered = OfferedPacks(offeredDir)
        assertEquals(emptySet<String>(), offered.all())
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val c = "C".repeat(64)
        offered.record(1, listOf(b, a))
        offered.record(2, listOf(b, c.lowercase()))
        assertEquals(listOf(a, b), File(offeredDir, "offered-v1.txt").readLines())
        assertEquals(setOf(a, b, c.lowercase()), offered.all())
        File(offeredDir, "notes.txt").writeText("d".repeat(64))
        File(offeredDir, "offered-v3.txt").writeText("not a hash\n${c}\n")
        assertEquals("other files and other lines are not read; a hash is kept lower-case", setOf(a, b, c.lowercase()), offered.all())
        offered.record(1, listOf(a))
        assertEquals("a record replaces the pack's own", listOf(a), File(offeredDir, "offered-v1.txt").readLines())
        assertEquals(listOf("offered-v1.txt", "offered-v2.txt", "offered-v3.txt", "notes.txt").sorted(), offeredDir.list()!!.sorted())
    }

    @Test
    fun `a stopped load's files are swept at the next start`() {
        cache.mkdirs()
        val old = File(cache, "library-v1.zip.part").apply { writeText("part"); setLastModified(1_000) }
        val stale = File(cache, "library-v1.zip").apply { writeText("zip"); setLastModified(2_000) }
        val fresh = File(cache, "library-v2.zip.part").apply { writeText("part"); setLastModified(9_000) }
        assertEquals(2, LibraryPack.sweep(cache, before = 5_000))
        assertFalse(old.exists())
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertEquals(0, LibraryPack.sweep(File(tmp.root, "none"), before = 5_000))
    }
}
