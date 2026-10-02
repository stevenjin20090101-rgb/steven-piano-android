// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.stevenjin.stevenpiano.ui.theme.CarbonPrimary
import dev.stevenjin.stevenpiano.ui.theme.CarbonSecondary
import dev.stevenjin.stevenpiano.ui.theme.CarbonTertiary
import dev.stevenjin.stevenpiano.ui.theme.GlassEdgeDark
import dev.stevenjin.stevenpiano.ui.theme.GlassEdgeLight
import dev.stevenjin.stevenpiano.ui.theme.GlassTokens
import dev.stevenjin.stevenpiano.ui.theme.InkDisabledGlyph
import dev.stevenjin.stevenpiano.ui.theme.InkElevated
import dev.stevenjin.stevenpiano.ui.theme.InkHairline
import dev.stevenjin.stevenpiano.ui.theme.InkSurface
import dev.stevenjin.stevenpiano.ui.theme.LiveRedDark
import dev.stevenjin.stevenpiano.ui.theme.LiveRedLight
import dev.stevenjin.stevenpiano.ui.theme.PaperDisabledGlyph
import dev.stevenjin.stevenpiano.ui.theme.PaperElevated
import dev.stevenjin.stevenpiano.ui.theme.PaperHairline
import dev.stevenjin.stevenpiano.ui.theme.PaperSurface
import dev.stevenjin.stevenpiano.ui.theme.SilverPrimary
import dev.stevenjin.stevenpiano.ui.theme.SilverSecondary
import dev.stevenjin.stevenpiano.ui.theme.SilverTertiary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** The panel's static files (assets/web): the app's tokens, the policy's rules, the allow-list. */
class WebAssetsTest {
    private val folder = File("src/main/assets/web")

    private fun text(name: String) = File(folder, name).readText()

    private fun hex(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)

    @Test
    fun `the stylesheet's two palettes are the app's tokens, value for value`() {
        val css = text("style.css")
        val tokens = mapOf(
            "--paper-surface" to PaperSurface, "--paper-elevated" to PaperElevated, "--paper-hairline" to PaperHairline,
            "--paper-disabled" to PaperDisabledGlyph, "--paper-primary" to CarbonPrimary, "--paper-secondary" to CarbonSecondary,
            "--paper-tertiary" to CarbonTertiary, "--paper-live" to LiveRedLight,
            "--ink-surface" to InkSurface, "--ink-elevated" to InkElevated, "--ink-hairline" to InkHairline,
            "--ink-disabled" to InkDisabledGlyph, "--ink-primary" to SilverPrimary, "--ink-secondary" to SilverSecondary,
            "--ink-tertiary" to SilverTertiary, "--ink-live" to LiveRedDark,
        )
        for ((name, color) in tokens) {
            val declared = Regex("${Regex.escape(name)}:\\s*(#[0-9A-Fa-f]{6});").find(css)?.groupValues?.get(1)
            assertEquals(name, hex(color), declared?.uppercase())
        }
    }

    @Test
    fun `the panel's glass is the app's, value for value, and gives way to solid surfaces when asked`() {
        val css = text("style.css")
        fun declared(name: String) = Regex("${Regex.escape(name)}:\\s*([^;]+);").find(css)?.groupValues?.get(1)?.trim()
        assertEquals("${(GlassTokens.ContainerAlpha * 100).roundToInt()}%", declared("--glass-bar"))
        assertEquals("${(GlassTokens.SheetAlpha * 100).roundToInt()}%", declared("--glass-sheet"))
        assertEquals("${GlassTokens.Blur.value.roundToInt()}px", declared("--glass-blur"))
        assertEquals("${GlassTokens.EdgeBand.value.roundToInt()}px", declared("--glass-band"))
        assertEquals("${GlassTokens.RailBand.value.roundToInt()}px", declared("--glass-rail-band"))
        assertEquals("hsl(0 0% 100% / ${String.format(Locale.ROOT, "%.2f", GlassEdgeDark.alpha)})", declared("--ink-specular"))
        assertEquals("hsl(0 0% 100% / ${String.format(Locale.ROOT, "%.2f", GlassEdgeLight.alpha)})", declared("--paper-specular"))
        // Reduce transparency and more contrast: solid surfaces; reduced motion: no fading bands.
        assertTrue(css.contains("@media (prefers-reduced-transparency: reduce), (prefers-contrast: more)"))
        assertTrue(css.contains("@media (prefers-reduced-motion: reduce) {\n  .sections::after { transition: none; }"))
        // The primary action is the filled circle, never glass.
        assertTrue(css.contains(".play-circle {") && text("index.html").contains("class=\"play-circle\" id=\"now-play\""))
        assertFalse(css.contains(".lens"))
    }

    @Test
    fun `no surface is pure black or white, and red is the dot's alone`() {
        val css = text("style.css").replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace("white-space", "")
        for (forbidden in listOf("#000", "#fff", "#FFF", "white", "black", "rgb(0", "rgb(255")) {
            assertFalse("the stylesheet names $forbidden", css.contains(forbidden))
        }
        val usesOfLive = Regex("var\\(--live\\)").findAll(css).count()
        val declaresLive = Regex("--live:").findAll(css).count()
        assertEquals("--live is read by the dot alone", 2, usesOfLive)
        assertEquals(3, declaresLive)
        assertTrue(css.contains(".dot.live { border-color: var(--live); background: var(--live); }"))
    }

