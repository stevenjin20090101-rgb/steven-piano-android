// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.diag

import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.instruments.InstrumentKind
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.web.relay.CloudStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What needs attention on the System page (v1.18 — M50): the panel's rule, each part on and off, in the panel's order. */
class AttentionTest {
    /** A tablet with nothing to say: 82 %, charging, good, 31 °C, no thermal trouble, memory and storage to spare. */
    private val calm = SystemReading(
        battery = BatteryReading(percent = 82, charging = true, tempC = 31.0, health = "good"),
        thermal = ThermalReading(status = 0),
        memory = MemoryReading(total = 4_000_000_000, available = 1_600_000_000, low = false),
        storage = StorageReading(total = 64_000_000_000, free = 40_000_000_000),
    )

    private fun items(inputs: Attention.Inputs): List<Attention.Item> = Attention.of(inputs)

    private fun tablet(reading: SystemReading): List<Attention.Item> = items(Attention.Inputs(reading = reading))

    private fun piano(vararg facts: Pair<String, String>): List<Attention.Item> = items(Attention.Inputs(reading = calm, piano = PianoDiag(facts.toMap())))

    private fun row(key: String, state: String, title: String = key) = RunningNow.Activity(key, title, state, "detail")

    @Test
    fun `a calm tablet, a healthy piano and an idle app need nothing, nor does a tablet not read yet`() {
        val healthy = PianoDiag(mapOf("boards" to "ok,ok,ok,ok,ok,ok,ok", "temp" to "44.0", "heapmin" to "120000"))
        val idle = RunningNow.KEYS.map { row(it, RunningNow.IDLE) }
        assertTrue(items(Attention.Inputs(calm, idle, healthy, cloudOn = true, cloud = CloudStatus.Connected("relay.example", "p1", 0))).isEmpty())
        assertTrue(items(Attention.Inputs()).isEmpty())
    }

    @Test
    fun `the battery under 20 percent and not charging, or its health bad`() {
        val low = tablet(calm.copy(battery = calm.battery.copy(percent = 15, charging = false)))
        assertEquals(listOf(Attention.Item(Attention.Part.Battery, "The tablet's battery is low · 15%")), low)
        assertTrue("charging", tablet(calm.copy(battery = calm.battery.copy(percent = 15, charging = true))).isEmpty())
        assertTrue("20 % is not under 20", tablet(calm.copy(battery = calm.battery.copy(percent = 20, charging = false))).isEmpty())
        assertEquals("not saying whether it charges counts as not charging", 1, tablet(calm.copy(battery = calm.battery.copy(percent = 5, charging = null))).size)
        assertEquals("The tablet's battery: overheating", tablet(calm.copy(battery = calm.battery.copy(health = "overheat"))).single().text)
        assertEquals("The tablet's battery: over voltage", tablet(calm.copy(battery = calm.battery.copy(health = "overVoltage"))).single().text)
        for (fine in listOf("good", "unknown", null)) assertTrue("$fine", tablet(calm.copy(battery = calm.battery.copy(health = fine))).isEmpty())
    }

    @Test
    fun `the tablet warm from Android's moderate, hot from severe or from 42 degrees on the battery`() {
        fun heat(status: Int?, celsius: Double?) = tablet(calm.copy(thermal = ThermalReading(status = status), battery = calm.battery.copy(tempC = celsius)))
        assertEquals(listOf(Attention.Item(Attention.Part.Heat, "The tablet is warm")), heat(2, 35.0))
        assertEquals("The tablet is hot", heat(3, 35.0).single().text)
        assertEquals("The tablet is hot", heat(0, 42.0).single().text)
        assertEquals("the battery alone, Android silent", "The tablet is hot", heat(null, 45.5).single().text)
        assertTrue("light is warm on the dial, but no attention", heat(1, 41.9).isEmpty())
        assertTrue(heat(null, null).isEmpty())
        assertEquals(listOf("Normal", "Warm", "Warm", "Hot", "Hot"), listOf(0, 1, 2, 3, 6).map { Attention.heatWord(it, 30.0) })
        assertEquals("Hot", Attention.heatWord(0, 42.0))
        assertEquals("Normal", Attention.heatWord(null, 30.0))
        assertEquals("", Attention.heatWord(null, null))
    }

