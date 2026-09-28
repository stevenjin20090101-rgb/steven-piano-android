// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.navigation.NavType
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.piano.PianoPage

/**
 * The four destinations, in bar and rail order. They navigate; they never act. Library keeps the
 * books, Now playing the roll, Keys takes the keyboard, and Piano (the connection and settings)
 * the sliders. Piano is a nested graph: its hub and its pages ([PianoRoutes]) all belong to it.
 */
enum class Route(val path: String, val label: String, @param:DrawableRes val icon: Int) {
    Library("library", "Library", R.drawable.ic_tab_library),
    NowPlaying("now-playing", "Now playing", R.drawable.ic_stat_piano),
    Keys("keys", "Keys", R.drawable.ic_tab_keys),
    Piano("piano", "Piano", R.drawable.ic_tab_piano),
    ;

    companion object {
        /** The destination [path] is, or belongs to ("piano/feel" and "piano/hub" are the Piano tab's). */
        fun of(path: String?): Route? = entries.firstOrNull { path == it.path || path?.startsWith(it.path + "/") == true }
    }
}

/**
 * The Piano tab's pages (DESIGN.md › v1.5), each opened from a row of the hub: `piano/{key}` on
 * phones, beside the hub on wide screens. [piano] is the piano page it shows, for the four whose rows
 * come from the piano's settings table. Remote control (the web panel) came in v1.5.1, Schedule in
 * v1.5.2; later runs add Kiosk and Studio.
 */
enum class SettingsPage(val key: String, val title: String, val piano: PianoPage?) {
    Feel("feel", "Feel", PianoPage.Feel),
    Lighting("lighting", "Lighting", PianoPage.Lighting),
    Pedal("pedal", "Pedal", PianoPage.Pedal),
    Firmware("firmware", "Firmware and status", PianoPage.Firmware),
    Playback("playback", "Playback", null),
    Display("display", "Display", null),
    Schedule("schedule", "Schedule", null),
    Remote("remote", "Remote control", null),
    ;

    companion object {
        fun of(key: String?): SettingsPage? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The Piano tab's routes: a nested graph at [Route.Piano]'s path whose start is the hub, and one
 * page route for every [SettingsPage], its key typed by [PageType]. [CUT] marks a page put back
 * after the window narrowed (a phone turned upright with a page open beside the hub): it appears
 * without the push.
 */
object PianoRoutes {
    const val HUB = "piano/hub"
    const val PAGE_KEY = "page"
    const val CUT = "cut"
    const val PAGE = "piano/{$PAGE_KEY}?$CUT={$CUT}"

    fun page(page: SettingsPage, cut: Boolean = false): String = "piano/${page.key}" + if (cut) "?$CUT=true" else ""

    /** Whether [route] (a destination's route pattern) is a page's, not the hub's. */
    fun isPage(route: String?): Boolean = route == PAGE

    /**
     * A page's key in its route: one of [SettingsPage]'s keys and nothing else. Navigation finds a
     * graph's start (and a route to pop to) by the first destination whose pattern matches, so
     * with a plain string `piano/{page}` would also match `piano/hub` and open a page named "hub";
     * a key that is not a page fails to parse here, so it matches nothing.
     */
    object PageType : NavType<SettingsPage>(isNullableAllowed = false) {
        override val name: String get() = "SettingsPage"

        override fun get(bundle: Bundle, key: String): SettingsPage? = SettingsPage.of(bundle.getString(key))

        override fun put(bundle: Bundle, key: String, value: SettingsPage) = bundle.putString(key, value.key)

        override fun parseValue(value: String): SettingsPage =
            SettingsPage.of(value) ?: throw IllegalArgumentException("Not a page of the Piano tab: $value")

        override fun serializeAsValue(value: SettingsPage): String = value.key
    }
}
