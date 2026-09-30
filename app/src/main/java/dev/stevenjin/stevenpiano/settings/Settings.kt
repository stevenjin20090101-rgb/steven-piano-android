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
import dev.stevenjin.stevenpiano.audio.Sampler
import dev.stevenjin.stevenpiano.audio.TabletSoundMode
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
 * What the resting screen shows with a piece loaded (DESIGN.md › v1.7.1): the piece's art with its title,
 * composer and a few lines about it (the default), or v1.5's paper roll over its keyboard.
 */
enum class StandbyShows { ART_AND_NOTES, PAPER_ROLL }

/**
 * The Piano tab's preferences, plus the last piano connected, where the Keys screen was, the
 * queue's two modes, how artwork looks and arrives, what the waterfall and the score show
 * beside the notes (fingering, chord names, the hands in colour), whether the app looks for
 * its own updates, the pause before each piece, the channels' volumes, the app's appearance and
 * display mode, the web panel (Piano › Remote control) and kiosk mode (Piano › Kiosk). Of the
 * panel's PIN only [webPinSet] is here, and of the kiosk's only [kioskPinSet]: their salts and
 * hashes are read on their own ([SettingsRepository.webPin], [SettingsRepository.kioskPin]), so
 * nothing that passes these settings around (the screens, Share diagnostics) ever holds them. So
 * with Steven Piano Cloud (v1.10 — M26): only [cloudSecretSet] is here, the sealed secret is read
 * on its own ([SettingsRepository.cloudSecret]).
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
    /** What the resting screen shows: the art and notes, or the paper roll (Piano › Display › STANDBY). */
    val standbyShows: StandbyShows = StandbyShows.ART_AND_NOTES,
    /** The web panel is on (Piano › Remote control › Web control); it can be only once a PIN is set. */
    val webEnabled: Boolean = false,
    /** Guests may ask for pieces from the request page (Guests can request). */
    val webGuests: Boolean = false,
    /** A guest's request waits for the person's Approve (Approve requests first); off, it joins Up next at once. */
    val webApproveFirst: Boolean = true,
    /** The whole panel on the Wi-Fi address too, not only the request page (Panel on Wi-Fi too): the PIN then travels unencrypted. */
    val webOnWifi: Boolean = false,
    /** A name the panel also answers to (a tailnet name such as "piano-tablet"), besides its address; null: none. */
    val webHostName: String? = null,
    /** Whether a panel PIN is set. */
    val webPinSet: Boolean = false,
    /** Kiosk mode is on (Piano › Kiosk): the screen locked to the app, which is the home screen; only as device owner, with a PIN. */
    val kioskEnabled: Boolean = false,
    /** Whether a kiosk PIN is set. */
    val kioskPinSet: Boolean = false,
    /** When the tablet plays the piano sound itself (Piano › Playback › TABLET SOUND, v1.8 — M25). */
    val tabletSound: TabletSoundMode = TabletSoundMode.WHEN_NOT_CONNECTED,
    /** The tablet's piano sound's volume, 0–100 % (Now playing's speaker, the Playback page, the web panel). */
    val tabletVolume: Int = Sampler.DEFAULT_VOLUME,
    /** Remote access over the internet (Piano › Remote control › CLOUD, v1.10 — M26): the tablet keeps its connection to the relay. */
    val cloudEnabled: Boolean = false,
    /** The relay's address as the person typed it for enrolling ("steven-piano-relay.you.workers.dev"), remembered; null: never typed. */
    val cloudHost: String? = null,
    /** This tablet's piano on the relay (12 letters of base32), once enrolled. */
    val cloudPianoId: String? = null,
    /** Whether a sealed relay secret is kept (whether it still opens is the relay client's to find). */
    val cloudSecretSet: Boolean = false,
) {
    /** Channel [key]'s volume: the person's, else 70 %. */
    fun channelVolume(key: String): Int = channelVolumes[key] ?: DEFAULT_CHANNEL_VOLUME

    /** Enrolled with a relay: an address, a piano's id and a secret kept. */
    val cloudEnrolled: Boolean get() = cloudHost != null && cloudPianoId != null && cloudSecretSet

    companion object {
        /** A channel's volume until the person sets it (DESIGN.md › v1.5 — M17). */
        const val DEFAULT_CHANNEL_VOLUME = 70

        const val DEFAULT_KEYS_VIEWPORT_START = 48

        /** Two seconds of silence before every piece (Steven's choice, DESIGN.md › v1.5 — M16). */
        const val DEFAULT_PRE_ROLL_MS = 2_000
    }
}

/**
 * A PIN as the settings keep it, the panel's or the kiosk's (salt and PBKDF2 hash, base64; see
 * `web.PinHash`). Never the PIN; [toString] prints neither part.
 */
class StoredPin(val salt: String, val hash: String) {
    override fun toString(): String = "StoredPin(kept)"
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

