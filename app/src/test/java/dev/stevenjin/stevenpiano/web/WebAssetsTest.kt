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
import dev.stevenjin.stevenpiano.diag.Attention
import dev.stevenjin.stevenpiano.ui.components.HAND_SOUNDING_MIX
import dev.stevenjin.stevenpiano.ui.components.KeyLayout
import dev.stevenjin.stevenpiano.ui.components.KeyboardStripHeight
import dev.stevenjin.stevenpiano.ui.components.MAX_CHORD_DRAWS
import dev.stevenjin.stevenpiano.ui.components.MAX_NOTE_DRAWS
import dev.stevenjin.stevenpiano.ui.components.NOTES_DP_PER_SECOND
import dev.stevenjin.stevenpiano.ui.components.NUMERALS_TALL
import dev.stevenjin.stevenpiano.ui.components.RAMP_STEPS
import dev.stevenjin.stevenpiano.ui.components.TRACKER_FROM_BOTTOM
import dev.stevenjin.stevenpiano.ui.theme.AttentionInk
import dev.stevenjin.stevenpiano.ui.theme.AttentionPaper
import dev.stevenjin.stevenpiano.ui.theme.CarbonPrimary
import dev.stevenjin.stevenpiano.ui.theme.CarbonSecondary
import dev.stevenjin.stevenpiano.ui.theme.CarbonTertiary
import dev.stevenjin.stevenpiano.ui.theme.GlassEdgeDark
import dev.stevenjin.stevenpiano.ui.theme.GlassEdgeLight
import dev.stevenjin.stevenpiano.ui.theme.GlassTokens
import dev.stevenjin.stevenpiano.ui.theme.HandLeftDark
import dev.stevenjin.stevenpiano.ui.theme.HandLeftLight
import dev.stevenjin.stevenpiano.ui.theme.HandRightDark
import dev.stevenjin.stevenpiano.ui.theme.HandRightLight
import dev.stevenjin.stevenpiano.ui.theme.InkDisabledGlyph
import dev.stevenjin.stevenpiano.ui.theme.InkElevated
import dev.stevenjin.stevenpiano.ui.theme.InkHairline
import dev.stevenjin.stevenpiano.ui.theme.InkSurface
import dev.stevenjin.stevenpiano.ui.theme.LiveRedDark
import dev.stevenjin.stevenpiano.ui.theme.LiveRedLight
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.NoteSoundingDark
import dev.stevenjin.stevenpiano.ui.theme.NoteSoundingLight
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
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The panel's static files (assets/web): the app's tokens, value for value, and the rules a mistake in them would break:
 * the page's security (the policy, nothing built from text, every request from the panel's ROOT, the modules asking only
 * through the host), the relay's prefix, and the piano's safety (Live never offered, a key held said first, red only for
 * notes reaching the piano). Since v1.27 (M63) it pins nothing else of the files' text: the words and the layout are the
 * panel's to change.
 */
class WebAssetsTest {
    private val folder = File("src/main/assets/web")

    private fun text(name: String) = File(folder, name).readText()

    private fun hex(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)

    /** Every file the server may ever read: the panel's, the guests', the poster's template and the score's font. */
    private val served: Set<String> = (WebAssets.PANEL.values + WebAssets.PUBLIC.values + WebAssets.POSTER + WebAssets.FONT).map { it.name }.toSet()

    /** The frame's scripts and the guests' page's: each builds its requests from the page's own ROOT. */
    private val framed = listOf("app.js", "request.js")

    /** The modules the frame hands its host to: each asks the tablet only through it, from host.ROOT. */
    private val hosted = listOf("settings.js", "system.js", "covers.js", "quiet.js")

    /** Now playing's views: they ask nothing themselves (the frame's api object does). */
    private val views = listOf("clock.js", "wire.js", "roll.js", "score.js", "views.js")

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
    fun `the panel's glass is the app's, value for value`() {
        val css = text("style.css")
        fun declared(name: String) = Regex("${Regex.escape(name)}:\\s*([^;]+);").find(css)?.groupValues?.get(1)?.trim()
        assertEquals("${(GlassTokens.ContainerAlpha * 100).roundToInt()}%", declared("--glass-bar"))
        assertEquals("${(GlassTokens.SheetAlpha * 100).roundToInt()}%", declared("--glass-sheet"))
        assertEquals("${GlassTokens.Blur.value.roundToInt()}px", declared("--glass-blur"))
        assertEquals("${GlassTokens.EdgeBand.value.roundToInt()}px", declared("--glass-band"))
        assertEquals("${GlassTokens.RailBand.value.roundToInt()}px", declared("--glass-rail-band"))
        assertEquals("hsl(0 0% 100% / ${String.format(Locale.ROOT, "%.2f", GlassEdgeDark.alpha)})", declared("--ink-specular"))
        assertEquals("hsl(0 0% 100% / ${String.format(Locale.ROOT, "%.2f", GlassEdgeLight.alpha)})", declared("--paper-specular"))
    }

