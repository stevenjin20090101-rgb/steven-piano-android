// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guests' requests (the audit's point 10): once every five minutes by any of a guest's keys, fifty waiting at most. */
class GuestRequestsTest {
    private var now = 0L
    private val requests = GuestRequests(clock = { now })
    private val nocturne = WebPiece(2, "Nocturne in E-flat", "Frédéric Chopin", "chopin", 271_000, composerShort = "Chopin")

    @Test
    fun `one request every five minutes, by cookie and by address alike`() {
        assertEquals(GuestRequests.Outcome.Queue, requests.submit(nocturne, listOf("guest:a", "address:10.0.0.2"), approveFirst = false))
        assertEquals(GuestRequests.Outcome.Wait(300_000), requests.submit(nocturne, listOf("guest:a", "address:10.0.0.3"), approveFirst = false))
        assertEquals(GuestRequests.Outcome.Wait(300_000), requests.submit(nocturne, listOf("guest:b", "address:10.0.0.2"), approveFirst = false))
        assertEquals(GuestRequests.Outcome.Queue, requests.submit(nocturne, listOf("guest:c", "address:10.0.0.4"), approveFirst = false))
        now += 299_999
        assertEquals(GuestRequests.Outcome.Wait(1), requests.submit(nocturne, listOf("guest:a"), approveFirst = false))
        now += 1
        assertEquals(GuestRequests.Outcome.Queue, requests.submit(nocturne, listOf("guest:a", "address:10.0.0.2"), approveFirst = false))
    }

    @Test
    fun `with approval first a request waits, oldest first, until taken or dismissed`() {
        val first = requests.submit(nocturne, listOf("guest:a"), approveFirst = true) as GuestRequests.Outcome.Pending
        val second = requests.submit(nocturne.copy(id = 3, title = "Für Elise"), listOf("guest:b"), approveFirst = true) as GuestRequests.Outcome.Pending
        assertEquals(listOf(first.request, second.request), requests.pending.value)
        assertEquals("Chopin", first.request.composer)
        assertEquals(first.request, requests.take(first.request.id))
        assertNull("taken once", requests.take(first.request.id))
        assertTrue(requests.dismiss(second.request.id))
        assertFalse(requests.dismiss(second.request.id))
        assertTrue(requests.pending.value.isEmpty())
    }

    @Test
    fun `at most fifty wait, and fifty guests' pieces in the queue make it full`() {
        repeat(GuestRequests.MAX_PENDING) { i -> assertTrue(requests.submit(nocturne, listOf("guest:$i"), approveFirst = true) is GuestRequests.Outcome.Pending) }
        assertEquals(GuestRequests.Outcome.Full, requests.submit(nocturne, listOf("guest:late"), approveFirst = true))
        assertEquals("a full list does not count as asking", GuestRequests.Outcome.Queue, requests.submit(nocturne, listOf("guest:late"), approveFirst = false))

        val queued = GuestRequests(clock = { now })
        queued.noteQueued((1L..50L).toList())
        assertEquals(GuestRequests.Outcome.Full, queued.submit(nocturne, listOf("guest:x"), approveFirst = false))
        queued.retain((1L..40L).toList())
        assertEquals("ten played or removed: room again", 40, queued.requested.value.size)
        assertEquals(GuestRequests.Outcome.Queue, queued.submit(nocturne, listOf("guest:x"), approveFirst = false))
    }

    @Test
    fun `guests' queue entries are remembered until they leave the queue`() {
        requests.noteQueued(listOf(7L, 9L))
        assertEquals(setOf(7L, 9L), requests.requested.value)
        requests.retain(listOf(1L, 9L, 12L))
        assertEquals(setOf(9L), requests.requested.value)
    }

    @Test
    fun `a bounded number of guests are remembered`() {
        val small = GuestRequests(clock = { now }, maxKeys = 4)
        (1..10).forEach { small.submit(nocturne, listOf("guest:$it"), approveFirst = false) }
        assertEquals("the oldest forgotten: the first may ask again", GuestRequests.Outcome.Queue, small.submit(nocturne, listOf("guest:1"), approveFirst = false))
        assertTrue(small.submit(nocturne, listOf("guest:10"), approveFirst = false) is GuestRequests.Outcome.Wait)
    }
}
