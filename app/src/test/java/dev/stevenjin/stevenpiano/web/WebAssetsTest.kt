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

import dev.stevenjin.stevenpiano.schedule.QuietCopy
import dev.stevenjin.stevenpiano.schedule.QuietTimes
import dev.stevenjin.stevenpiano.ui.PlaybackCopy
import dev.stevenjin.stevenpiano.ui.SettingNotes
import dev.stevenjin.stevenpiano.ui.screens.piano.PageRows
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
        // v1.20 — M53: five places; Up next, Add music and the Piano capsule in the pages' heads; the rest built by the script.
        for (words in listOf("Now playing", "Library", "Quiet times", "Studio", "Settings", "Up next", "Add music", "Piano", "Play anyway")) assertTrue(words, index.contains(">$words"))
        for (copy in listOf("'Channels'", "`Requests · ", "`Approve \${", "`Decline \${", "'Added to Up next.'")) assertTrue(copy, text("app.js").contains(copy))
        for (copy in listOf(
            "Drop a piano recording here", "About a minute per three minutes of audio.", "/api/studio/audio", "/api/studio/jobs/",
            "Compose a piece…", "Runs on this tablet. About a minute for a two-minute piece.", "/api/studio/compose", "/api/studio/seed",
            "In the manner of", "'Melancholy'",
        )) {
            assertTrue("Studio (1.7): $copy", text("app.js").contains(copy))
        }
        // Timed plays are gone (v1.20): no schedule page, editor or route on the panel.
        assertFalse("no Schedule page", index.contains("Add schedule") || text("app.js").contains("/api/schedules") || text("app.js").contains("scheduleLoad"))
        assertTrue("the tablet's piano sound (1.8), its volume by its one name (1.13)", index.contains("Tablet volume") && text("app.js").contains("tabletVolume"))
        assertTrue("places named as the tablet names them (1.13)", index.contains("Piano › Web panel") && !text("app.js").contains("Remote control"))
        // What plays and what is played from (1.11 — M29): no longer under Now playing (v1.20 — M53: Settings › System
        // says it); the built-in Settings page keeps its two lines, and the piano's pages hide under a MIDI piano.
        assertFalse(index.contains("id=\"instrument-line\"") || index.contains("id=\"keyboard-line\""))
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
        for (id in listOf("settings-tools", "settings-body", "quiet-tools", "quiet-body", "rail-vitals")) assertTrue(id, index.contains("id=\"$id\""))
        assertTrue(index.contains("<section id=\"section-settings\" data-page=\"settings\" hidden>"))
        assertFalse("System is a page of Settings now (v1.20 — M53)", index.contains("section-system"))
        val app = text("app.js")
        assertTrue("each module loaded the first time it is needed", app.contains("import('./settings.js')") && app.contains("import('./system.js')"))
        assertFalse("never at the page's start", Regex("src=\"(settings|system)\\.js\"").containsMatchIn(index))
        assertTrue("the host is one frozen object", app.contains("const host = Object.freeze({"))
        assertTrue("nothing stored: dark", app.contains("return value === 'light' || value === 'system' ? value : 'dark';"))
        // The guests' page and the poster keep their own appearance.
        assertTrue(text("request.html").contains("<html lang=\"en\">") && text("request.html").contains("content=\"light dark\""))
    }

    @Test
    fun `the panel has five places and no stray text, the mark in the tab and the rail, and Quiet times through the seam (v1_20 M53)`() {
        val index = text("index.html")
        val app = text("app.js")
        // The five places, and no others, in the rail and in the phones' bar, in this order; no More sheet.
        val five = listOf("now" to "Now playing", "library" to "Library", "quiet" to "Quiet times", "studio" to "Studio", "settings" to "Settings")
        fun places(from: String, to: String): List<Pair<String, String>> {
            val part = index.substring(index.indexOf(from), index.indexOf(to, index.indexOf(from)))
            return Regex("data-section=\"([a-z]+)\".*?<span>([^<]+)</span>").findAll(part).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }
        assertEquals("the rail", five, places("<div class=\"section-list\">", "<div class=\"rail-foot\""))
        assertEquals("the phones' bar", five, places("<nav class=\"tab-bar glass\"", "</nav>"))
        assertEquals("nothing else names a section", 10, Regex("data-section=\"").findAll(index).count())
        assertFalse("no More sheet", index.contains("more-sheet") || app.contains("tab-more"))
        assertTrue("the script's places are the five", app.contains("const SECTIONS = ['now', 'library', 'quiet', 'studio', 'settings'];"))
        // The addresses of 1.19 open their new homes.
        for (old in listOf("queue", "requests", "channels", "add", "system", "piano", "schedule")) assertTrue("#$old", app.contains("case '$old':"))
        // No stray text: the rail's head is the mark, the name and a dot, with a pill only while something is wrong.
        for (name in listOf("index.html", "app.js", "settings.js", "system.js")) assertFalse("$name: no \"Connected ·\"", text(name).contains("Connected ·"))
        assertTrue(app.contains("'Reconnecting…'") && app.contains("'Piano not connected'"))
        assertEquals("the tablet's address only in the socket's, never on the page", 1, Regex("location\\.host").findAll(app).count())
        // The mark: the app's icon as the tab's icon on the three pages and beside the rail's name, a picture and nothing more.
        val svg = WebAssets.Asset("favicon.svg", WebAssets.SVG)
        assertEquals("image/svg+xml", WebAssets.SVG)
        assertEquals(svg, WebAssets.PANEL["/favicon.svg"])
        assertEquals(svg, WebAssets.PUBLIC["/favicon.svg"])
        for (page in listOf("index.html", "request.html", "poster.html")) {
            assertTrue("$page links the mark, relatively", text(page).contains("<link rel=\"icon\" href=\"favicon.svg\" type=\"image/svg+xml\">"))
        }
        assertTrue("the mark beside the name", index.contains("<img class=\"mark\" src=\"favicon.svg\" alt=\"\""))
        val mark = text("favicon.svg")
        assertTrue("a viewBox", Regex("<svg [^>]*viewBox=\"[0-9 .]+\"").containsMatchIn(mark))
        assertTrue("its own background", mark.contains("<rect ") && mark.contains("fill=\"#0E0E0E\""))
        assertTrue("the launcher's glyph", mark.contains(File("src/main/res/drawable/ic_launcher_foreground.xml").readText().substringAfter("android:pathData=\"").substringBefore("\"")))
        assertFalse("no script", mark.contains("<script", ignoreCase = true) || Regex("\\son[a-z]+=", RegexOption.IGNORE_CASE).containsMatchIn(mark))
        assertFalse("nothing from elsewhere", Regex("(?:href|src)=").containsMatchIn(mark) || mark.replace("http://www.w3.org/2000/svg", "").contains("http"))
        // Quiet times: quiet.js through the modules' seam, loaded the first time its section shows, never at the start.
        assertTrue(app.contains("import('./quiet.js')") && app.contains("quiet: { body: 'quiet-body', tools: 'quiet-tools'"))
        assertFalse("never at the page's start", Regex("(?:src|href)=\"quiet\\.js\"").containsMatchIn(index))
        assertTrue(index.contains("<section id=\"section-quiet\" data-page=\"quiet\" hidden>"))
        // Quiet now (M54's state field, absent on older tablets) and Play anyway.
        assertTrue(app.contains("return !!(quiet && quiet.now && !quiet.overridden);") && app.contains("await post(ROOT + '/api/quiet/override');"))
        // Settings: System its first page (system.js's create through host.system()), Guests with the two switches.
        val settings = text("settings.js")
        assertTrue(app.contains("system: () => loadSystem(),") && settings.contains("module.create(host, system.holder, system.tools)"))
        assertTrue(settings.indexOf("key: 'system'") in 0 until settings.indexOf("key: 'playback'"))
        for (copy in listOf("'Guests can request'", "'Approve requests first'", "webGuests", "webApproveFirst")) assertTrue(copy, settings.contains(copy))
        assertFalse("the guests' switches left the frame", index.contains("guests-open") || app.contains("webGuests"))
        // Motion: every animation the frame adds has a cut under reduced motion; nothing new loops.
        val css = text("style.css")
        val reduced = css.substring(css.lastIndexOf("@media (prefers-reduced-motion: reduce) {"))
        for (cut in listOf(".page > .entering", ".enter", ".now-art.settle", "dialog.sheet[open]", ".toast:not([hidden])", ".tile-play:hover .art { transform: none; }")) {
            assertTrue("reduced motion cuts $cut", reduced.contains(cut))
        }
        val motion = css.substring(css.indexOf("/* ---- Motion (v1.20 — M53)"), css.lastIndexOf("@media (prefers-reduced-motion: reduce) {"))
        assertFalse("nothing new loops", motion.contains("infinite"))
        assertTrue(app.contains("const reducedQuery = window.matchMedia('(prefers-reduced-motion: reduce)');"))
        // No pure black or white anywhere in the pages' own files (the mark's colours are the icon's own, near-black and near-white).
        for (name in WebAssets.NAMES - WebAssets.FONT.name) {
            val body = File(folder, name).readText()
            assertFalse("$name names pure black or white", Regex("#(?:000|fff)(?:000|fff)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(body))
        }
    }

    @Test
    fun `the Settings and System modules are on the list, ask only through the host from its ROOT, and say what the tablet says (v1_18 M47b)`() {
        for (name in listOf("settings.js", "system.js")) {
            assertTrue("$name is on the panel's list", WebAssets.PANEL["/$name"] == WebAssets.Asset(name, WebAssets.JS))
            val js = text(name)
            assertTrue("$name is the module the frame creates", js.contains("export function create(host, body, tools)"))
            for (banned in listOf("fetch(", "WebSocket(", "XMLHttpRequest", "setAttribute('style'", "cssText")) assertFalse("$name holds $banned", js.contains(banned))
            val paths = Regex("['`\"]/api/").findAll(js).map { it.range.first }.toList()
            assertTrue("$name asks the tablet", paths.isNotEmpty())
            for (at in paths) assertEquals("$name: every /api/ path starts from host.ROOT (at $at)", "host.ROOT + ", js.substring(maxOf(0, at - 12), at))
        }
        assertTrue("the rail's foot", text("system.js").contains("export function vitals(host, node)"))
        assertTrue(WebAssets.PANEL["/system.css"] == WebAssets.Asset("system.css", WebAssets.CSS))
        // Tokens only: no pure black or white, red the live dot's alone (style.css), the arc's ease cut under reduced motion.
        val css = text("system.css").replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace("white-space", "")
        for (forbidden in listOf("#000", "#fff", "#FFF", "white", "black", "rgb(0", "rgb(255", "var(--live)", "!important")) {
            assertFalse("system.css names $forbidden", css.contains(forbidden))
        }
        assertTrue(css.contains("transition: stroke-dasharray 480ms") && css.contains("@media (prefers-reduced-motion: reduce) {\n  .system-page .dial .fill { transition: none; }"))
        // The Playback page in the tablet's own words: its rows' names, their notes, the line under Velocity.
        val settings = text("settings.js")
        val rows = listOf(
            PageRows.DEFAULT_TEMPO, PageRows.TRANSPOSE, PageRows.VELOCITY, PageRows.DYNAMIC_RANGE, PageRows.QUIETEST_NOTE, PageRows.EXPRESSION,
            PageRows.RESTRIKE, PageRows.PAUSE, PageRows.FOLD, PageRows.SKIP_DRUMS, PageRows.ALBUM_BACKDROP,
        )
        for (row in rows) assertTrue(row.label, settings.contains("'${row.label}'"))
        for (copy in listOf(
            SettingNotes.DEFAULT_TEMPO, SettingNotes.VELOCITY, SettingNotes.DYNAMIC_RANGE, SettingNotes.QUIETEST_NOTE, SettingNotes.EXPRESSION,
            SettingNotes.RESTRIKE, SettingNotes.FOLD, SettingNotes.SKIP_DRUMS, SettingNotes.ALBUM_BACKDROP, PlaybackCopy.FULL_POWER_ON, PlaybackCopy.FULL_POWER_OFF,
        )) {
            assertTrue(copy, settings.contains(copy))
        }
        assertTrue("a MIDI piano hides the piano's pages", settings.contains("belong to Steven Piano and are hidden while"))
        // What newer piano firmware will fill in (firmware/docs/BLE_DIAG.md) is said, never left blank.
        assertTrue(text("system.js").contains("'Needs newer firmware'"))
    }

    @Test
    fun `the cover picker is a module on the list, asks only through the host from its ROOT, and shows only the tablet's data pictures (v1_18 M48)`() {
        assertTrue("covers.js is on the panel's list", WebAssets.PANEL["/covers.js"] == WebAssets.Asset("covers.js", WebAssets.JS))
        val js = text("covers.js")
        assertTrue("the module the piece menu opens", js.contains("export function open(host, piece)"))
        for (banned in listOf("fetch(", "WebSocket(", "XMLHttpRequest", "setAttribute('style'", "cssText")) assertFalse("covers.js holds $banned", js.contains(banned))
        val paths = Regex("['`\"]/api/").findAll(js).map { it.range.first }.toList()
        assertEquals("its three routes", 3, paths.size)
        for (at in paths) assertEquals("covers.js: every /api/ path starts from host.ROOT (at $at)", "host.ROOT + ", js.substring(maxOf(0, at - 12), at))
        for (copy in listOf(
            "'Find a cover'", "'Searching…'", "\"Nothing found. Try the album's name or the artist's.\"", "'Apple asked to slow down. Try again in a minute.'",
            "\"Remove this piece's cover\"", "'Cover changed.'",
        )) {
            assertTrue(copy, js.contains(copy))
        }
        assertTrue("a picture only as a data: JPEG or PNG", js.contains("/^data:image\\/(?:jpeg|png);base64,"))
        val app = text("app.js")
        assertTrue("loaded the first time a piece's menu asks for it", app.contains("import('./covers.js').then((m) => m.open(host, piece))"))
        assertFalse("never at the page's start", text("index.html").contains("covers.js"))
        assertTrue("offered for the library's pieces only", app.contains("piece.genre === 'classical' || piece.genre === 'modern' ? [['Find a cover…'"))
    }

    // ---- Quiet times (v1.20 — M54) ------------------------------------------------------------------------------------

    @Test
    fun `the Quiet times module is on the list, asks only through the host from its ROOT, links its own styles, and says what the tablet says (v1_20 M54)`() {
        assertEquals(WebAssets.Asset("quiet.js", WebAssets.JS), WebAssets.PANEL["/quiet.js"])
        assertEquals(WebAssets.Asset("quiet.css", WebAssets.CSS), WebAssets.PANEL["/quiet.css"])
        val js = text("quiet.js")
        assertTrue("the module the frame creates", js.contains("export function create(host, body, tools)"))
        for (banned in listOf("fetch(", "WebSocket(", "XMLHttpRequest", "setAttribute('style'", "cssText", "innerHTML")) assertFalse("quiet.js holds $banned", js.contains(banned))
        val paths = Regex("['`\"]/api/").findAll(js).map { it.range.first }.toList()
        assertEquals("its two routes and the override", 3, paths.size)
        for (at in paths) assertEquals("quiet.js: every /api/ path starts from host.ROOT (at $at)", "host.ROOT + ", js.substring(maxOf(0, at - 12), at))
        assertTrue("its styles, linked once by the module", js.contains("href: 'quiet.css'") && js.contains("document.head.append("))
        // The tablet's limits and words (schedule/QuietTimes.kt, QuietCopy.kt), so both editors say the same.
        for ((name, value) in listOf("MAX_SECTIONS" to QuietTimes.MAX_SECTIONS, "MAX_BLOCKS" to QuietTimes.MAX_BLOCKS, "MAX_NAME" to QuietTimes.MAX_NAME, "GAP_MINUTES" to QuietTimes.GAP_MINUTES)) {
            assertTrue("$name = $value", js.contains("const $name = $value;"))
        }
        for (word in listOf(
            QuietTimes.TOO_MANY_SECTIONS, QuietTimes.NO_NAME, QuietTimes.LONG_NAME, QuietTimes.SAME_NAME, QuietTimes.NO_DAY, QuietTimes.NO_BLOCK,
            QuietTimes.TOO_MANY_BLOCKS, QuietTimes.NOT_A_TIME, QuietTimes.SAME_TIMES, QuietCopy.NOTE, QuietCopy.PLAY_ANYWAY,
        )) {
            assertTrue(word, js.contains(word))
        }
        assertTrue("the week's window", js.contains("const WEEK_FROM = 6 * 60;") && js.contains("const WEEK_TO = 22 * 60;"))
        // Tokens only: no pure black or white, red the live dot's alone (style.css), nothing forced.
        val css = text("quiet.css").replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace("white-space", "")
        for (forbidden in listOf("#000", "#fff", "#FFF", "white", "black", "rgb(0", "rgb(255", "var(--live)", "!important", "url(")) {
            assertFalse("quiet.css names $forbidden", css.contains(forbidden))
        }
        assertTrue("the blocks hatched with tokens", css.contains("repeating-linear-gradient(135deg, color-mix(in srgb, var(--primary)"))
        // The guests' page: the resting line from the catalogue's quiet, its words the design's.
        val request = text("request.js")
        assertTrue(request.contains("resting(data.quiet);"))
        assertTrue(request.contains("`The piano is resting until \${clock(quiet.until)}. Your request will wait until then.`"))
    }
}
