// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.stevenjin.stevenpiano.ui.PowerCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The power watch's usual idle in the plug's store (v1.31 — M68), piano safety: the plug's last rail-up readings outlive the
 * app, go with its pairing, and start again only after a trip and a safety check that heard every key. Since v1.36 (M72):
 * the update clears a Shelly plug's pairing (data: once, said, idempotent), and Power use's meter is kept with the pairing.
 */
class PlugStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file by lazy { File(tmp.root, "power_plug.preferences_pb") }

    /** The store's file as one run of the app opens it; [block] runs, then the run ends. */
    private fun <T> raw(block: suspend (DataStore<Preferences>) -> T): T = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            block(PreferenceDataStoreFactory.create(scope = scope) { file })
        } finally {
            scope.cancel()
        }
    }

    /** The store as one run of the app opens it; [block] runs, then the run ends. */
    private fun <T> session(block: suspend (PlugStore) -> T): T = raw { block(PlugStore(it)) }

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

    @Test
    fun `the update to the SwitchBot - a Shelly pairing is cleared with its usual idle, said, and a second run changes nothing (v1_36 M72)`() {
        raw { store ->
            store.edit { prefs ->   // as 1.35 kept a Shelly plug
                prefs[stringPreferencesKey("address")] = "A8:03:2A:B5:C1:D2"
                prefs[stringPreferencesKey("id")] = "shellyplugusg4-a8032ab5c1d2"
                prefs[stringPreferencesKey("firmware")] = "2.0.1"
                prefs[stringPreferencesKey("bondEntry")] = "00:00:5E:00:53:A1"
                prefs[booleanPreferencesKey("locked")] = true
                prefs[booleanPreferencesKey("matterOff")] = true
                prefs[booleanPreferencesKey("on")] = true
                prefs[stringPreferencesKey("idles")] = "1761300000000:9.8,1761300060000:10.1,1761300120000:10.0"
            }
        }
        val before = kept()
        assertNull("read as no plug even before it is cleared: the rail never switches a Shelly", before.pairing)
        assertFalse(before.switches)
        assertEquals(PlugSettings(shellyCleared = true), before)

        assertTrue("cleared, for the log once", session { it.migrate() })
        val after = kept()
        assertEquals(PlugSettings(shellyCleared = true), after)
        assertEquals("Piano › Power plug says to pair the SwitchBot", PowerCopy.PAIR, "Pair the SwitchBot plug")
        assertTrue(PowerCopy.SHELLY_CLEARED.endsWith(PowerCopy.PAIR + "."))
        raw { store -> assertEquals("every Shelly field gone", setOf("shellyCleared"), store.data.first().asMap().keys.map { it.name }.toSet()) }

        assertFalse("a second run finds nothing", session { it.migrate() })
        assertEquals(after, kept())

        session { it.pair(PLUG) }
        assertEquals("a SwitchBot paired: the message goes", PlugSettings(pairing = PLUG, on = true), kept())
        assertFalse("and the migration leaves it alone", session { it.migrate() })
        assertEquals(PLUG, kept().pairing)
    }

    @Test
    fun `the power use round-trips through the store, goes with the pairing, and never lands on another plug (v1_36 M72)`() {
        val zone = java.time.ZoneId.of("Europe/London")
        val meter = EnergyMeter(zone)
        for (i in 0..1_800) meter.add(1_761_300_000_000L + i * 2_000L, 10.0 + (i % 7))
        session { store ->
            store.pair(PLUG)
            store.keepEnergy(PLUG.address, meter.encode())
            store.keepEnergy("11:22:33:44:55:66", "1|999|-|")   // another plug's: not kept
        }
        val back = EnergyMeter.decode(session { it.energy(PLUG.address) }, zone)
        assertEquals(meter.totalWh, back.totalWh, 1e-6)
        assertEquals(meter.peak, back.peak)
        assertEquals(meter.days().keys, back.days().keys)
        for ((day, use) in meter.days()) {
            assertEquals(use.wh, back.days().getValue(day).wh, 1e-6)
            assertEquals(use.peak, back.days().getValue(day).peak)
        }
        assertNull("asked for by another plug: none", session { it.energy("11:22:33:44:55:66") })

        session { it.pair(PLUG.copy(address = "11:22:33:44:55:66")) }
        assertNull("a new pairing starts from nothing", session { it.energy("11:22:33:44:55:66") })
        session { store ->
            store.keepEnergy("11:22:33:44:55:66", meter.encode())
            store.forget()
        }
        assertNull("forgotten with the plug", session { it.energy("11:22:33:44:55:66") })
    }

    private companion object {
        val PLUG = PlugPairing("68:EE:8F:28:47:CA", mac = "68:EE:8F:28:47:CA")
    }
}
