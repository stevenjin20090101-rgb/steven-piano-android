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
import androidx.datastore.preferences.core.stringPreferencesKey
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
        repository.setWideLayout(WideLayout.NOTES_ONLY)
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
                wideLayout = WideLayout.NOTES_ONLY,
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
        assertEquals(WideLayout.STAFF_AND_NOTES, defaults.wideLayout)
        assertEquals(48, defaults.keysViewportStart)
        assertEquals(NoteDisplay.PAPER_ROLL, NoteDisplay.STAFF.rollStyle)
        assertEquals(NoteDisplay.FALLING, NoteDisplay.FALLING.rollStyle)
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
}
