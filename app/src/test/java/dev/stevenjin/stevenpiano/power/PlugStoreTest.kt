// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The power watch's usual idle in the plug's store (v1.31 — M68), piano safety: the plug's last rail-up readings outlive the
 * app, go with its pairing, and start again only after a trip and a safety check that heard every key.
 */
class PlugStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file by lazy { File(tmp.root, "power_plug.preferences_pb") }

    /** The store as one run of the app opens it; [block] runs, then the run ends. */
    private fun <T> session(block: suspend (PlugStore) -> T): T = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            block(PlugStore(PreferenceDataStoreFactory.create(scope = scope) { file }))
        } finally {
            scope.cancel()
        }
    }

    private fun kept(): PlugSettings = session { it.settings.first() }

    @Test
    fun `the last 5 rail-up readings are kept with their times across a restart, and a forgotten or re-paired plug starts empty`() {
        val readings = listOf(9.8, 10.1, 10.0, 9.9, 10.3, 10.2, 9.7).mapIndexed { i, watts -> IdleReading(1_761_300_000_000L + i * 60_000L, watts) }
        session { store ->
            store.pair(PLUG)
            for (reading in readings) store.keepIdle(reading)
        }
        assertEquals("the app started again: the last five, oldest first", readings.takeLast(5), kept().idles)

        session { it.forget() }
        assertEquals(PlugSettings(), kept())
        session { store ->
            store.keepIdle(readings.first())
            store.aboveUsual()
        }
        assertEquals("no plug paired: nothing kept", PlugSettings(), kept())

        session { store ->
            store.pair(PLUG)
            for (reading in readings) store.keepIdle(reading)
            store.aboveUsual()
            store.pair(PLUG.copy(address = "11:22:33:44:55:66"))
        }
        val repaired = kept()
        assertEquals("paired again: empty", emptyList<IdleReading>(), repaired.idles)
        assertFalse(repaired.aboveUsual)
    }

    @Test
    fun `after a trip a check that heard every key starts the usual again, and without a trip it stays`() {
        val readings = (0 until 5).map { IdleReading(1_761_300_000_000L + it * 60_000L, 10.0) }
        session { store ->
            store.pair(PLUG)
            for (reading in readings) store.keepIdle(reading)
            assertFalse("no trip: a routine check that heard every key leaves it", store.heardAll())
        }
        assertEquals(readings, kept().idles)

        session { it.aboveUsual() }
        assertTrue("the trip is kept for the next check, across a restart", kept().aboveUsual)
        assertEquals(readings, kept().idles)
        assertTrue(session { it.heardAll() })
        val again = kept()
        assertEquals("a new normal is learned from the next rail-up", emptyList<IdleReading>(), again.idles)
        assertFalse(again.aboveUsual)
        assertEquals(PLUG, again.pairing)
    }

    private companion object {
        val PLUG = PlugPairing("AA:BB:CC:DD:EE:FF", id = "shellyplugusg4-aabbccddeeff")
    }
}
