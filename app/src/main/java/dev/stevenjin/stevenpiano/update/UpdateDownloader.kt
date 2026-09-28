// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads a release's file to `<dir>/<versionName>.apk` ([dir] is `cacheDir/updates`, which the
 * app's FileProvider shares with Android's installer and nothing else), through [VerifiedDownloader]
 * (since v1.7 — M23, which made this class's way general): the bytes go to a `.part` file while
 * their SHA-256 is computed on the way; the file is renamed to its name only once its size and hash
 * match the manifest's. Anything else (a size past the manifest's or [cap], a hash that differs)
 * deletes it and fails with [UpdateFailures.MISMATCH]: nothing is ever done with a file that did not
 * match. Older downloads in [dir] are removed first.
 */
class UpdateDownloader(
    private val dir: File,
    server: UpdateServer,
    private val cap: Long = UpdateManifest.MAX_APK_BYTES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val verified = VerifiedDownloader(server, io)

    /**
     * The verified file for [manifest]; [progress] hears (bytes so far, total). Throws
     * [UpdateFailure] with the line to show, or CancellationException (the part file is removed).
     */
    suspend fun download(manifest: UpdateManifest, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val name = "${manifest.versionName}.apk"
        withContext(io) {
            if (!dir.isDirectory && !dir.mkdirs()) throw UpdateFailure(UpdateFailures.NO_ROOM)
            dir.listFiles()?.forEach { if (it.name != name) it.delete() }
            File(dir, name).delete()
        }
        val target = VerifiedDownloader.Target(manifest.apkUrl, dir, name, manifest.sizeBytes, manifest.sha256, cap, FREE_MARGIN)
        return try {
            verified.download(target, progress)
        } catch (e: DownloadFailure) {
            throw UpdateFailure(
                when (e.problem) {
                    DownloadProblem.Mismatch -> UpdateFailures.MISMATCH
                    DownloadProblem.Stopped -> UpdateFailures.STOPPED
                    DownloadProblem.Unreachable -> UpdateFailures.UNREACHABLE
                    DownloadProblem.NoRoom -> UpdateFailures.NO_ROOM
                },
                e.cause ?: e,
            )
        }
    }

    /** Whether [file] still hashes to [manifest]'s SHA-256 (checked again just before installing). */
    suspend fun verified(file: File, manifest: UpdateManifest): Boolean = withContext(io) {
        file.isFile && file.length() == manifest.sizeBytes && VerifiedDownloader.sha256Of(file) == manifest.sha256
    }

    /** Removes every download (after the update is installed, or a file that no longer matched). */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * At the process's start: downloads older than [before] (wall-clock ms) are left from an earlier
     * run (Android's installer has taken its own copy by then) and are removed.
     */
    fun sweep(before: Long) {
        dir.listFiles()?.forEach { if (it.lastModified() < before) it.delete() }
    }

    companion object {
        /** Room left over after the file, so an update never fills the device. */
        const val FREE_MARGIN = 16L * 1024 * 1024

        fun hex(bytes: ByteArray): String = VerifiedDownloader.hex(bytes)
    }
}