    @Test
    fun `the roll's numbers are the app's`() {
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
        pinned("FLIP_MS", Motion.QuickMs)
        assertEquals(1f / 3f, TRACKER_FROM_BOTTOM)
        assertTrue(roll.contains("  TRACKER_FROM_BOTTOM: 1 / 3,"))
    }

    @Test
    fun `red is the live dot's alone, notes reaching the piano`() {
        val css = text("style.css").replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        assertEquals("--live is read by the dot alone", 2, Regex("var\\(--live\\)").findAll(css).count())
        assertTrue(css.contains(".dot.live { border-color: var(--live); background: var(--live); }"))
        for (name in served.filter { it.endsWith(".css") } - "style.css") assertFalse("$name reads --live", text(name).contains("var(--live)"))
    }

    @Test
    fun `every file the server may send exists, carries the banner, and has no inline script, style or handler`() {
        // The font is the one exception: a binary file, carried unmodified (its licence reserves its name), checked below.
        for (name in served - WebAssets.FONT.name) {
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
            if (name.endsWith(".svg")) {
                // The mark (v1.20 — M53), on the panel's own address: a picture and nothing more.
                assertFalse("$name: no script", body.contains("<script", ignoreCase = true) || Regex("\\son[a-z]+=", RegexOption.IGNORE_CASE).containsMatchIn(body))
                assertFalse("$name: nothing from elsewhere", Regex("(?:href|src)=").containsMatchIn(body) || body.replace("http://www.w3.org/2000/svg", "").contains("http"))
            }
        }
    }

    @Test
    fun `every request the panel builds starts from its ROOT, so the pages work under the relay's prefix (v1_10 M26)`() {
        for (name in framed) {
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
        }
        val app = text("app.js")
        assertTrue("the socket follows the page's scheme and ROOT", app.contains("new WebSocket(`\${location.protocol === 'https:' ? 'wss' : 'ws'}://\${location.host}\${ROOT}/ws`)"))
        assertFalse("never a bare ws:// socket", app.contains("WebSocket(`ws://"))
    }

    @Test
    fun `the panel's modules are on the list, ask the tablet only through the host from its ROOT, and nothing of their own`() {
        for (name in hosted + views) assertEquals("$name is on the panel's list", WebAssets.Asset(name, WebAssets.JS), WebAssets.PANEL["/$name"])
        for (name in hosted) {
            val js = text(name)
            for (banned in listOf("fetch(", "WebSocket(", "XMLHttpRequest", "setAttribute('style'", "cssText")) assertFalse("$name holds $banned", js.contains(banned))
            val paths = Regex("['`\"]/api/").findAll(js).map { it.range.first }.toList()
            assertTrue("$name asks the tablet", paths.isNotEmpty())
            for (at in paths) assertEquals("$name: every /api/ path starts from host.ROOT (at $at)", "host.ROOT + ", js.substring(maxOf(0, at - 12), at))
        }
        for (name in views) {
            val js = text(name)
            for (banned in listOf("/api/", "fetch(", "WebSocket(", "XMLHttpRequest", "setAttribute('style'", "cssText")) assertFalse("$name holds $banned", js.contains(banned))
        }
        // The cover picker shows the tablet's own pictures alone: data: JPEGs or PNGs, never an address from Apple.
        assertTrue(text("covers.js").contains("/^data:image\\/(?:jpeg|png);base64,"))
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
        assertEquals("font/otf", WebAssets.FONT.contentType)
        assertTrue(WebAssets.FONT.name in served && WebAssets.FONT !in WebAssets.PANEL.values && WebAssets.FONT !in WebAssets.PUBLIC.values)
        assertTrue(File("../AUTHORS").readText().contains("Bravura music font (app/src/main/res/font/bravura.otf"))
        assertTrue(File("../third_party/bravura/OFL.txt").readText().contains("with Reserved Font Name \"Bravura\""))
    }

    @Test
    fun `the piano's safety on the panel - Live is never offered, and a key held on is said first (v1_23 M55)`() {
        for (name in framed - "request.js" + hosted) assertFalse("$name never offers Live", text(name).contains("liveToPiano") || text(name).contains("/api/live"))
        val system = text("system.js")
        assertTrue("a key held on, first of all", system.contains("items.push({ part: 'held', text: KEY_HELD, short: 'key held' })"))
        assertTrue(system.contains("const KEY_HELD = '${Attention.KEY_HELD}';"))
    }

    @Test
    fun `the panel hears the room only when it asked, and confirms it's you before the microphone or an update (v1_23 M55, v1_24 M56)`() {
        assertTrue("the room's frames play only on the panel that asked", text("system.js").contains("if (!hear.live && !hear.test) return;"))
        val app = text("app.js")
        assertTrue("a 403 confirm asks for the PIN once, then sends again", app.contains("data.error === 'confirm' && !confirmed") && app.contains("return call(method, path, body, true);"))
        assertTrue(app.contains("call('POST', ROOT + '/api/confirm', { pin }, true)"))
        assertTrue("the PIN never shows", app.contains("class: 'pin-field', type: 'password', inputmode: 'numeric'"))
        assertTrue("Sign out ends this browser's session", text("settings.js").contains("await host.post(host.ROOT + '/api/logout');"))
    }
}
