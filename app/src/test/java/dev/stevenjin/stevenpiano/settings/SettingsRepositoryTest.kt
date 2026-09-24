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
        repository.setNoteDisplay(NoteDisplay.FALLING)
        repository.setDefaultTempo(80)
        repository.setTranspose(30)
        repository.setVelocity(10)
        repository.setFoldOutOfRange(false)
        repository.setSkipDrumChannel(false)
        assertEquals(
            PianoSettings(
                autoConnect = false,
                lastDeviceAddress = "C8:2E:18:00:11:22",
                lastDeviceName = "Steven Piano",
                noteDisplay = NoteDisplay.FALLING,
                defaultTempoPct = 80,
                transpose = 12,
                velocityPct = 50,
                foldOutOfRange = false,
                skipDrumChannel = false,
            ),
            repository.settings.first(),
        )
        scope.cancel()
    }
}
