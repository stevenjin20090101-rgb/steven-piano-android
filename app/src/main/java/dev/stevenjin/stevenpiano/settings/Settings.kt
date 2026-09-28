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
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.player.PlaybackLimits
import dev.stevenjin.stevenpiano.player.RepeatMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * How Now playing draws notes: the pianola roll (default), Synthesia-style falling notes, or the
 * score (saved as STAFF, its v1.1 name, so the choice carries over). On wide screens the score has
 * its own place ([WideLayout]) and this chooses the roll's style, the score reading as the paper
 * roll there.
 */
enum class NoteDisplay {
    PAPER_ROLL,
    FALLING,
    STAFF,
    ;

    /** The roll style this display draws the notes in: the score has none of its own, so it takes the paper roll. */
    val rollStyle: NoteDisplay get() = if (this == STAFF) PAPER_ROLL else this
}

/** What Now playing shows on medium and expanded widths: the score and the notes, or either alone (v1.1 names, kept). */
enum class WideLayout { STAFF_AND_NOTES, NOTES_ONLY, STAFF_ONLY }

/**
 * The app's appearance (Piano › Display › APPEARANCE, DESIGN.md › v1.5 — M17): as the system sets
 * it (the default), or the paper roll or the camera body whatever the system says.
 */
enum class Appearance {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** Whether the app draws dark, when the system's own appearance is [systemDark]. */
    fun dark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

/** Display mode's canvas: true black (the default), or the app's own surface, ink or paper as the app appears. */
enum class StandbyCanvas { BLACK, INK }

/**
 * The Piano tab's preferences, plus the last piano connected, where the Keys screen was, the
 * queue's two modes, how artwork looks and arrives, what the waterfall and the score show
 * beside the notes (fingering, chord names, the hands in colour), whether the app looks for
 * its own updates, the pause before each piece, the channels' volumes, the app's appearance and
 * display mode.
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
    /** Suggested fingering: numerals on the score's heads and in the waterfall's bars. */
    val fingering: Boolean = true,
    /** Chord names above the score and at the waterfall's left edge. */
    val chordNames: Boolean = true,
    /** The two hands in two colours on the waterfall and the keyboard strip (monochrome when off). */
    val handColours: Boolean = false,
    /** A newer release looked for on launch and once a day while the app is open (Check now works either way). */
    val checkForUpdates: Boolean = true,
    /** Silence before every piece starts, in milliseconds, 0-5000 (Piano › Playback, "Pause before each piece"). */
    val preRollMs: Int = DEFAULT_PRE_ROLL_MS,
    /** Each channel's volume as the person set it, 0-100 %, by channel key; a channel not here plays at [DEFAULT_CHANNEL_VOLUME]. */
    val channelVolumes: Map<String, Int> = emptyMap(),
    /** Display mode after a minute without a touch while a piece is loaded (Piano › Display › STANDBY). */
    val displayModeAfterMinute: Boolean = false,
    /** Light, dark, or as the system says (Piano › Display › APPEARANCE). */
    val appearance: Appearance = Appearance.SYSTEM,
    /** Display mode's canvas: black, or the app's own (Piano › Display › STANDBY). */
    val standbyCanvas: StandbyCanvas = StandbyCanvas.BLACK,
) {
    /** Channel [key]'s volume: the person's, else 70 %. */
    fun channelVolume(key: String): Int = channelVolumes[key] ?: DEFAULT_CHANNEL_VOLUME

    companion object {
        /** A channel's volume until the person sets it (DESIGN.md › v1.5 — M17). */
        const val DEFAULT_CHANNEL_VOLUME = 70

        const val DEFAULT_KEYS_VIEWPORT_START = 48

        /** Two seconds of silence before every piece (Steven's choice, DESIGN.md › v1.5 — M16). */
        const val DEFAULT_PRE_ROLL_MS = 2_000
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

    suspend fun setFingering(on: Boolean) = edit { it[FINGERING] = on }

    suspend fun setChordNames(on: Boolean) = edit { it[CHORD_NAMES] = on }

    suspend fun setHandColours(on: Boolean) = edit { it[HAND_COLOURS] = on }

    suspend fun setCheckForUpdates(on: Boolean) = edit { it[CHECK_FOR_UPDATES] = on }

    suspend fun setPreRoll(ms: Int) = edit { it[PRE_ROLL_MS] = ms.coerceIn(PlaybackLimits.PreRollMs) }

    suspend fun setDisplayModeAfterMinute(on: Boolean) = edit { it[DISPLAY_MODE_AFTER_MINUTE] = on }

    suspend fun setAppearance(appearance: Appearance) = edit { it[APPEARANCE] = appearance.name }

    suspend fun setStandbyCanvas(canvas: StandbyCanvas) = edit { it[STANDBY_CANVAS] = canvas.name }

    /** Channel [key]'s volume, 0-100 %, kept with the others as one small JSON object. */
    suspend fun setChannelVolume(key: String, pct: Int) = edit {
        it[CHANNEL_VOLUMES] = ChannelVolumesJson.write(ChannelVolumesJson.read(it[CHANNEL_VOLUMES]) + (key to pct.coerceIn(0, 100)))
    }

    /** Whether the one-off repair of over-long library text (`TextRepair`) has run. Housekeeping, not a preference. */
    suspend fun textRepairDone(): Boolean =
        store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }.first()[TEXT_REPAIR_DONE] == true

    suspend fun markTextRepairDone() = edit { it[TEXT_REPAIR_DONE] = true }

    /**
     * The newest crash report the person has answered (shared or dismissed), as its epoch ms; 0
     * when none. A newer report brings back the Library's "The app crashed last time" banner.
     * Housekeeping, not a preference.
     */
    val crashNoticeSeenAt: Flow<Long> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[CRASH_NOTICE_SEEN_AT] ?: 0L }
        .distinctUntilChanged()

    suspend fun markCrashNoticeSeen(reportAt: Long) = edit { if ((it[CRASH_NOTICE_SEEN_AT] ?: 0L) < reportAt) it[CRASH_NOTICE_SEEN_AT] = reportAt }

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
            fingering = this[FINGERING] ?: defaults.fingering,
            chordNames = this[CHORD_NAMES] ?: defaults.chordNames,
            handColours = this[HAND_COLOURS] ?: defaults.handColours,
            checkForUpdates = this[CHECK_FOR_UPDATES] ?: defaults.checkForUpdates,
            preRollMs = (this[PRE_ROLL_MS] ?: defaults.preRollMs).coerceIn(PlaybackLimits.PreRollMs),
            channelVolumes = ChannelVolumesJson.read(this[CHANNEL_VOLUMES]),
            displayModeAfterMinute = this[DISPLAY_MODE_AFTER_MINUTE] ?: defaults.displayModeAfterMinute,
            appearance = Appearance.entries.firstOrNull { it.name == this[APPEARANCE] } ?: defaults.appearance,
            standbyCanvas = StandbyCanvas.entries.firstOrNull { it.name == this[STANDBY_CANVAS] } ?: defaults.standbyCanvas,
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
        val FINGERING = booleanPreferencesKey("fingering")
        val CHORD_NAMES = booleanPreferencesKey("chordNames")
        val HAND_COLOURS = booleanPreferencesKey("handColours")
        val CHECK_FOR_UPDATES = booleanPreferencesKey("checkForUpdates")
        val PRE_ROLL_MS = intPreferencesKey("preRollMs")
        val CHANNEL_VOLUMES = stringPreferencesKey("channelVolumes")
        val DISPLAY_MODE_AFTER_MINUTE = booleanPreferencesKey("displayModeAfterMinute")
        val APPEARANCE = stringPreferencesKey("appearance")
        val STANDBY_CANVAS = stringPreferencesKey("standbyCanvas")
        val TEXT_REPAIR_DONE = booleanPreferencesKey("libraryTextRepairDone")
        val CRASH_NOTICE_SEEN_AT = longPreferencesKey("crashNoticeSeenAt")
    }
}

/** `channelVolumes` as DataStore keeps it: `{"calm":60,"epic":80}`. Anything unreadable reads as none; values are held to 0-100. */
internal object ChannelVolumesJson {
    fun read(text: String?): Map<String, Int> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(text)
            json.keys().asSequence().mapNotNull { key -> (json.opt(key) as? Number)?.let { key to it.toInt().coerceIn(0, 100) } }.toMap()
        } catch (e: JSONException) {
            emptyMap()
        }
    }

    fun write(volumes: Map<String, Int>): String = JSONObject(volumes.toSortedMap() as Map<*, *>).toString()
}
