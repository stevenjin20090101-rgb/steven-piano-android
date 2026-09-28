// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.update.DownloadFailure
import dev.stevenjin.stevenpiano.update.DownloadProblem
import dev.stevenjin.stevenpiano.update.UpdateServer
import dev.stevenjin.stevenpiano.update.UpdateSource
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import java.io.File
import java.io.IOException

/** A Studio job that stopped for a reason the person is told in [message], one line, in words. */
class StudioFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What Studio says when something goes wrong (DESIGN.md › v1.7 — M23: one line, in words, no red). */
object StudioFailures {
    const val OFFLINE = "Downloading a model needs an internet connection."
    const val UNREACHABLE = "Couldn't reach the download server."
    const val UNREADABLE = "The list of models couldn't be read."
    const val NOT_OFFERED = "This model isn't offered right now."
    const val MISMATCH = "The download didn't match the model; try again."
    const val STOPPED = "The download stopped; try again."
    const val NO_ROOM = "There isn't enough free space for the model."
    const val NO_MODEL = "The transcription model isn't on this tablet. Download it first."
    const val MODEL_DAMAGED = "The transcription model was damaged and has been removed. Download it again."
    const val UNAVAILABLE = "Studio isn't available on this device."
    const val TOO_LITTLE_MEMORY = "This tablet doesn't have enough memory for Studio."
    const val BUSY = "Close other apps and try again."
    const val RAN_OUT = "The tablet ran short of memory, so the transcription stopped. Close other apps and try again."
    const val NO_NOTES = "No piano was heard in this recording."
    const val FAILED = "The transcription didn't finish."
    const val NOT_SAVED = "The piece couldn't be added to the library."
}

/**
 * Brings one of Studio's models onto the device: the models' list from [source] (`releases/models.json`,
 * checked against the pins, [ModelManifest]), then the model's file from the address the list gives,
 * downloaded to the pinned size and SHA-256 ([VerifiedDownloader]: at most
 * [UpdateSource.MAX_MODEL_BYTES], with the file's size and [FREE_MARGIN] free beside it, progress every
 * [PROGRESS_EVERY]) into [store]. Failures are [StudioFailure]s with the line to show.
 */
class ModelInstaller(
    private val source: UpdateSource,
    private val server: UpdateServer,
    private val store: ModelStore,
    private val downloader: VerifiedDownloader,
) {
    /** Downloads [model]; [progress] hears (bytes so far, its size). Returns its file, verified. */
    suspend fun install(model: ModelEntry, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val text = try {
            server.manifest()
        } catch (e: IOException) {
            throw StudioFailure(StudioFailures.UNREACHABLE, e)
        }
        val manifest = try {
            ModelManifest.parse(text, source)
        } catch (e: InvalidModelManifest) {
            throw StudioFailure(StudioFailures.UNREADABLE, e)
        }
        val entry = manifest.entryFor(model) ?: throw StudioFailure(StudioFailures.NOT_OFFERED)
        val target = VerifiedDownloader.Target(
            url = entry.url,
            dir = store.dir,
            name = model.file,
            sizeBytes = model.sizeBytes,
            sha256 = model.sha256,
            cap = UpdateSource.MAX_MODEL_BYTES,
            freeMargin = FREE_MARGIN,
            progressEveryBytes = PROGRESS_EVERY,
        )
        val file = try {
            downloader.download(target, progress)
        } catch (e: DownloadFailure) {
            throw StudioFailure(
                when (e.problem) {
                    DownloadProblem.Mismatch -> StudioFailures.MISMATCH
                    DownloadProblem.Stopped -> StudioFailures.STOPPED
                    DownloadProblem.Unreachable -> StudioFailures.UNREACHABLE
                    DownloadProblem.NoRoom -> StudioFailures.NO_ROOM
                },
                e,
            )
        }
        store.downloaded(model)
        return file
    }

    companion object {
        /** Free space kept beside a model's file: a model never fills the device. */
        const val FREE_MARGIN = 256L * 1024 * 1024

        /** How often a download reports its progress. */
        const val PROGRESS_EVERY = 256L * 1024
    }
}
