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
import dev.stevenjin.stevenpiano.ui.theme.AttentionInk
import dev.stevenjin.stevenpiano.ui.theme.AttentionPaper
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
import dev.stevenjin.stevenpiano.ui.theme.HandLeftDark
import dev.stevenjin.stevenpiano.ui.theme.HandLeftLight
import dev.stevenjin.stevenpiano.ui.theme.HandRightDark
import dev.stevenjin.stevenpiano.ui.theme.HandRightLight
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.NoteSoundingDark
import dev.stevenjin.stevenpiano.ui.theme.NoteSoundingLight
import dev.stevenjin.stevenpiano.ui.components.HAND_SOUNDING_MIX
import dev.stevenjin.stevenpiano.ui.components.KeyLayout
import dev.stevenjin.stevenpiano.ui.components.KeyboardStripHeight
import dev.stevenjin.stevenpiano.ui.components.MAX_CHORD_DRAWS
import dev.stevenjin.stevenpiano.ui.components.MAX_NOTE_DRAWS
import dev.stevenjin.stevenpiano.ui.components.NOTES_DP_PER_SECOND
import dev.stevenjin.stevenpiano.ui.components.NUMERALS_TALL
import dev.stevenjin.stevenpiano.ui.components.RAMP_STEPS
import dev.stevenjin.stevenpiano.ui.components.TRACKER_FROM_BOTTOM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
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
            // The views (v1.13 — M32): the score's sounding yellow and the two hands, each appearance's own.
            "--paper-sounding" to NoteSoundingLight, "--paper-hand-left" to HandLeftLight, "--paper-hand-right" to HandRightLight,
            "--ink-sounding" to NoteSoundingDark, "--ink-hand-left" to HandLeftDark, "--ink-hand-right" to HandRightDark,
            // Needs attention (v1.18 — M47), and nothing else.
            "--paper-attention" to AttentionPaper, "--ink-attention" to AttentionInk,
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
        // The font is the one exception: a binary file, carried unmodified (its licence reserves its name), checked below.
        for (name in WebAssets.NAMES - WebAssets.FONT.name) {
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
    fun `the score's font is Bravura as the app carries it, unmodified, named in AUTHORS and its licence (v1_13 M32)`() {
        val font = File("src/main/res/font/bravura.otf")
        assertTrue(font.isFile)
        assertFalse("one copy only: not in assets/web", File(folder, WebAssets.FONT.name).exists())
        val bytes = font.readBytes()
        assertEquals(889_228, bytes.size)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("cdf0f893ee1fdb64b7f6713d71ee0dcfc349c0ac01429a8e451b01a9e79f5f3b", sha)
        assertEquals(sha.take(8), WebAssets.FONT_VERSION)
        assertTrue("the panel asks for the same version", text("app.js").contains("const FONT_VERSION = '${WebAssets.FONT_VERSION}';"))
        assertEquals("font/otf", WebAssets.FONT.contentType)
        assertTrue(WebAssets.FONT.name in WebAssets.NAMES && WebAssets.FONT !in WebAssets.PANEL.values && WebAssets.FONT !in WebAssets.PUBLIC.values)
        assertTrue(File("../AUTHORS").readText().contains("Bravura music font (app/src/main/res/font/bravura.otf"))
        assertTrue(File("../third_party/bravura/OFL.txt").readText().contains("with Reserved Font Name \"Bravura\""))
        assertTrue("the font face keeps its name", text("score.js").contains("new FontFace('Bravura'"))
    }

    @Test
    fun `the views' modules ask nothing of their own and write styles only through the CSSOM (v1_13 M32)`() {
        val modules = listOf("clock.js", "wire.js", "roll.js", "score.js", "views.js")
        for (name in modules) {
            assertTrue("$name is on the panel's list", WebAssets.PANEL["/$name"] == WebAssets.Asset(name, WebAssets.JS))
            val js = text(name)
            for (banned in listOf("/api/", "fetch(", "WebSocket(", "XMLHttpRequest", "innerHTML", "setAttribute('style'", "cssText", "eval(", "document.write")) {
                assertFalse("$name holds $banned", js.contains(banned))
            }
            assertTrue("$name is a module", js.contains("export "))
        }
        val app = text("app.js")
        assertTrue("loaded the first time a view shows", app.contains("import('./views.js')"))
        assertFalse("never at the page's start", text("index.html").contains("views.js"))
        assertTrue("the divider is a separator", text("views.js").contains("role: 'separator'") && text("views.js").contains("'aria-valuenow'"))
        // The roll's numbers are the app's.
        val roll = text("roll.js")
        fun pinned(name: String, value: Number) = assertTrue("$name: $value", roll.contains("  $name: $value,"))
        pinned("PX_PER_SECOND", NOTES_DP_PER_SECOND.toInt())
        pinned("WHITE_KEYS", KeyLayout.WHITE_KEYS)
        pinned("BLACK_RATIO", KeyLayout.BLACK_RATIO)
        pinned("MAX_DRAWS", MAX_NOTE_DRAWS)
        pinned("RAMP_STEPS", RAMP_STEPS)
        pinned("HAND_SOUNDING_MIX", HAND_SOUNDING_MIX)
        pinned("NUMERALS_TALL", NUMERALS_TALL.toInt())
        pinned("MAX_CHORD_DRAWS", MAX_CHORD_DRAWS)
        pinned("STRIP_HEIGHT", KeyboardStripHeight.value.toInt())
        pinned("FLIP_MS", Motion.FastMs)
        assertEquals(1f / 3f, TRACKER_FROM_BOTTOM)
        assertTrue(roll.contains("  TRACKER_FROM_BOTTOM: 1 / 3,"))
    }

    @Test
    fun `the pages say what the design says`() {
        val request = text("request.html")
        for (copy in listOf("Ask the piano", "Pick a piece. It joins the queue.", "One request every five minutes", "Thanks — it's in the queue.")) {
            assertTrue(copy, request.contains(copy))
        }
        assertTrue(text("request.js").contains("'Request'") || text("request.js").contains("text: 'Request'"))
        val index = text("index.html")
        for (section in listOf("Now playing", "Up next", "Library", "Channels", "Schedule", "Requests", "Add", "Studio", "Piano", "Settings", "System")) assertTrue(section, index.contains(">$section"))
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

    @Test
    fun `the panel starts dark and keeps its side of the Settings and System modules' seam (v1_18 M47)`() {
        val index = text("index.html")
        assertTrue("dark by default", index.contains("<html lang=\"en\" data-theme=\"dark\">") && index.contains("<meta name=\"color-scheme\" content=\"dark light\">"))
        assertTrue("the modules' styles after the frame's", index.indexOf("href=\"system.css\"") > index.indexOf("href=\"style.css\""))
        assertTrue(WebAssets.PANEL["/system.css"] == WebAssets.Asset("system.css", WebAssets.CSS))
        for (id in listOf("piano-tools", "piano-body", "system-tools", "system-body", "rail-vitals")) assertTrue(id, index.contains("id=\"$id\""))
        assertTrue(index.contains("<section id=\"section-system\" data-page=\"system\" hidden>"))
        val app = text("app.js")
        assertTrue("each module loaded the first time it is needed", app.contains("import('./settings.js')") && app.contains("import('./system.js')"))
        assertFalse("never at the page's start", Regex("src=\"(settings|system)\\.js\"").containsMatchIn(index))
        assertTrue("the host is one frozen object", app.contains("const host = Object.freeze({"))
        assertTrue("nothing stored: dark", app.contains("return value === 'light' || value === 'system' ? value : 'dark';"))
        // The guests' page and the poster keep their own appearance.
        assertTrue(text("request.html").contains("<html lang=\"en\">") && text("request.html").contains("content=\"light dark\""))
    }
}
