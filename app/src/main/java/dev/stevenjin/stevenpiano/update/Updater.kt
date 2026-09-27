// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * What the Update button sets going, from a release on offer to Android's installer: the download
 * (run by `UpdateService`, its progress in [state]), the check of the file's SHA-256, and the
 * hand-over ([UpdateInstaller]). Every step reports to [checker], whose state the Piano tab shows.
 */
class Updater(
    val checker: UpdateChecker,
    val downloader: UpdateDownloader,
    val installer: UpdateInstaller,
) {
    val state: StateFlow<UpdateState> get() = checker.state

    /**
     * Downloads [manifest]'s file, then installs it ([launcher] opens Android's installer). The
     * state follows the bytes (at most [PROGRESS_STEPS] steps, so the row and the notification are
     * not redrawn for every buffer), and [onProgress] hears the same steps. A failure is one line
     * under the row with the release still on offer; a cancel puts the release back on offer.
     */
    suspend fun downloadAndInstall(manifest: UpdateManifest, launcher: Context, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        var shown = -1L
        checker.publish(UpdateState.Downloading(manifest, 0, manifest.sizeBytes))
        val file = try {
            downloader.download(manifest) { bytes, total ->
                if (bytes == total || shown < 0 || bytes - shown >= total / PROGRESS_STEPS) {
                    shown = bytes
                    checker.publish(UpdateState.Downloading(manifest, bytes, total))
                    onProgress(bytes, total)
                }
            }
        } catch (e: UpdateFailure) {
            checker.publish(UpdateState.Failed(e.message ?: UpdateFailures.UNREACHABLE, manifest))
            return
        } catch (e: CancellationException) {
            checker.publish(UpdateState.Available(manifest))
            throw e
        }
        checker.publish(UpdateState.ReadyToInstall(manifest, file))
        install(manifest, file, launcher)
    }

    /**
     * Hands a downloaded [file] to Android, after hashing it once more: a file that no longer
     * matches is deleted and never offered. The device owner's install reports back through
     * [UpdateResultReceiver]; with Android's installer showing, the file stays ready, so Update
     * opens the installer again if the person backs out of it.
     */
    suspend fun install(manifest: UpdateManifest, file: File, launcher: Context) {
        if (!downloader.verified(file, manifest)) {
            downloader.clear()
            checker.publish(UpdateState.Failed(UpdateFailures.MISMATCH, manifest))
            return
        }
        if (installer.isDeviceOwner()) checker.publish(UpdateState.Installing(manifest))
        when (installer.install(file, manifest, launcher)) {
            UpdateInstaller.Outcome.Silent -> Unit   // UpdateResultReceiver reports
            UpdateInstaller.Outcome.Prompted -> checker.publish(UpdateState.ReadyToInstall(manifest, file))
            UpdateInstaller.Outcome.Failed -> checker.publish(UpdateState.Failed(UpdateFailures.NOT_INSTALLED, manifest))
        }
    }

    private companion object {
        const val PROGRESS_STEPS = 200
    }
}