    suspend fun setStandbyShows(shows: StandbyShows) = edit { it[STANDBY_SHOWS] = shows.name }

    suspend fun setWebEnabled(on: Boolean) = edit { it[WEB_ENABLED] = on }

    suspend fun setWebGuests(on: Boolean) = edit { it[WEB_GUESTS] = on }

    suspend fun setWebApproveFirst(on: Boolean) = edit { it[WEB_APPROVE_FIRST] = on }

    suspend fun setWebOnWifi(on: Boolean) = edit { it[WEB_ON_WIFI] = on }

    /** A name the panel answers to besides its address, lower-cased; blank or null forgets it. The web panel checks its form first. */
    suspend fun setWebHostName(name: String?) = edit {
        val kept = name?.trim()?.lowercase()?.take(MAX_HOST_NAME)
        if (kept.isNullOrEmpty()) it.remove(WEB_HOST_NAME) else it[WEB_HOST_NAME] = kept
    }

    /** The panel's PIN as kept (salt and hash), or null while none is set. Read on its own, never with [settings]. */
    suspend fun webPin(): StoredPin? {
        val prefs = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }.first()
        val salt = prefs[WEB_PIN_SALT] ?: return null
        val hash = prefs[WEB_PIN_HASH] ?: return null
        return StoredPin(salt, hash)
    }

    /** A new panel PIN (its salt and hash, both at once). */
    suspend fun setWebPin(pin: StoredPin) = edit {
        it[WEB_PIN_SALT] = pin.salt
        it[WEB_PIN_HASH] = pin.hash
    }

    /** Kiosk mode on or off (Piano › Kiosk); the kiosk itself (`admin.Kiosk`) turns it, after Android has. */
    suspend fun setKioskEnabled(on: Boolean) = edit { it[KIOSK_ENABLED] = on }

    /** The kiosk's PIN as kept (salt and hash), or null while none is set. Read on its own, never with [settings]. */
    suspend fun kioskPin(): StoredPin? {
        val prefs = current()
        val salt = prefs[KIOSK_PIN_SALT] ?: return null
        val hash = prefs[KIOSK_PIN_HASH] ?: return null
        return StoredPin(salt, hash)
    }

    /** A new kiosk PIN (its salt and hash, both at once); the wrong tries counted against the old one go with it. */
    suspend fun setKioskPin(pin: StoredPin) = edit {
        it[KIOSK_PIN_SALT] = pin.salt
        it[KIOSK_PIN_HASH] = pin.hash
        it.remove(KIOSK_STRIKES)
        it.remove(KIOSK_LOCKED_UNTIL)
    }

    /** Wrong kiosk PINs in a row and when the wait they earned ends (epoch ms), so a restart doesn't reset them. Housekeeping. */
    suspend fun kioskStrikes(): Pair<Int, Long> {
        val prefs = current()
        return (prefs[KIOSK_STRIKES] ?: 0).coerceAtLeast(0) to (prefs[KIOSK_LOCKED_UNTIL] ?: 0L)
    }

    suspend fun setKioskStrikes(count: Int, lockedUntil: Long) = edit {
        if (count <= 0) {
            it.remove(KIOSK_STRIKES)
            it.remove(KIOSK_LOCKED_UNTIL)
        } else {
            it[KIOSK_STRIKES] = count
            it[KIOSK_LOCKED_UNTIL] = lockedUntil
        }
    }

    /** Android's "stay on while plugged in" as it was before kiosk mode set it, to put back when kiosk mode ends; null: not kept. Housekeeping. */
    suspend fun kioskStayOnBefore(): Int? = current()[KIOSK_STAY_ON_BEFORE]

    suspend fun setKioskStayOnBefore(mask: Int?) = edit { if (mask == null) it.remove(KIOSK_STAY_ON_BEFORE) else it[KIOSK_STAY_ON_BEFORE] = mask }

    /** Remote access over the internet on or off (v1.10 — M26); it connects only once enrolled, with a PIN set. */
    suspend fun setCloudEnabled(on: Boolean) = edit { it[CLOUD_ENABLED] = on }

    /** The relay's address as typed (already read into its one form, `CloudAddress.host`), remembered for the next enrolment. */
    suspend fun setCloudHost(host: String) = edit { it[CLOUD_HOST] = host.take(MAX_HOST_NAME) }

    /**
     * An enrolment, all at once: the relay's [host], this tablet's [pianoId] and its secret [sealed]
     * (`CloudSecrets.seal`), so the relay client never sees a new id with an old secret.
     */
    suspend fun setCloudEnrolment(host: String, pianoId: String, sealed: String) = edit {
        it[CLOUD_HOST] = host.take(MAX_HOST_NAME)
        it[CLOUD_PIANO_ID] = pianoId
        it[CLOUD_SECRET] = sealed
    }

    /** The relay's secret as kept (sealed), or null. Read on its own, never with [settings]. */
    suspend fun cloudSecret(): String? = current()[CLOUD_SECRET]

    /** A rotated secret (sealed) in place of the old one; null removes it. */
    suspend fun setCloudSecret(sealed: String?) = edit { if (sealed == null) it.remove(CLOUD_SECRET) else it[CLOUD_SECRET] = sealed }

    /** Forget this cloud: the enrolment (the piano's id and its secret) goes and remote access turns off; the typed address stays for next time. */
    suspend fun forgetCloud() = edit {
        it.remove(CLOUD_PIANO_ID)
        it.remove(CLOUD_SECRET)
        it[CLOUD_ENABLED] = false
    }

    /** When the tablet plays the piano sound (v1.8 — M25). */
    suspend fun setTabletSound(mode: TabletSoundMode) = edit { it[TABLET_SOUND] = mode.name }

    /** The tablet's piano sound's volume, held to 0-100 %. */
    suspend fun setTabletVolume(pct: Int) = edit { it[TABLET_VOLUME] = pct.coerceIn(0, 100) }

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

    private suspend fun current(): Preferences = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }.first()

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
            standbyShows = StandbyShows.entries.firstOrNull { it.name == this[STANDBY_SHOWS] } ?: defaults.standbyShows,
            webEnabled = this[WEB_ENABLED] ?: defaults.webEnabled,
            webGuests = this[WEB_GUESTS] ?: defaults.webGuests,
            webApproveFirst = this[WEB_APPROVE_FIRST] ?: defaults.webApproveFirst,
            webOnWifi = this[WEB_ON_WIFI] ?: defaults.webOnWifi,
            webHostName = this[WEB_HOST_NAME],
            webPinSet = this[WEB_PIN_SALT] != null && this[WEB_PIN_HASH] != null,
            kioskEnabled = this[KIOSK_ENABLED] ?: defaults.kioskEnabled,
            kioskPinSet = this[KIOSK_PIN_SALT] != null && this[KIOSK_PIN_HASH] != null,
            tabletSound = TabletSoundMode.entries.firstOrNull { it.name == this[TABLET_SOUND] } ?: defaults.tabletSound,
            tabletVolume = (this[TABLET_VOLUME] ?: defaults.tabletVolume).coerceIn(0, 100),
            cloudEnabled = this[CLOUD_ENABLED] ?: defaults.cloudEnabled,
            cloudHost = this[CLOUD_HOST],
            cloudPianoId = this[CLOUD_PIANO_ID]?.takeIf { PIANO_ID.matches(it) },
            cloudSecretSet = this[CLOUD_SECRET] != null,
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
        val STANDBY_SHOWS = stringPreferencesKey("standbyShows")
        val WEB_ENABLED = booleanPreferencesKey("webEnabled")
        val WEB_GUESTS = booleanPreferencesKey("webGuests")
        val WEB_APPROVE_FIRST = booleanPreferencesKey("webApproveFirst")
        val WEB_ON_WIFI = booleanPreferencesKey("webOnWifi")
        val WEB_HOST_NAME = stringPreferencesKey("webHostName")
        val WEB_PIN_SALT = stringPreferencesKey("webPinSalt")
        val WEB_PIN_HASH = stringPreferencesKey("webPinHash")
        val KIOSK_ENABLED = booleanPreferencesKey("kioskEnabled")
        val KIOSK_PIN_SALT = stringPreferencesKey("kioskPinSalt")
        val KIOSK_PIN_HASH = stringPreferencesKey("kioskPinHash")
        val KIOSK_STRIKES = intPreferencesKey("kioskPinStrikes")
        val KIOSK_LOCKED_UNTIL = longPreferencesKey("kioskPinLockedUntil")
        val KIOSK_STAY_ON_BEFORE = intPreferencesKey("kioskStayOnBefore")
        const val MAX_HOST_NAME = 253
        val TEXT_REPAIR_DONE = booleanPreferencesKey("libraryTextRepairDone")
        val CRASH_NOTICE_SEEN_AT = longPreferencesKey("crashNoticeSeenAt")
        val TABLET_SOUND = stringPreferencesKey("tabletSound")
        val TABLET_VOLUME = intPreferencesKey("tabletVolume")
        val CLOUD_ENABLED = booleanPreferencesKey("cloudEnabled")
        val CLOUD_HOST = stringPreferencesKey("cloudHost")
        val CLOUD_PIANO_ID = stringPreferencesKey("cloudPianoId")
        val CLOUD_SECRET = stringPreferencesKey("cloudSecret")

        /** A piano's id on the relay (`web.relay.RelayProtocol.PIANO_ID`, kept here so the settings need nothing from the web). */
        val PIANO_ID = Regex("[a-z2-7]{12}")
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
