// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.web.WebChannel
import dev.stevenjin.stevenpiano.web.WebState
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The tablet's `status` for the relay and the console (BUILD_SPEC.md › v1.10 — M26), built from
 * what the web panel already shows: the app's version and build, the piano's firmware (its report's
 * `fw`), the piano link's state, the player (playing, paused or stopped; the piece's title and
 * composer; where it is and how long it lasts; the channel), whether guests may request and wait for
 * approval, whether the panel is on and where on the tablet's own networks, the library's size and
 * the version of Steven's library pack loaded (v1.10 — M27; 0: none yet), and the channels' keys and names for
 * the console's list. Nothing that names the device: no Bluetooth address, no piano name, no serial,
 * no Android id. Texts are cut to [MAX_TEXT] characters.
 */
object RelayStatus {
    const val MAX_TEXT = 200
    const val MAX_CHANNELS = 32

    /** [report] as the web service sends it: whether the panel is on and the library pack loaded, from [settings]. */
    fun report(
        appVersion: String,
        appCode: Int,
        state: WebState,
        settings: PianoSettings,
        panelHost: String?,
        libraryPieces: Int?,
        channels: List<WebChannel>,
        at: Long,
    ): JSONObject = report(appVersion, appCode, state, settings.webEnabled, panelHost, libraryPieces, settings.libraryPackVersion, channels, at)

    fun report(
        appVersion: String,
        appCode: Int,
        state: WebState,
        webEnabled: Boolean,
        panelHost: String?,
        libraryPieces: Int?,
        pack: Int?,
        channels: List<WebChannel>,
        at: Long,
    ): JSONObject {
        val player = state.player
        val piece = player.piece
        val playing = JSONObject()
            .put("status", player.status.name.lowercase(Locale.ROOT))
            .put("title", piece?.title?.take(MAX_TEXT) ?: JSONObject.NULL)
            .put("composer", piece?.composer?.takeIf { it.isNotBlank() }?.take(MAX_TEXT) ?: JSONObject.NULL)
            .put("positionMs", if (piece == null) 0L else player.positionMs.coerceAtLeast(0L))
            .put("durationMs", piece?.durationMs ?: 0L)
            .put("channel", player.channel?.let { JSONObject().put("key", it.key).put("name", it.name.take(MAX_TEXT)) } ?: JSONObject.NULL)
        return JSONObject()
            .put("app", JSONObject().put("version", appVersion).put("code", appCode))
            .put("firmware", state.piano.facts["fw"]?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: JSONObject.NULL)
            .put("link", state.link.state)
            .put("player", playing)
            .put("guests", JSONObject().put("open", state.guests.open).put("approveFirst", state.guests.approveFirst))
            .put("panel", JSONObject().put("web", webEnabled).put("host", panelHost ?: JSONObject.NULL))
            .put("library", JSONObject().put("pieces", libraryPieces ?: JSONObject.NULL).put("pack", pack ?: JSONObject.NULL))
            .put("channels", JSONArray().apply { channels.take(MAX_CHANNELS).forEach { put(JSONObject().put("key", it.key).put("name", it.name.take(MAX_TEXT))) } })
            .put("at", at)
    }
}