    @Test
    fun `memory low, and storage under 1 GB free`() {
        assertEquals(listOf(Attention.Item(Attention.Part.Memory, "The tablet is low on memory")), tablet(calm.copy(memory = calm.memory.copy(low = true))))
        assertTrue(tablet(calm.copy(memory = calm.memory.copy(low = null))).isEmpty())
        assertEquals(
            listOf(Attention.Item(Attention.Part.Storage, "The tablet's storage is almost full")),
            tablet(calm.copy(storage = StorageReading(64_000_000_000, 999_999_999))),
        )
        assertTrue(tablet(calm.copy(storage = StorageReading(64_000_000_000, 1_000_000_000))).isEmpty())
        assertTrue(tablet(calm.copy(storage = StorageReading())).isEmpty())
    }

    @Test
    fun `a power board missing, the chip at 70 degrees, the controller's memory once under 30 KB`() {
        assertEquals(listOf(Attention.Item(Attention.Part.Boards, "A power board is missing")), piano("boards" to "ok,ok,missing,ok,ok,ok,ok"))
        assertEquals("2 power boards are missing", piano("boards" to "missing,ok,missing").single().text)
        assertTrue(piano("boards" to "ok,ok,ok,ok,ok,ok,ok").isEmpty())
        assertTrue("an unreadable list is no list", piano("boards" to "ok,,missing").isEmpty())
        assertEquals(listOf(Attention.Item(Attention.Part.Chip, "The controller is hot · 70 °C")), piano("temp" to "70.0"))
        assertTrue(piano("temp" to "69.9").isEmpty())
        assertEquals(listOf(Attention.Item(Attention.Part.Heap, "The controller ran low on memory")), piano("heapmin" to "30719"))
        assertTrue(piano("heapmin" to "30720").isEmpty())
        // Only Steven Piano, connected and answering, has facts to read.
        val facts = PianoState.Ready(emptyMap(), mapOf("boards" to "missing"))
        assertNotNull(PianoDiag.of(InstrumentKind.StevenPiano, LinkState.Connected("Steven Piano", 247), facts))
        assertNull(PianoDiag.of(InstrumentKind.StevenPiano, LinkState.Disconnected, facts))
        assertNull(PianoDiag.of(InstrumentKind.MidiPiano, LinkState.Connected("FP-30X", 0), facts))
        assertNull(PianoDiag.of(InstrumentKind.StevenPiano, LinkState.Connected("Steven Piano", 247), PianoState.Unsupported))
    }

    @Test
    fun `the internet link down while the panel is on the internet`() {
        val down = items(Attention.Inputs(cloudOn = true, cloud = CloudStatus.Waiting("No network", 5_000, 0)))
        assertEquals(listOf(Attention.Item(Attention.Part.Relay, "The internet link is not connected")), down)
        assertTrue(items(Attention.Inputs(cloudOn = true, cloud = CloudStatus.Connected("relay.example", "p1", 0))).isEmpty())
        assertTrue("remote access off", items(Attention.Inputs(cloudOn = false, cloud = CloudStatus.Off)).isEmpty())
    }

    @Test
    fun `anything running in trouble, and the covers waiting out Apple's stop, last`() {
        val trouble = items(Attention.Inputs(running = listOf(row("update", RunningNow.PROBLEM, "App update"), row("player", RunningNow.WAITING))))
        assertEquals(listOf(Attention.Item(Attention.Part.Running, "App update needs attention", "update")), trouble)
        val covers = items(Attention.Inputs(running = listOf(row("covers", RunningNow.WAITING, "Artwork"))))
        assertEquals(listOf(Attention.Item(Attention.Part.Running, "Album covers are waiting", "covers")), covers)
        assertTrue(items(Attention.Inputs(running = listOf(row("covers", RunningNow.RUNNING), row("relay", RunningNow.WAITING)))).isEmpty())
    }

    @Test
    fun `everything at once comes most pressing first, as the panel lists it`() {
        val everything = Attention.Inputs(
            reading = SystemReading(
                battery = BatteryReading(percent = 10, charging = false, tempC = 44.0, health = "cold"),
                thermal = ThermalReading(status = 4),
                memory = MemoryReading(low = true),
                storage = StorageReading(free = 1),
            ),
            running = listOf(row("covers", RunningNow.WAITING), row("firmware", RunningNow.PROBLEM)),
            piano = PianoDiag(mapOf("boards" to "missing", "temp" to "80", "heapmin" to "1")),
            cloudOn = true,
        )
        assertEquals(
            listOf(
                Attention.Part.Battery, Attention.Part.Battery, Attention.Part.Heat, Attention.Part.Memory, Attention.Part.Storage,
                Attention.Part.Boards, Attention.Part.Chip, Attention.Part.Heap, Attention.Part.Relay, Attention.Part.Running, Attention.Part.Running,
            ),
            Attention.of(everything).map { it.part },
        )
        assertEquals("Album covers are waiting", Attention.of(everything).last().text)
    }
}
