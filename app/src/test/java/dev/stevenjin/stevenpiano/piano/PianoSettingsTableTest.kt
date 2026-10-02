// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The settings table against the firmware's contract, `firmware/docs/BLE_SETTINGS.md`, read at
 * test time from beside this project: every name the app sends is in its `dump` list, with the
 * same range. Skipped, saying so, when the firmware folder is not there.
 */
class PianoSettingsTableTest {
    /** The `dump` list: name (facts with their "!") to what the spec says it holds ("0..255", "0|1", …). */
    private val spec: Map<String, String> by lazy {
        val file = specFile()
        assumeTrue("firmware/docs/BLE_SETTINGS.md is not beside android/, so the table can't be checked against it", file != null)
        val text = file!!.readText()
        val list = text.substringAfter("Exact list and order:").substringAfter("```").substringBefore("```")
        list.lines()
            .mapNotNull { Regex("""^(!?[a-z_]+)=(\S+)""").find(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun specFile(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "firmware/docs/BLE_SETTINGS.md")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `every setting in the table is in the spec's dump list`() {
        assertTrue("the dump list parsed", spec.size > 40)
        val missing = PianoSettings.all.map { it.name }.filter { it !in spec }
        assertEquals("not in BLE_SETTINGS.md's dump list", emptyList<String>(), missing)
    }

    @Test
    fun `every range in the table is the spec's range`() {
        for (setting in PianoSettings.all) {
            val said = spec.getValue(setting.name)
            val whole = Regex("""^(-?\d+)\.\.(-?\d+)$""").find(said)?.groupValues?.drop(1)?.map(String::toInt)
            val decimal = Regex("""^(\d+\.\d+)\.\.(\d+\.\d+)$""").find(said)?.groupValues?.drop(1)?.map(String::toFloat)
            val offOr = Regex("""^0\|(\d+)\.\.(\d+)$""").find(said)?.groupValues?.drop(1)?.map(String::toInt)
            val name = setting.name
            when (val kind = setting.kind) {
                SettingKind.Switch -> assertEquals(name, "0|1", said)
                is SettingKind.Choice -> assertEquals(name, listOf(0, kind.options.size - 1), whole)
                is SettingKind.Stepper -> if (kind.lowestOn != null) {
                    assertEquals(name, listOf(kind.lowestOn, kind.max), offOr)
                    assertEquals(name, 0, kind.min)
                } else {
                    assertEquals(name, listOf(kind.min, kind.max), whole)
                }
                is SettingKind.Slider -> if (setting.readOnly) {
                    assertTrue("$name: read-only, and the spec gives no range", said.startsWith("<"))
                } else {
                    val range = decimal ?: whole?.map(Int::toFloat)
                    assertEquals(name, listOf(kind.min, kind.max), range)
                }
            }
        }
    }

    @Test
    fun `the one tunable the table leaves out is the piano screen's key display`() {
        val tunables = spec.keys.filterNot { it.startsWith("!") }
        assertEquals(listOf("keyviz"), tunables - PianoSettings.all.map { it.name }.toSet())
    }

    @Test
    fun `names are unique and the table runs page by page, section by section`() {
        assertEquals(PianoSettings.all.size, PianoSettings.all.map { it.name }.toSet().size)
        val order = PianoSettings.all.map { it.section.ordinal }
        assertEquals(order.sorted(), order)
        // Firmware and status holds the facts and Read status only since v1.13: the key-force readings went to Sound and touch.
        assertEquals(PianoPage.entries - PianoPage.Firmware, PianoSettings.all.map { it.page }.distinct())
    }

    @Test
    fun `values read the way the piano means them`() {
        fun shown(name: String, wire: String?) = PianoSettings.named(name)!!.display(wire)
        assertEquals("the firmware's own (40 x 100) / 255", "15", shown("ledbright", "40"))
        assertEquals("100", shown("ledbright", "255"))
        assertEquals("Off", shown("restrike", "0"))
        assertEquals("120", shown("restrike", "120"))
        assertEquals("Never", shown("dimsecs", "0"))
        assertEquals("−12", shown("ledoffset", "-12"))
        assertEquals("1.60", shown("velcurve", "1.60"))
        assertEquals("×1.00", shown("keyforce_white", "1.00"))
        assertEquals("Reactive", shown("ledmode", "3"))
        assertEquals("On", shown("leds", "1"))
        assertEquals("—", shown("leds", null))
        assertEquals("15 percent", PianoSettings.named("ledbright")!!.spoken("40"))
        assertEquals("60 milliseconds", PianoSettings.named("minstrike")!!.spoken("60"))
        assertEquals("1.00 times", PianoSettings.named("keyforce_black")!!.spoken("1.00"))
        assertEquals("0:02", PianoSettings.uptime(123))
        assertEquals("1:02", PianoSettings.uptime(3_723))
    }

    @Test
    fun `restrike steps between off and 40, then in tens`() {
        val restrike = PianoSettings.named("restrike")!!.kind as SettingKind.Stepper
        assertEquals(40, restrike.next(0, up = true))
        assertEquals(50, restrike.next(40, up = true))
        assertEquals(0, restrike.next(40, up = false))
        assertEquals("a value off the grid steps down to the lowest on", 40, restrike.next(45, up = false))
        assertEquals(1000, restrike.next(1000, up = true))
        val freq = PianoSettings.named("freq")!!.kind as SettingKind.Stepper
        assertEquals(1526, freq.next(1520, up = true))
        assertEquals(24, freq.next(30, up = false))
    }

    @Test
    fun `sliders snap to their step and stay in range`() {
        val curve = PianoSettings.named("velcurve")!!.kind as SettingKind.Slider
        assertEquals("1.70", PianoSettings.wire(curve.snap(1.68f)))
        assertEquals("0.40", PianoSettings.wire(curve.snap(0f)))
        assertEquals("3.00", PianoSettings.wire(curve.snap(9f)))
        assertEquals("booleans travel as 0 or 1", "1", PianoSettings.wire(true))
    }
}
