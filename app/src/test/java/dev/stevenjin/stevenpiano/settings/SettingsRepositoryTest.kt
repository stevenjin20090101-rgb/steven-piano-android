// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.stevenjin.stevenpiano.data.LibraryScope
import dev.stevenjin.stevenpiano.data.PlaylistSort
import dev.stevenjin.stevenpiano.player.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `defaults, round trips and clamping`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(PianoSettings(), repository.settings.first())

        repository.setAutoConnect(false)
        repository.rememberDevice("C8:2E:18:00:11:22", "Steven Piano")
        repository.setNoteDisplay(NoteDisplay.STAFF)
        repository.setDefaultTempo(80)
        repository.setTranspose(30)
        repository.setVelocity(10)
        repository.setFoldOutOfRange(false)
        repository.setSkipDrumChannel(false)
        repository.setNotesSplit(stacked = true, share = 0f)
        repository.setKeysViewportStart(3)
        assertEquals(
            PianoSettings(
                autoConnect = false,
                lastDeviceAddress = "C8:2E:18:00:11:22",
                lastDeviceName = "Steven Piano",
                noteDisplay = NoteDisplay.STAFF,
                defaultTempoPct = 80,
                transpose = 12,
                velocityPct = 50,
                foldOutOfRange = false,
                skipDrumChannel = false,
                notesSplitStacked = 0f,
                keysViewportStart = 24,
            ),
            repository.settings.first(),
        )
        repository.setKeysViewportStart(200)
        assertEquals(107, repository.settings.first().keysViewportStart)
        repository.setKeysViewportStart(60)
        assertEquals(60, repository.settings.first().keysViewportStart)
        scope.cancel()
    }

    @Test
    fun `shuffle and repeat are remembered, and start off`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "modes.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(false, repository.settings.first().shuffle)
        assertEquals(RepeatMode.OFF, repository.settings.first().repeat)
        repository.setShuffle(true)
        repository.setRepeat(RepeatMode.ONE)
        assertEquals(true, repository.settings.first().shuffle)
        assertEquals(RepeatMode.ONE, repository.settings.first().repeat)
        repository.setRepeat(RepeatMode.ALL)
        repository.setShuffle(false)
        assertEquals(PianoSettings(repeat = RepeatMode.ALL), repository.settings.first())
        scope.cancel()
    }

    @Test
    fun `artwork starts in colour and fetched automatically, and both are remembered`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "artwork.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(false, repository.settings.first().artworkMonochrome)
        assertEquals(true, repository.settings.first().fetchArtworkAutomatically)
        repository.setArtworkMonochrome(true)
        repository.setFetchArtworkAutomatically(false)
        assertEquals(PianoSettings(artworkMonochrome = true, fetchArtworkAutomatically = false), repository.settings.first())
        repository.setArtworkMonochrome(false)
        repository.setFetchArtworkAutomatically(true)
        assertEquals(PianoSettings(), repository.settings.first())
        scope.cancel()
    }

    @Test
    fun `v1_1 defaults - paper roll, staff and notes on wide screens, the Keys screen from C3`() {
        val defaults = PianoSettings()
        assertEquals(NoteDisplay.PAPER_ROLL, defaults.noteDisplay)
        assertEquals("each arrangement's own default split (v1.12)", null, defaults.notesSplitStacked)
        assertEquals(null, defaults.notesSplitSide)
        assertEquals(48, defaults.keysViewportStart)
        assertEquals(NoteDisplay.PAPER_ROLL, NoteDisplay.STAFF.rollStyle)
        assertEquals(NoteDisplay.FALLING, NoteDisplay.FALLING.rollStyle)
    }

    @Test
    fun `the split is the first Float preference - each arrangement's share round-trips, held to 0-1, not a number refused (v1_12 M31a)`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "split.preferences_pb") }
        val repository = SettingsRepository(store)
        repository.setNotesSplit(stacked = true, share = 0.4f)
        repository.setNotesSplit(stacked = false, share = 0.62f)
        assertEquals(PianoSettings(notesSplitStacked = 0.4f, notesSplitSide = 0.62f), repository.settings.first())
        repository.setNotesSplit(stacked = true, share = 1.7f)
        repository.setNotesSplit(stacked = false, share = -0.3f)
        assertEquals(1f, repository.settings.first().notesSplitStacked)
        assertEquals(0f, repository.settings.first().notesSplitSide)
        repository.setNotesSplit(stacked = true, share = Float.NaN)
        repository.setNotesSplit(stacked = true, share = Float.POSITIVE_INFINITY)
        assertEquals("refused, the share before kept", 1f, repository.settings.first().notesSplitStacked)
        // A stored value this version cannot use reads as the default, or held to 0-1.
        store.edit { it[floatPreferencesKey("notesSplitStacked")] = Float.NaN }
        store.edit { it[floatPreferencesKey("notesSplitSide")] = 3f }
        assertEquals(PianoSettings(notesSplitStacked = null, notesSplitSide = 1f), repository.settings.first())
        scope.cancel()
    }

    @Test
    fun `an older build's Wide layout seeds both arrangements - Notes only 0, Score only 1 - and the first write keeps it and forgets it (v1_12 M31a)`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        for ((legacy, seed) in listOf("NOTES_ONLY" to 0f, "STAFF_ONLY" to 1f, "STAFF_AND_NOTES" to null, "SOMETHING_NEW" to null)) {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "legacy-$legacy.preferences_pb") }
            val repository = SettingsRepository(store)
            store.edit { it[stringPreferencesKey("wideLayout")] = legacy }
            assertEquals(legacy, PianoSettings(notesSplitStacked = seed, notesSplitSide = seed), repository.settings.first())
            // The person drags the stacked divider: the side-by-side share keeps what Wide layout said, and the old key goes.
            repository.setNotesSplit(stacked = true, share = 0.5f)
            assertEquals(legacy, PianoSettings(notesSplitStacked = 0.5f, notesSplitSide = seed), repository.settings.first())
            assertEquals(legacy, null, store.data.first()[stringPreferencesKey("wideLayout")])
            assertEquals(legacy, seed, store.data.first()[floatPreferencesKey("notesSplitSide")])
        }
        scope.cancel()
    }

    @Test
    fun `fingering and chord names start on, hand colours off, and all three are remembered`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "waterfall.preferences_pb") }
        val repository = SettingsRepository(store)
        val start = repository.settings.first()
        assertEquals(true, start.fingering)
        assertEquals(true, start.chordNames)
        assertEquals(false, start.handColours)
        repository.setFingering(false)
        repository.setChordNames(false)
        repository.setHandColours(true)
        assertEquals(PianoSettings(fingering = false, chordNames = false, handColours = true), repository.settings.first())
        repository.setFingering(true)
        repository.setChordNames(true)
        repository.setHandColours(false)
        assertEquals(PianoSettings(), repository.settings.first())
        scope.cancel()
    }

    @Test
    fun `the crash banner's answer only moves forward`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "crash.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(0L, repository.crashNoticeSeenAt.first())
        repository.markCrashNoticeSeen(2_000L)
        assertEquals(2_000L, repository.crashNoticeSeenAt.first())
        repository.markCrashNoticeSeen(1_000L)
        assertEquals(2_000L, repository.crashNoticeSeenAt.first())
        assertEquals(PianoSettings(), repository.settings.first())
        scope.cancel()
    }

    @Test
    fun `automatic update checks start on and are remembered`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "updates.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(true, repository.settings.first().checkForUpdates)
        repository.setCheckForUpdates(false)
        assertEquals(PianoSettings(checkForUpdates = false), repository.settings.first())
        repository.setCheckForUpdates(true)
        assertEquals(PianoSettings(), repository.settings.first())
        scope.cancel()
    }

    @Test
    fun `the pause before each piece is 2 s at first, remembered, and held to 0-5 s`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "preroll.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(2_000, repository.settings.first().preRollMs)
        repository.setPreRoll(500)
        assertEquals(500, repository.settings.first().preRollMs)
        repository.setPreRoll(0)
        assertEquals(0, repository.settings.first().preRollMs)
        repository.setPreRoll(9_000)
        assertEquals(5_000, repository.settings.first().preRollMs)
        repository.setPreRoll(-500)
        assertEquals(0, repository.settings.first().preRollMs)
        scope.cancel()
    }

    @Test
    fun `channel volumes start at 70, are remembered each on its own, and are held to 0-100`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "channels.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(70, repository.settings.first().channelVolume("calm"))
        repository.setChannelVolume("calm", 40)
        repository.setChannelVolume("epic", 250)
        repository.setChannelVolume("baroque", -5)
        val saved = repository.settings.first()
        assertEquals(mapOf("calm" to 40, "epic" to 100, "baroque" to 0), saved.channelVolumes)
        assertEquals(70, saved.channelVolume("romantic"))
        repository.setChannelVolume("calm", 55)
        assertEquals(mapOf("calm" to 55, "epic" to 100, "baroque" to 0), repository.settings.first().channelVolumes)
        scope.cancel()
    }

    @Test
    fun `channel volumes that cannot be read read as none`() {
        assertEquals(emptyMap<String, Int>(), ChannelVolumesJson.read("{not json"))
        assertEquals(emptyMap<String, Int>(), ChannelVolumesJson.read(null))
        assertEquals(mapOf("calm" to 60), ChannelVolumesJson.read("""{"calm":60,"epic":"loud"}"""))
        assertEquals("""{"calm":60,"epic":80}""", ChannelVolumesJson.write(mapOf("epic" to 80, "calm" to 60)))
    }

    @Test
    fun `the appearance follows the system, display mode is off on a black canvas, and all three are remembered`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "display.preferences_pb") }
        val repository = SettingsRepository(store)
        val start = repository.settings.first()
        assertEquals(Appearance.SYSTEM, start.appearance)
        assertEquals(false, start.displayModeAfterMinute)
        assertEquals(StandbyCanvas.BLACK, start.standbyCanvas)
        repository.setAppearance(Appearance.LIGHT)
        repository.setDisplayModeAfterMinute(true)
        repository.setStandbyCanvas(StandbyCanvas.INK)
        assertEquals(
            PianoSettings(appearance = Appearance.LIGHT, displayModeAfterMinute = true, standbyCanvas = StandbyCanvas.INK),
            repository.settings.first(),
        )
        repository.setAppearance(Appearance.DARK)
        assertEquals(Appearance.DARK, repository.settings.first().appearance)
        scope.cancel()
    }

    @Test
    fun `an appearance or canvas this version does not know reads as the default`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "unknown.preferences_pb") }
        store.edit {
            it[stringPreferencesKey("appearance")] = "SEPIA"
            it[stringPreferencesKey("standbyCanvas")] = "GLASS"
        }
        val read = SettingsRepository(store).settings.first()
        assertEquals(Appearance.SYSTEM, read.appearance)
        assertEquals(StandbyCanvas.BLACK, read.standbyCanvas)
        scope.cancel()
    }

    @Test
    fun `the resting screen shows the art and notes at first, the paper roll once chosen, and a choice it does not know as the art`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "standby.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(StandbyShows.ART_AND_NOTES, repository.settings.first().standbyShows)
        repository.setStandbyShows(StandbyShows.PAPER_ROLL)
        assertEquals(PianoSettings(standbyShows = StandbyShows.PAPER_ROLL), repository.settings.first())
        repository.setStandbyShows(StandbyShows.ART_AND_NOTES)
        assertEquals(PianoSettings(), repository.settings.first())
        store.edit { it[stringPreferencesKey("standbyShows")] = "SLIDESHOW" }
        assertEquals(StandbyShows.ART_AND_NOTES, repository.settings.first().standbyShows)
        scope.cancel()
    }

    @Test
    fun `the album's colours behind the player start on, and turned off are remembered (v1_15 M41)`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "backdrop.preferences_pb") }
        assertEquals(true, SettingsRepository(store).settings.first().albumBackdrop)
        SettingsRepository(store).setAlbumBackdrop(false)
        assertEquals("read back by a new repository", PianoSettings(albumBackdrop = false), SettingsRepository(store).settings.first())
        scope.cancel()
    }

    @Test
    fun `the web panel starts off with guests closed and approval first, and every switch is remembered`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "web.preferences_pb") }
        val repository = SettingsRepository(store)
        val first = repository.settings.first()
        assertEquals(listOf(false, false, true, false), listOf(first.webEnabled, first.webGuests, first.webApproveFirst, first.webOnWifi))
        assertEquals(null, first.webHostName)
        assertEquals(false, first.webPinSet)
        repository.setWebEnabled(true)
        repository.setWebGuests(true)
        repository.setWebApproveFirst(false)
        repository.setWebOnWifi(true)
        repository.setWebHostName("  Piano-Tablet ")
        val read = repository.settings.first()
        assertEquals(listOf(true, true, false, true), listOf(read.webEnabled, read.webGuests, read.webApproveFirst, read.webOnWifi))
        assertEquals("piano-tablet", read.webHostName)
        repository.setWebHostName(" ")
        assertEquals(null, repository.settings.first().webHostName)
        scope.cancel()
    }

    @Test
    fun `the PIN is kept apart from the settings, which only say whether one is set`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "pin.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(null, repository.webPin())
        repository.setWebPin(StoredPin("c2FsdA==", "aGFzaA=="))
        assertEquals("c2FsdA==", repository.webPin()!!.salt)
        assertEquals("aGFzaA==", repository.webPin()!!.hash)
        assertEquals(true, repository.settings.first().webPinSet)
        assertEquals("the stored PIN prints neither part", false, "c2FsdA" in repository.webPin().toString())
        val fields = PianoSettings::class.java.declaredFields.map { it.name.lowercase() }
        assertEquals("nothing that carries the settings can carry the PIN's hash or salt", emptyList<String>(), fields.filter { "hash" in it || "salt" in it })
        scope.cancel()
    }

    @Test
    fun `kiosk mode starts off, its PIN is kept apart like the panel's, and its housekeeping is remembered`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "kiosk.preferences_pb") }
        val repository = SettingsRepository(store)
        val first = repository.settings.first()
        assertEquals(false, first.kioskEnabled)
        assertEquals(false, first.kioskPinSet)
        assertEquals(null, repository.kioskPin())
        assertEquals(0 to 0L, repository.kioskStrikes())
        assertEquals(null, repository.kioskStayOnBefore())

        repository.setKioskEnabled(true)
        repository.setKioskStrikes(5, 1_234L)
        repository.setKioskStayOnBefore(3)
        assertEquals(true, repository.settings.first().kioskEnabled)
        assertEquals(5 to 1_234L, repository.kioskStrikes())
        assertEquals(3, repository.kioskStayOnBefore())

        repository.setKioskPin(StoredPin("a2lvc2s=", "cGlu"))
        assertEquals("a2lvc2s=", repository.kioskPin()!!.salt)
        assertEquals(true, repository.settings.first().kioskPinSet)
        assertEquals("the web panel's PIN is another", null, repository.webPin())
        assertEquals("a new PIN forgets the wrong tries counted against the old", 0 to 0L, repository.kioskStrikes())

        repository.setKioskStrikes(0, 99L)
        assertEquals(0 to 0L, repository.kioskStrikes())
        repository.setKioskStayOnBefore(null)
        assertEquals(null, repository.kioskStayOnBefore())
        scope.cancel()
    }

    @Test
    fun `the tablet's sound plays when the piano isn't connected, at 60 %, until changed`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "tablet.preferences_pb") }
        val repository = SettingsRepository(store)
        val first = repository.settings.first()
        assertEquals(dev.stevenjin.stevenpiano.audio.TabletSoundMode.WHEN_NOT_CONNECTED, first.tabletSound)
        assertEquals(60, first.tabletVolume)
        repository.setTabletSound(dev.stevenjin.stevenpiano.audio.TabletSoundMode.ALWAYS)
        repository.setTabletVolume(85)
        assertEquals(dev.stevenjin.stevenpiano.audio.TabletSoundMode.ALWAYS, repository.settings.first().tabletSound)
        assertEquals(85, repository.settings.first().tabletVolume)
        repository.setTabletVolume(140)
        assertEquals(100, repository.settings.first().tabletVolume)
        repository.setTabletVolume(-3)
        assertEquals(0, repository.settings.first().tabletVolume)
        repository.setTabletSound(dev.stevenjin.stevenpiano.audio.TabletSoundMode.OFF)
        assertEquals(dev.stevenjin.stevenpiano.audio.TabletSoundMode.OFF, repository.settings.first().tabletSound)
        scope.cancel()
    }

    @Test
    fun `the cloud starts off and unenrolled, an enrolment is kept at once with its sealed secret apart, and forgetting keeps the address`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "cloud.preferences_pb") }
        val repository = SettingsRepository(store)
        val fresh = repository.settings.first()
        assertEquals(false, fresh.cloudEnabled)
        assertEquals(false, fresh.cloudEnrolled)
        assertEquals(null, repository.cloudSecret())
        repository.setCloudHost("relay.example.dev")
        assertEquals("relay.example.dev", repository.settings.first().cloudHost)
        assertEquals("an address alone is no enrolment", false, repository.settings.first().cloudEnrolled)
        repository.setCloudEnrolment("relay.example.dev", "abcdefgh2345", "v1:c2VhbGVk")
        repository.setCloudEnabled(true)
        val enrolled = repository.settings.first()
        assertEquals(true, enrolled.cloudEnabled)
        assertEquals("abcdefgh2345", enrolled.cloudPianoId)
        assertEquals(true, enrolled.cloudSecretSet)
        assertEquals(true, enrolled.cloudEnrolled)
        assertEquals("v1:c2VhbGVk", repository.cloudSecret())
        assertEquals("the settings never carry the sealed secret", false, "c2VhbGVk" in enrolled.toString())
        repository.setCloudSecret("v1:cm90YXRlZA==")
        assertEquals("v1:cm90YXRlZA==", repository.cloudSecret())
        repository.forgetCloud()
        val forgotten = repository.settings.first()
        assertEquals(false, forgotten.cloudEnabled)
        assertEquals(null, forgotten.cloudPianoId)
        assertEquals(false, forgotten.cloudSecretSet)
        assertEquals(null, repository.cloudSecret())
        assertEquals("the typed address stays for next time", "relay.example.dev", forgotten.cloudHost)
        store.edit { it[stringPreferencesKey("cloudPianoId")] = "NOT AN ID" }
        assertEquals("an id not of the relay's form reads as none", null, repository.settings.first().cloudPianoId)
        scope.cancel()
    }

    @Test
    fun `the library pack loaded starts at none and is remembered`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "library.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(0, repository.settings.first().libraryPackVersion)
        repository.setLibraryPackVersion(2)
        assertEquals(2, repository.settings.first().libraryPackVersion)
        assertEquals(2, SettingsRepository(store).settings.first().libraryPackVersion)
        repository.setLibraryPackVersion(-1)
        assertEquals(0, repository.settings.first().libraryPackVersion)
        scope.cancel()
    }

    @Test
    fun `the playlists are newest first until the person chooses by name, and a choice this version does not know reads as the default`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "sort.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(PlaylistSort.NEWEST, repository.settings.first().playlistSort)
        repository.setPlaylistSort(PlaylistSort.NAME)
        assertEquals(PlaylistSort.NAME, repository.settings.first().playlistSort)
        assertEquals(PlaylistSort.NAME, SettingsRepository(store).settings.first().playlistSort)
        store.edit { it[stringPreferencesKey("playlistSort")] = "BY_COLOUR" }
        assertEquals(PlaylistSort.NEWEST, repository.settings.first().playlistSort)
        scope.cancel()
    }

    @Test
    fun `the Library's genre is All until one is chosen, then the one chosen, across restarts`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "genre.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(LibraryScope.All, repository.settings.first().libraryScope)
        repository.setLibraryScope(LibraryScope.Modern)
        assertEquals(LibraryScope.Modern, repository.settings.first().libraryScope)
        assertEquals(LibraryScope.Modern, SettingsRepository(store).settings.first().libraryScope)
        scope.cancel()
    }

    @Test
    fun `the repair of older uploads is housekeeping, due at first and remembered once done, never a preference`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "repair.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(false, repository.uploadRepairDone())
        assertEquals(false, repository.textRepairDone())
        repository.markUploadRepairDone()
        assertEquals(true, repository.uploadRepairDone())
        assertEquals(true, SettingsRepository(store).uploadRepairDone())
        assertEquals("each repair its own flag", false, repository.textRepairDone())
        assertEquals("nothing a preference: Share diagnostics never sees it", PianoSettings(), repository.settings.first())
        scope.cancel()
    }

    @Test
    fun `no keyboard at first, one chosen is kept with its name, and forgetting it forgets both (v1_11 M29)`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "keyboard.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(null, repository.settings.first().keyboardId)
        repository.setKeyboard("ble:11:22:33:44:55:66", "Roland FP-30X")
        assertEquals("ble:11:22:33:44:55:66", repository.settings.first().keyboardId)
        assertEquals("Roland FP-30X", SettingsRepository(store).settings.first().keyboardName)
        repository.setKeyboard("usb:" + "x".repeat(400), "y".repeat(100))
        assertEquals(256, repository.settings.first().keyboardId?.length)
        assertEquals(64, repository.settings.first().keyboardName?.length)
        repository.setKeyboard(null, "ignored")
        assertEquals(PianoSettings(), repository.settings.first())
        store.edit { it[stringPreferencesKey("keyboardName")] = "a name without its keyboard" }
        assertEquals("a name alone is no keyboard", null, repository.settings.first().keyboardName)
        scope.cancel()
    }

    @Test
    fun `Live starts off and is remembered (v1_11 M29)`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "live.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(false, repository.settings.first().liveToPiano)
        repository.setLiveToPiano(true)
        assertEquals(true, SettingsRepository(store).settings.first().liveToPiano)
        repository.setLiveToPiano(false)
        assertEquals(PianoSettings(), repository.settings.first())
        scope.cancel()
    }

    @Test
    fun `Steven Piano plays at first, a MIDI piano chosen is kept with its name, and choosing Steven Piano again remembers it (v1_11 M29)`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "instrument.preferences_pb") }
        val repository = SettingsRepository(store)
        assertEquals(InstrumentChoice.STEVEN_PIANO, repository.settings.first().instrumentKind)
        repository.setInstrument(InstrumentChoice.MIDI_PIANO, "usb:Roland|FP-30X|1", "FP-30X")
        val chosen = SettingsRepository(store).settings.first()
        assertEquals(InstrumentChoice.MIDI_PIANO, chosen.instrumentKind)
        assertEquals("usb:Roland|FP-30X|1", chosen.midiOutId)
        assertEquals("FP-30X", chosen.midiOutName)
        repository.setInstrument(InstrumentChoice.STEVEN_PIANO, null, null)
        val back = repository.settings.first()
        assertEquals(InstrumentChoice.STEVEN_PIANO, back.instrumentKind)
        assertEquals("the MIDI piano stays remembered for next time", "usb:Roland|FP-30X|1", back.midiOutId)
        repository.setInstrument(InstrumentChoice.MIDI_PIANO, "ble:" + "x".repeat(400), "y".repeat(100))
        assertEquals(256, repository.settings.first().midiOutId?.length)
        assertEquals(64, repository.settings.first().midiOutName?.length)
        store.edit {
            it.remove(stringPreferencesKey("midiOutId"))
            it[stringPreferencesKey("instrumentKind")] = "MIDI_PIANO"
        }
        assertEquals("a MIDI piano without one chosen is Steven Piano", InstrumentChoice.STEVEN_PIANO, repository.settings.first().instrumentKind)
        assertEquals(null, repository.settings.first().midiOutName)
        store.edit { it[stringPreferencesKey("instrumentKind")] = "THEREMIN" }
        assertEquals(InstrumentChoice.STEVEN_PIANO, repository.settings.first().instrumentKind)
        scope.cancel()
    }
}
