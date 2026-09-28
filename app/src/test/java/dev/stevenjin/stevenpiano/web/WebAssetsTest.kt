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
        assertTrue(text("poster.html").contains("data-theme=\"light\""))
    }
}