    @Test
    fun `every file the server may send exists, carries the banner, and has no inline script, style or handler`() {
        for (name in WebAssets.NAMES) {
            val file = File(folder, name)
            assertTrue(name, file.isFile)
            val body = file.readText()
            assertTrue("$name keeps the authorship banner", body.contains("Authorship provenance (Ed25519 fingerprint): eab16a502f679465"))
            if (name.endsWith(".html")) {
                assertFalse("$name: no inline style", Regex("\\sstyle=").containsMatchIn(body))
                assertFalse("$name: no inline handler", Regex("\\son[a-z]+=", RegexOption.IGNORE_CASE).containsMatchIn(body))
                assertFalse("$name: no inline script", Regex("<script(?![^>]*\\ssrc=)[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(body))
                assertFalse("$name: no <style> block", body.contains("<style"))
                val referenced = Regex("(?:src|href)=\"([^\"#][^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
                for (ref in referenced) {
                    if (ref.startsWith("http://www.w3.org/")) continue
                    val path = if (ref.startsWith("/")) ref else "/$ref"
                    assertTrue("$name links $ref, which the allow-list serves", path in WebAssets.PUBLIC || path in WebAssets.PANEL)
                }
                assertFalse("$name: nothing from another site", Regex("(?:src|href)=\"https?://").containsMatchIn(body.replace("http://www.w3.org/2000/svg", "")))
            }
            if (name.endsWith(".js")) {
                assertFalse("$name never builds markup from text", body.contains("innerHTML") || body.contains("outerHTML") || body.contains("insertAdjacentHTML") || body.contains("document.write"))
                assertFalse("$name runs no strings", Regex("\\beval\\(|new Function\\(").containsMatchIn(body))
            }
        }
    }

    @Test
    fun `every request the panel builds starts from its ROOT, so the pages work under the relay's prefix (v1_10 M26)`() {
        for (name in listOf("app.js", "request.js")) {
            val js = text(name)
            assertTrue("$name defines ROOT from where the page lives", Regex("const ROOT = location\\.pathname\\.replace\\(").containsMatchIn(js))
            val quoted = Regex("'/api/").findAll(js).map { it.range.first }.toList()
            assertTrue("$name builds requests", quoted.isNotEmpty())
            for (at in quoted) assertTrue("$name: every '/api/ is ROOT + '/api/ (at $at)", js.substring(maxOf(0, at - 7), at) == "ROOT + ")
            for (match in Regex("[`\"]/api/").findAll(js)) {
                val at = match.range.first
                val before = js.substring(maxOf(0, at - 7), at)
                assertTrue("$name: a template or string starting /api/ starts from ROOT (at $at: …$before${match.value})", before == "ROOT + " || js.substring(at, at + 8) == "\"\${ROOT}")
            }
            assertFalse("$name: no absolute /api/ left in a template", Regex("[`\"]/api/").findAll(js).any { js.substring(maxOf(0, it.range.first - 7), it.range.first) != "ROOT + " })
        }
        val app = text("app.js")
        assertTrue("the socket follows the page's scheme and ROOT", app.contains("new WebSocket(`\${location.protocol === 'https:' ? 'wss' : 'ws'}://\${location.host}\${ROOT}/ws`)"))
        assertFalse("never a bare ws:// socket", app.contains("WebSocket(`ws://"))
        assertTrue("the relay's offline answer shows the offline card, not the PIN gate", app.contains("data.error === 'offline'") && text("index.html").contains("id=\"offline\""))
        assertTrue(text("index.html").contains("The piano is offline"))
        assertTrue(text("request.js").contains("error === 'offline'"))
    }

    @Test
    fun `the pages say what the design says`() {
        val request = text("request.html")
        for (copy in listOf("Ask the piano", "Pick a piece. It joins the queue.", "One request every five minutes", "Thanks — it's in the queue.")) {
            assertTrue(copy, request.contains(copy))
        }
        assertTrue(text("request.js").contains("'Request'") || text("request.js").contains("text: 'Request'"))
        val index = text("index.html")
        for (section in listOf("Now playing", "Up next", "Library", "Channels", "Schedule", "Requests", "Add", "Studio", "Piano")) assertTrue(section, index.contains(">$section"))
        for (copy in listOf(
            "Drop a piano recording here", "About a minute per three minutes of audio.", "/api/studio/audio", "/api/studio/jobs/",
            "Compose a piece…", "Runs on this tablet. About a minute for a two-minute piece.", "/api/studio/compose", "/api/studio/seed",
            "In the manner of", "'Melancholy'",
        )) {
            assertTrue("Studio (1.7): $copy", text("app.js").contains(copy))
        }
        assertTrue("the Schedule page is real (1.6.2)", index.contains(">Add schedule<") && !text("app.js").contains("Coming in the next update."))
        for (copy in listOf("No schedules yet.", "Choose at least one day.", "Choose what to play.", "The tablet starts them: keep it on, charged and near the piano.")) {
            assertTrue(copy, text("app.js").contains(copy))
        }
        assertTrue("the tablet's piano sound (1.8), its volume by its one name (1.13)", index.contains("Tablet volume") && text("app.js").contains("tabletVolume"))
        assertTrue("places named as the tablet names them (1.13)", index.contains("Piano › Web panel") && text("app.js").contains("Piano › Tablet sound") && !text("app.js").contains("Remote control"))
        // What plays and what is played from (1.11 — M29): two read-only lines, the piano's pages hidden under a MIDI piano.
        assertTrue(index.contains("id=\"instrument-line\"") && index.contains("id=\"keyboard-line\""))
        for (copy in listOf("Instrument: ", "Keyboard: ", "'Live'", "'Recording'", "belong to Steven Piano and are hidden while")) {
            assertTrue("Instruments (1.11): $copy", text("app.js").contains(copy))
        }
        assertFalse("Live is never offered to the panel", text("app.js").contains("liveToPiano") || text("app.js").contains("/api/live"))
        assertTrue(text("poster.html").contains("data-theme=\"light\""))
    }
}
