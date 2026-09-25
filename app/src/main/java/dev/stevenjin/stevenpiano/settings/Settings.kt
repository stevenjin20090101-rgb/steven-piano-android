// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.player.PlaybackLimits
import dev.stevenjin.stevenpiano.player.RepeatMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * How Now playing draws notes: the pianola roll (default), Synthesia-style falling notes, or the
 * staff. On wide screens the staff has its own place ([WideLayout]) and this chooses the roll's
 * style, Staff reading as the paper roll there.
 */
enum class NoteDisplay {
    PAPER_ROLL,
    FALLING,
    STAFF,
    ;

    /** The roll style this display draws the notes in: Staff has none of its own, so it takes the paper roll. */
    val rollStyle: NoteDisplay get() = if (this == STAFF) PAPER_ROLL else this
}

/** What Now playing shows on medium and expanded widths. */
enum class WideLayout { STAFF_AND_NOTES, NOTES_ONLY, STAFF_ONLY }

/**
 * The Piano tab's preferences, plus the last piano connected, where the Keys screen was, the
 * queue's two modes, and how artwork looks and arrives.
 */
data class PianoSettings(
    val autoConnect: Boolean = true,
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val noteDisplay: NoteDisplay = NoteDisplay.PAPER_ROLL,
    val defaultTempoPct: Int = 100,
    val transpose: Int = 0,
    val velocityPct: Int = 100,
    val foldOutOfRange: Boolean = true,
    val skipDrumChannel: Boolean = true,
    val wideLayout: WideLayout = WideLayout.STAFF_AND_NOTES,
    /** The leftmost key the Keys screen shows when it scrolls (C3 by default). */
    val keysViewportStart: Int = DEFAULT_KEYS_VIEWPORT_START,
    /** The transport's Shuffle, remembered across launches. */
    val shuffle: Boolean = false,
    /** The transport's Repeat, remembered across launches. */
    val repeat: RepeatMode = RepeatMode.OFF,
    /** Portraits drawn in black and white (the rest of the interface is monochrome either way). */
    val artworkMonochrome: Boolean = false,
    /** Composers' portraits and notes fetched from Wikipedia after an import, and when the app opens. */
    val fetchArtworkAutomatically: Boolean = true,
) {
    companion object {
        const val DEFAULT_KEYS_VIEWPORT_START = 48
    }
}

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Preferences in DataStore: one [settings] flow, one setter each. Out-of-range values are clamped. */
class SettingsRepository(private val store: DataStore<Preferences>) {
    val settings: Flow<PianoSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun setAutoConnect(on: Boolean) = edit { it[AUTO_CONNECT] = on }

    suspend fun rememberDevice(address: String, name: String) = edit {
        it[LAST_DEVICE_ADDRESS] = address
        it[LAST_DEVICE_NAME] = name
    }

    suspend fun setNoteDisplay(display: NoteDisplay) = edit { it[NOTE_DISPLAY] = display.name }

    suspend fun setDefaultTempo(pct: Int) = edit { it[DEFAULT_TEMPO_PCT] = pct.coerceIn(PlaybackLimits.TempoPct) }

    suspend fun setTranspose(semitones: Int) = edit { it[TRANSPOSE] = semitones.coerceIn(PlaybackLimits.Transpose) }

    suspend fun setVelocity(pct: Int) = edit { it[VELOCITY_PCT] = pct.coerceIn(PlaybackLimits.VelocityPct) }

    suspend fun setFoldOutOfRange(on: Boolean) = edit { it[FOLD_OUT_OF_RANGE] = on }

    suspend fun setSkipDrumChannel(on: Boolean) = edit { it[SKIP_DRUM_CHANNEL] = on }

    suspend fun setWideLayout(layout: WideLayout) = edit { it[WIDE_LAYOUT] = layout.name }

