// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.record

import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import dev.stevenjin.stevenpiano.midi.SmfWriter
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Where a recording goes (v1.11 — M29): the app's library and Studio's review, or a fake in tests. */
interface RecordingStore {
    /** Imports [bytes] through the importer as a piece called [title] by [composer]; its id, or null when it wasn't added. */
    suspend fun add(fileName: String, bytes: ByteArray, title: String, composer: String): Long?

    /** The piece's own line on its sheet. */
    suspend fun describe(pieceId: Long, description: String)

    /** The piece joins the built-in playlist Recordings, first. */
    suspend fun addToRecordings(pieceId: Long)

    /** The recordings, newest first. */
    suspend fun recordings(): List<Long>

    /** The piece waits for Keep or Discard (Studio's review). */
    suspend fun markUndecided(pieceId: Long)

    /** The pieces waiting for Keep or Discard. */
    suspend fun undecided(): Set<Long>

    /** The piece leaves the library, as Discard does. */
    suspend fun discard(pieceId: Long)
}

/** A recording saved: the piece, its title, how long it lasts and how many notes it has. */
data class SavedRecording(val pieceId: Long, val title: String, val durationMicros: Long, val notes: Int)

/**
 * A take into a piece of the library (v1.11 — M29). Its MIDI file ([SmfWriter]: the notes, every pedal value,
 * the title as the track's name and "Recorded live, <date and time to the second>" as its text, so no two takes
 * are the same file) is written to [dir] first, `pending-<epoch ms>.mid`, so a crash between here and the library
 * loses nothing ([recoverPending] imports what is left at the next start); then it goes in through the importer
 * ([RecordingStore.add]: its caps, its parse, its lock) as "Recording · <medium date> <short time>" by
 * [ComposerNames.RECORDED_LIVE] (once more with " (2)" in its text should the library already hold those bytes),
 * described "Recorded live · <date>", first in the built-in playlist Recordings, waiting for Keep or Discard. In
 * kiosk mode ([kiosk]) at most [KIOSK_UNDECIDED] recordings wait: past that, the oldest waiting one is discarded.
 * Nothing of a take is in a file name but the time.
 */
class RecordingPieces(
    private val store: RecordingStore,
    private val dir: File,
    private val kiosk: () -> Boolean,
    private val log: (String) -> Unit = {},
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val locale: Locale = Locale.getDefault(),
) {
    /** Saves [take], played at [at]: the piece, or null when the library would not take it (logged). */
    suspend fun save(take: Take, at: ZonedDateTime): SavedRecording? {
        val title = title(at)
        val bytes = midi(take, title, at, again = false)
        val pending = keep(bytes, at)
        val id = add(title, bytes, at) ?: add(title, midi(take, title, at, again = true), at)
        if (id == null) {
            log("Recording: the library did not take a ${take.durationMicros / 1_000_000} s take; its file stays for the next start")
            return null
        }
        finish(id, at)
        pending?.delete()
        log("Recording: ${take.notes.size} notes, ${take.durationMicros / 1_000_000} s, ended by ${take.ended.name.lowercase()}")
        return SavedRecording(id, title, take.durationMicros, take.notes.size)
    }

    /** At start: a take a crash left in [dir] goes into the library now. Returns how many did. */
    suspend fun recoverPending(): Int {
        val files = dir.listFiles { file -> file.isFile && file.name.startsWith(PENDING) && file.name.endsWith(MID) }.orEmpty().sortedBy { it.name }
        var saved = 0
        for (file in files) {
            val millis = file.name.removePrefix(PENDING).removeSuffix(MID).toLongOrNull()
            if (millis == null) {
                file.delete()
                continue
            }
            val at = Instant.ofEpochMilli(millis).atZone(zone())
            val bytes = try {
                file.readBytes()
            } catch (e: IOException) {
                continue
            }
            val id = try {
                add(title(at), bytes, at)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (id != null) {
                finish(id, at)
                saved++
            }
            file.delete()   // saved, or a file the library will never take: not tried at every start
        }
        if (saved > 0) log("Recording: $saved take" + (if (saved == 1) "" else "s") + " left by a crash saved")
        return saved
    }

    private suspend fun add(title: String, bytes: ByteArray, at: ZonedDateTime): Long? =
        store.add(fileName(at), bytes, title, ComposerNames.RECORDED_LIVE)

    /** Described, first in Recordings, waiting for Keep or Discard; in kiosk mode, the oldest waiting past the cap discarded. */
    private suspend fun finish(id: Long, at: ZonedDateTime) {
        store.describe(id, description(at))
        store.addToRecordings(id)
        store.markUndecided(id)
        if (!kiosk()) return
        val undecided = store.undecided()
        val waiting = store.recordings().filter { it in undecided }   // newest first
        for (old in waiting.drop(KIOSK_UNDECIDED)) {
            log("Recording: more than $KIOSK_UNDECIDED wait for the PIN in kiosk mode: the oldest discarded")
            store.discard(old)
        }
    }

    /** The take's file, kept until the library has it; null when it could not be written (the save goes on). */
    private fun keep(bytes: ByteArray, at: ZonedDateTime): File? = try {
        dir.mkdirs()
        val file = File(dir, PENDING + at.toInstant().toEpochMilli() + MID)
        val part = File(dir, file.name + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(file)) {
            part.delete()
            null
        } else {
            file
        }
    } catch (e: IOException) {
        null
    }

    private fun midi(take: Take, title: String, at: ZonedDateTime, again: Boolean): ByteArray = SmfWriter.write(
        notes = take.notes,
        title = title,
        text = "${ComposerNames.RECORDED_LIVE}, ${STAMP.format(at)}" + if (again) " (2)" else "",
        controls = take.controls,
    )

    /** "Recording · Oct 1, 2026 2:05 PM": the date and the time in the device's own style. */
    fun title(at: ZonedDateTime): String =
        "Recording · ${DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(at)} ${DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at)}"

    /** "Recorded live · Oct 1, 2026": the line on the piece's sheet. */
    fun description(at: ZonedDateTime): String = "${ComposerNames.RECORDED_LIVE} · ${DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(at)}"

    /** The file's name the importer is given: the time alone ("Recording 2026-10-01 14-05-33.mid"). */
    private fun fileName(at: ZonedDateTime): String = "Recording ${FILE_STAMP.format(at)}$MID"

    companion object {
        /** In kiosk mode, the most recordings that wait for someone with the PIN. */
        const val KIOSK_UNDECIDED = 30

        private const val PENDING = "pending-"
        private const val MID = ".mid"
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss", Locale.ROOT)

        /** Whether a piece's sheet line ([description]) says it is a recording made here. */
        fun isRecording(description: String?): Boolean = description?.startsWith(ComposerNames.RECORDED_LIVE + " · ") == true
    }
}
