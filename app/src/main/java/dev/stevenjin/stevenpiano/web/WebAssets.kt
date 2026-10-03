// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import dev.stevenjin.stevenpiano.R
import java.io.IOException

/** Where the panel's files are read from: the app's assets, or a map in the tests. */
fun interface AssetSource {
    /** The file [name] (one of [WebAssets]' names, nothing else), or null when it cannot be read. */
    fun read(name: String): ByteArray?
}

/** The app's own `assets/web/` folder, and (v1.13 — M32) the score's font from `res/font`, the one copy the app carries. */
class AndroidAssets(private val context: Context) : AssetSource {
    // openRawResource reads any file resource's bytes as stored; a font's is the file itself, unchanged.
    @SuppressLint("ResourceType")
    override fun read(name: String): ByteArray? = try {
        if (name == WebAssets.FONT.name) {
            context.resources.openRawResource(R.font.bravura).use { it.readBytes() }
        } else {
            context.assets.open("$FOLDER/$name").use { it.readBytes() }
        }
    } catch (e: IOException) {
        null
    } catch (e: Resources.NotFoundException) {
        null
    }

    companion object {
        const val FOLDER = "web"
    }
}

/**
 * The panel's static files, served from an allow-list by exact request path, never by resolving the
 * path itself: there is no directory listing, no `..`, and no file the list does not name. [PANEL]
 * is the panel's page and script (the Tailscale listener, or Wi-Fi with Panel on Wi-Fi too);
 * [PUBLIC] is what guests need, on every listener. Both need no session: the page shows the PIN gate,
 * and neither file holds anything secret.
 */
object WebAssets {
    /** A file of `assets/web/` and the type it is sent as. */
    data class Asset(val name: String, val contentType: String)

    const val HTML = "text/html; charset=utf-8"
    const val JS = "text/javascript; charset=utf-8"
    const val CSS = "text/css; charset=utf-8"

    val PANEL: Map<String, Asset> = mapOf(
        "/" to Asset("index.html", HTML),
        "/app.js" to Asset("app.js", JS),
        // The Settings and System pages (v1.18 — M47b): modules the page imports the first time each shows.
        "/settings.js" to Asset("settings.js", JS),
        "/system.js" to Asset("system.js", JS),
        // The views of Now playing (v1.13 — M32): modules the page imports the first time it shows them.
        "/clock.js" to Asset("clock.js", JS),
        "/wire.js" to Asset("wire.js", JS),
        "/roll.js" to Asset("roll.js", JS),
        "/score.js" to Asset("score.js", JS),
        "/views.js" to Asset("views.js", JS),
        // The Settings and System pages' styles (v1.18 — M47): linked by the page, filled by their modules' run.
        "/system.css" to Asset("system.css", CSS),
        // The cover picker (v1.18 — M48): a module the page imports the first time a piece's menu asks for it.
        "/covers.js" to Asset("covers.js", JS),
    )

    val PUBLIC: Map<String, Asset> = mapOf(
        "/style.css" to Asset("style.css", CSS),
        "/request" to Asset("request.html", HTML),
        "/request.js" to Asset("request.js", JS),
    )

    /** The poster's template; its route fills in the QR and the address ([Poster.page]). */
    val POSTER = Asset("poster.html", HTML)

    /**
     * Bravura, the score's font (v1.13 — M32): sent unmodified (its licence reserves the name, so it is never subset,
     * converted or renamed) by a route that needs a session, `/api/font/bravura.otf?v=` [FONT_VERSION], cached for a
     * year. It is read from `res/font`, not `assets/web`.
     */
    val FONT = Asset("bravura.otf", "font/otf")

    /** The first eight hex digits of the font's SHA-256: its address's version (the panel's `score.js` names the same). */
    const val FONT_VERSION = "cdf0f893"

    /** Every file the server may ever read. */
    val NAMES: Set<String> = (PANEL.values + PUBLIC.values + POSTER + FONT).map { it.name }.toSet()
}