    suspend fun setKeysViewportStart(key: Int) = edit { it[KEYS_VIEWPORT_START] = key.coerceIn(KeyMap.LOWEST, KeyMap.HIGHEST) }

    suspend fun setShuffle(on: Boolean) = edit { it[SHUFFLE] = on }

    suspend fun setRepeat(mode: RepeatMode) = edit { it[REPEAT] = mode.name }

    suspend fun setArtworkMonochrome(on: Boolean) = edit { it[ARTWORK_MONOCHROME] = on }

    suspend fun setFetchArtworkAutomatically(on: Boolean) = edit { it[FETCH_ARTWORK_AUTOMATICALLY] = on }

    private suspend fun edit(change: (MutablePreferences) -> Unit) {
        store.edit(change)
    }

    private fun Preferences.toSettings(): PianoSettings {
        val defaults = PianoSettings()
        return PianoSettings(
            autoConnect = this[AUTO_CONNECT] ?: defaults.autoConnect,
            lastDeviceAddress = this[LAST_DEVICE_ADDRESS],
            lastDeviceName = this[LAST_DEVICE_NAME],
            noteDisplay = NoteDisplay.entries.firstOrNull { it.name == this[NOTE_DISPLAY] } ?: defaults.noteDisplay,
            defaultTempoPct = (this[DEFAULT_TEMPO_PCT] ?: defaults.defaultTempoPct).coerceIn(PlaybackLimits.TempoPct),
            transpose = (this[TRANSPOSE] ?: defaults.transpose).coerceIn(PlaybackLimits.Transpose),
            velocityPct = (this[VELOCITY_PCT] ?: defaults.velocityPct).coerceIn(PlaybackLimits.VelocityPct),
            foldOutOfRange = this[FOLD_OUT_OF_RANGE] ?: defaults.foldOutOfRange,
            skipDrumChannel = this[SKIP_DRUM_CHANNEL] ?: defaults.skipDrumChannel,
            wideLayout = WideLayout.entries.firstOrNull { it.name == this[WIDE_LAYOUT] } ?: defaults.wideLayout,
            keysViewportStart = (this[KEYS_VIEWPORT_START] ?: defaults.keysViewportStart).coerceIn(KeyMap.LOWEST, KeyMap.HIGHEST),
            shuffle = this[SHUFFLE] ?: defaults.shuffle,
            repeat = RepeatMode.entries.firstOrNull { it.name == this[REPEAT] } ?: defaults.repeat,
            artworkMonochrome = this[ARTWORK_MONOCHROME] ?: defaults.artworkMonochrome,
            fetchArtworkAutomatically = this[FETCH_ARTWORK_AUTOMATICALLY] ?: defaults.fetchArtworkAutomatically,
        )
    }

    private companion object {
        val AUTO_CONNECT = booleanPreferencesKey("autoConnect")
        val LAST_DEVICE_ADDRESS = stringPreferencesKey("lastDeviceAddress")
        val LAST_DEVICE_NAME = stringPreferencesKey("lastDeviceName")
        val NOTE_DISPLAY = stringPreferencesKey("noteDisplay")
        val DEFAULT_TEMPO_PCT = intPreferencesKey("defaultTempoPct")
        val TRANSPOSE = intPreferencesKey("transpose")
        val VELOCITY_PCT = intPreferencesKey("velocityPct")
        val FOLD_OUT_OF_RANGE = booleanPreferencesKey("foldOutOfRange")
        val SKIP_DRUM_CHANNEL = booleanPreferencesKey("skipDrumChannel")
        val WIDE_LAYOUT = stringPreferencesKey("wideLayout")
        val KEYS_VIEWPORT_START = intPreferencesKey("keysViewportStart")
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val REPEAT = stringPreferencesKey("repeat")
        val ARTWORK_MONOCHROME = booleanPreferencesKey("artworkMonochrome")
        val FETCH_ARTWORK_AUTOMATICALLY = booleanPreferencesKey("fetchArtworkAutomatically")
    }
}
