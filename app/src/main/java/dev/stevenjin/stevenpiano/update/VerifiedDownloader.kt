// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Why a verified download did not keep its file ([VerifiedDownloader]); each caller words it its own way. */
enum class DownloadProblem {
    /** More bytes than expected, fewer, or another SHA-256: the file was deleted before anything else. */
    Mismatch,

    /** The connection dropped after the first bytes. */
    Stopped,

    /** No first byte: no network path, a server error, the repository still private (404). */
    Unreachable,

    /** Too little free space for the file and the margin kept beside it, or the folder can't be written. */
    NoRoom,
}

/** A download that failed for [problem]; [cause] is the exception behind it, when there is one. */
class DownloadFailure(val problem: DownloadProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/**
 * One file from [server] into a folder, kept only when it is exactly what was expected (v1.7 — M23:
 * [UpdateDownloader]'s way, made general for Studio's models). What is wanted is a [Target]: the
 * address, the folder and the name the file gets, its exact size and SHA-256, the cap on what may be
 * read, and the free space kept beside it. The bytes go to `<name>.part` while their SHA-256 is
 * computed on the way; more than the size or the cap stops at once, and the part is renamed to its
 * name only once size and hash match. Anything else deletes the part and throws [DownloadFailure]; a
 * cancel deletes it too. Nothing is resumed: a new download starts from the first byte. Progress is
 * reported every [Target.progressEveryBytes] (and at the end).
 */
class VerifiedDownloader(
    private val server: UpdateServer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The free space where a folder lives (a seam for tests). */
    private val usableSpace: (File) -> Long = { it.usableSpace },
) {
    /**
     * [url] into [dir]/[name]; [sizeBytes] and [sha256] (lower-case hex) are what it must be; at most
     * [cap] bytes are read whatever the server says; [freeMargin] bytes must stay free beside the file.
     */
    data class Target(
        val url: String,
        val dir: File,
        val name: String,
        val sizeBytes: Long,
        val sha256: String,
        val cap: Long,
        val freeMargin: Long,
        val progressEveryBytes: Long = 0,
    )

    /**
     * The verified file; [progress] hears (bytes so far, the expected size). Throws [DownloadFailure],
     * or CancellationException (the part file is removed).
     */
    suspend fun download(target: Target, progress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(io) {
        val limit = minOf(target.cap, target.sizeBytes)
        val dir = target.dir
        if (!dir.isDirectory && !dir.mkdirs()) throw DownloadFailure(DownloadProblem.NoRoom)
        val file = File(dir, target.name)
        val part = File(dir, target.name + PART)
        part.delete()
        val free = usableSpace(dir)
        if (free in 1 until limit + target.freeMargin) throw DownloadFailure(DownloadProblem.NoRoom)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        var reported = 0L
        try {
            FileOutputStream(part).use { out ->
                server.download(target.url, limit) { buffer, n ->
                    count += n
                    if (count > limit) throw DownloadFailure(DownloadProblem.Mismatch)
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    if (count - reported >= target.progressEveryBytes || count == target.sizeBytes) {
                        reported = count
                        progress(count, target.sizeBytes)
                    }
                }
                out.fd.sync()
            }
            // The hash is compared before anything else is done with the file.
            if (count != target.sizeBytes || hex(digest.digest()) != target.sha256) throw DownloadFailure(DownloadProblem.Mismatch)
            file.delete()
            if (!part.renameTo(file)) throw DownloadFailure(DownloadProblem.NoRoom)
            file
        } catch (e: DownloadFailure) {
            part.delete()
            throw e
        } catch (e: UpdateFailure) {   // the server's own refusal: it declared more than the cap
            part.delete()
            throw DownloadFailure(DownloadProblem.Mismatch, e)
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: IOException) {
            part.delete()
            throw DownloadFailure(if (count > 0) DownloadProblem.Stopped else DownloadProblem.Unreachable, e)
        }
    }

    companion object {
        /** What a file is called while it arrives. */
        const val PART = ".part"

        /** [file]'s SHA-256 as lower-case hex, or null when it can't be read. Blocking. */
        fun sha256Of(file: File): String? {
            val digest = MessageDigest.getInstance("SHA-256")
            return try {
                file.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                hex(digest.digest())
            } catch (e: IOException) {
                null
            }
        }

        fun hex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return out.toString()
        }

        private const val HEX = "0123456789abcdef"
        private const val BUFFER_BYTES = 64 * 1024
    }
}
