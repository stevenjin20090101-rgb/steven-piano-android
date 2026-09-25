// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException

/** A folder tree from a documents provider that may loop, go deep or never end (the v1.2 audit, F4). */
class TreeWalkTest {
    /** A provider: each folder id lists its children; [asked] counts the folder queries. */
    private class FakeProvider(private val folders: Map<String, List<TreeWalk.Child>>) {
        var asked = 0

        fun list(folderId: String, visit: (TreeWalk.Child) -> Boolean) {
            asked++
            for (child in folders[folderId].orEmpty()) if (!visit(child)) return
        }
    }

    private fun folder(id: String, name: String = id) = TreeWalk.Child(id, name, isFolder = true)

    private fun file(name: String) = TreeWalk.Child("f:$name", name, isFolder = false)

    private fun walk(provider: FakeProvider, cancelled: () -> Boolean = { false }) = TreeWalk.walk("root", cancelled, provider::list)

    @Test
    fun `a tree is listed breadth first, hidden files skipped, the shallowest index kept`() {
        val provider = FakeProvider(
            mapOf(
                "root" to listOf(file("a.mid"), folder("sub"), file(".hidden.mid"), file("notes.txt"), file("INDEX.csv")),
                "sub" to listOf(file("b.MIDI"), file("INDEX.csv")),
            ),
        )
        val result = walk(provider)
        assertEquals(listOf("a.mid", "sub/b.MIDI"), result.files.map { it.path })
        assertEquals("INDEX.csv", result.index?.path)
        assertNull(result.limited)
    }

    @Test
    fun `a folder that contains itself, or is listed twice, is walked once`() {
        val provider = FakeProvider(
            mapOf(
                "root" to listOf(folder("loop"), folder("loop", "again")),
                "loop" to listOf(file("x.mid"), folder("loop"), folder("root")),
            ),
        )
        val result = walk(provider)
        assertEquals(listOf("loop/x.mid"), result.files.map { it.path })
        assertEquals(2, provider.asked)
    }

    @Test
    fun `folders deeper than 16 levels are not walked`() {
        val chain = (0 until 30).associate { depth ->
            (if (depth == 0) "root" else "d$depth") to listOf(file("at$depth.mid"), folder("d${depth + 1}"))
        }
        val result = walk(FakeProvider(chain))
        assertEquals(17, result.files.size)   // the chosen folder and 16 levels below it
        assertTrue(result.files.last().path.startsWith("d1/d2/"))
        assertEquals(16, result.files.last().path.count { it == '/' })
    }

    @Test
    fun `at most 20,000 MIDI files are taken`() {
        val provider = FakeProvider(mapOf("root" to (0 until 25_000).map { file("$it.mid") }))
        val result = walk(provider)
        assertEquals(20_000, result.files.size)
        assertEquals("more than 20000 MIDI files", result.limited)
    }

    @Test
    fun `at most 5,000 folders are walked`() {
        val provider = FakeProvider(mapOf("root" to (0 until 6_000).map { folder("k$it") }))
        val result = walk(provider)
        assertEquals("more than 5000 folders", result.limited)
        assertTrue(provider.asked <= 1)   // the walk stops before listing any of them
    }

    @Test
    fun `a listing that never ends is cut at 100,000 documents`() {
        var handed = 0
        val endless: (String, (TreeWalk.Child) -> Boolean) -> Unit = { _, visit ->
            while (visit(TreeWalk.Child("n$handed", "n${handed++}.txt", isFolder = false))) Unit
        }
        val result = TreeWalk.walk("root", { false }, endless)
        assertEquals("more than 100000 documents", result.limited)
        assertEquals(100_001, handed)
    }

    @Test
    fun `cancelling stops the walk before the next folder`() {
        var folders = 0
        val provider = FakeProvider((0 until 50).associate { (if (it == 0) "root" else "d$it") to listOf(folder("d${it + 1}")) })
        try {
            walk(provider) { ++folders > 3 }
            fail("a cancelled walk throws")
        } catch (e: CancellationException) {
            assertEquals(3, provider.asked)
        }
    }
}
