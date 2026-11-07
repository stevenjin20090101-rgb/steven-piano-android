// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

import dev.stevenjin.stevenpiano.ble.EmulatedConsole
import dev.stevenjin.stevenpiano.ble.FakeConsole
import dev.stevenjin.stevenpiano.ble.FakePianoLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M80: what each choice of the strip's colour sends the piano. */
@OptIn(ExperimentalCoroutinesApi::class)
class LedColourTest {
    @Test
    fun `each choice maps to the palette and the tint`() {
        val cover = 0x3A7BD5
        assertEquals(LedColour.Plan(solid = false, tint = "off"), LedColour.plan(LedColourSource.PALETTE, LedColour.REACTIVE, cover))
        assertEquals(LedColour.Plan(solid = true, tint = "off"), LedColour.plan(LedColourSource.CUSTOM, LedColour.REACTIVE, cover))
        assertEquals(LedColour.Plan(solid = false, tint = "off"), LedColour.plan(LedColourSource.CUSTOM, LedColour.STATIC, cover))
        assertEquals(LedColour.Plan(solid = true, tint = "3a7bd5"), LedColour.plan(LedColourSource.ALBUM_ART, LedColour.REACTIVE, cover))
        assertEquals(LedColour.Plan(solid = false, tint = "3a7bd5"), LedColour.plan(LedColourSource.ALBUM_ART, LedColour.STATIC, cover))
        assertEquals("a grey cover, or none", LedColour.Plan(solid = true, tint = "off"), LedColour.plan(LedColourSource.ALBUM_ART, LedColour.REACTIVE, null))
        // Static offers no palettes: Palette reads as the custom colour there.
        assertEquals(listOf(LedColourSource.ALBUM_ART, LedColourSource.CUSTOM), LedColour.sources(LedColour.STATIC))
        assertEquals(LedColourSource.CUSTOM, LedColour.shown(LedColourSource.PALETTE, LedColour.STATIC))
        assertTrue("Rainbow and Off offer none", LedColour.sources("2").isEmpty() && LedColour.sources("0").isEmpty())
        assertTrue(LedColour.glowShows(LedColourSource.ALBUM_ART, LedColour.REACTIVE))
        assertFalse(LedColour.glowShows(LedColourSource.PALETTE, LedColour.REACTIVE))
        assertFalse(LedColour.glowShows(LedColourSource.CUSTOM, LedColour.STATIC))
        assertTrue(LedColour.accepts("ledcolor", "FF8800") && LedColour.accepts("ledtint", "off"))
        assertFalse(LedColour.accepts("ledcolor", "#ff8800") || LedColour.accepts("ledcolor", "off") || LedColour.accepts("ledfade", "5001"))
    }

    private val link = FakePianoLink()
    private val piano = EmulatedConsole()
    private val console = FakeConsole(piano::handle)
    private val source = MutableStateFlow(LedColourSource.PALETTE)
    private val piece = MutableStateFlow<Long?>(null)
    private val followed = mutableListOf<LedColourSource>()

    private fun TestScope.keeping(): PianoSettingsRepository {
        link.connectedWith(console)
        val repo = PianoSettingsRepository(link, backgroundScope, log = {})
        repo.start()
        LedColourKeeper(
            repo, source, piece,
            coverOf = { id -> flowOf(CoverTint(if (id == 1L) 0x3A7BD5 else null)) },
            follow = { followed += it; source.value = it },
            scope = backgroundScope,
            log = {},
        ).start()
        runCurrent()
        return repo
    }

    private fun TestScope.settle() {
        advanceTimeBy(DEBOUNCE_AND_A_BIT)
        runCurrent()
    }

    private fun writes(): List<String> = console.sent.filterNot { it.startsWith("get ") || it == "dump" }

    @Test
    fun `the keeper sends each choice's commands, the cover's tint at each piece, and follows a palette chosen elsewhere`() = runTest {
        piano.handle("reactcolor 1")   // Solid, as an Album art choice left it
        source.value = LedColourSource.ALBUM_ART
        val repo = keeping()
        settle()
        assertEquals("no piece: the custom colour, which the piano shows already", emptyList<String>(), writes())
        piece.value = 1
        settle()
        assertEquals(listOf("ledtint 3a7bd5"), writes())
        console.clearSent()
        piece.value = 2   // a grey cover
        settle()
        assertEquals(listOf("ledtint off"), writes())
        piece.value = 1
        settle()
        console.clearSent()
        source.value = LedColourSource.CUSTOM
        settle()
        assertEquals(listOf("ledtint off"), writes())
        console.clearSent()
        source.value = LedColourSource.PALETTE
        settle()
        assertEquals("the tint is off already; the palette stays as chosen", emptyList<String>(), writes())
        repo.set("reactcolor", "3")   // Fire, on the page's palettes; then the choice turns back to Album art in Reactive: Solid again
        settle()
        console.clearSent()
        source.value = LedColourSource.ALBUM_ART
        settle()
        assertEquals(listOf("reactcolor 1", "ledtint 3a7bd5"), writes())
        console.clearSent()
        piano.handle("reactcolor 4")   // the web panel's Ocean, read back at the next connection: the tablet follows it
        link.drop()
        runCurrent()
        link.connectedWith(console)
        runCurrent()
        settle()
        assertEquals(listOf(LedColourSource.PALETTE), followed)
        assertEquals("Palette: the tint off, and Ocean left alone", listOf("ledtint off"), writes())
    }

    @Test
    fun `a piano without the strip's colour is sent nothing new`() = runTest {
        piano.ledColour = false
        source.value = LedColourSource.ALBUM_ART
        piece.value = 1
        keeping()
        settle()
        assertEquals(emptyList<String>(), writes())
    }

    private companion object {
        const val DEBOUNCE_AND_A_BIT = 151L
    }
}
