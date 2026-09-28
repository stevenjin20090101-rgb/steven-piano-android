// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.data.TextLimits
import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import dev.stevenjin.stevenpiano.data.imports.TitleHeuristics
import dev.stevenjin.stevenpiano.midi.SmfWriter
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToLong

/** What Studio needs of the library: a new piece in, its line on the piece sheet, and the piece out again (Discard). */
interface StudioLibrary {
    /**
     * Imports the MIDI file [bytes] as a piece called [title] by [composer], through the importer's own
     * path ([fileName] names the file); its id, or null when it wasn't added.
     */
    suspend fun add(fileName: String, bytes: ByteArray, title: String, composer: String): Long?

    /** [description] becomes the piece's own line on its sheet (an artwork row with no source). */
    suspend fun describe(pieceId: Long, description: String)

    /** The piece leaves the library, the player (silenced first if it plays it) and its artwork. */
    suspend fun discard(pieceId: Long)
}

/** A piece Studio made: its library [id] and [title]. */
data class StudioPiece(val id: Long, val title: String)

/**
 * A transcription into a piece of the library (v1.7 — M23): the notes and pedal written as a MIDI file
 * ([SmfWriter]: the title as its track name, "Made in Studio, <time>" as its text, so no two are the
 * same file; the text in plain ASCII, as other programs read it), imported through the importer with
 * the title the recording's file name gives
 * (without its extension; "Recording · Sep 28, 2026" when it has none) and the composer
 * [ComposerNames.STUDIO], then described "Made in Studio · Sep 28, 2026" on its sheet (the date in
 * the device's own style). A transcription without a note is refused ([StudioFailures.NO_NOTES]); one
 * the library couldn't take is [StudioFailures.NOT_SAVED].
 */
class StudioPieces(private val library: StudioLibrary, private val locale: Locale = Locale.getDefault()) {
    suspend fun add(transcription: Transcription, recordingName: String?, at: ZonedDateTime): StudioPiece {
        if (transcription.notes.isEmpty()) throw StudioFailure(StudioFailures.NO_NOTES)
        val title = title(recordingName, at)
        val bytes = midi(transcription, title, "${ComposerNames.STUDIO}, ${STAMP.format(at)}")
        val fileName = title.replace('/', '-').replace('\\', '-') + ".mid"
        val id = library.add(fileName, bytes, title, ComposerNames.STUDIO) ?: throw StudioFailure(StudioFailures.NOT_SAVED)
        library.describe(id, description(at))
        return StudioPiece(id, title)
    }

    /** The piece's title: the recording's name without its extension, or "Recording · <date>". */
    fun title(recordingName: String?, at: ZonedDateTime): String {
        val base = recordingName?.substringAfterLast('/')?.let { name -> if ('.' in name.drop(1)) name.substringBeforeLast('.') else name }
        val clean = base?.let { TextLimits.clip(TitleHeuristics.cleanText(it), TextLimits.TITLE) }.orEmpty()
        return clean.ifBlank { "Recording · ${date(at)}" }
    }

    /** "Made in Studio · Sep 28, 2026". */
    fun description(at: ZonedDateTime): String = "${ComposerNames.STUDIO} · ${date(at)}"

    private fun date(at: ZonedDateTime): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(at)

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

        /** The notes and pedal as a MIDI file: onset and offset to the microsecond, velocities held to 1–127. */
        fun midi(transcription: Transcription, title: String, text: String): ByteArray = SmfWriter.write(
            notes = transcription.notes.map { SmfWriter.Note(micros(it.onset), micros(it.offset), it.pitch, it.velocity) },
            pedals = transcription.pedals.map { SmfWriter.Pedal(micros(it.onset), micros(it.offset)) },
            title = title,
            text = text,
        )

        private fun micros(seconds: Float): Long =
            if (seconds.isFinite()) (seconds.toDouble() * 1_000_000).roundToLong().coerceAtLeast(0) else 0L
    }
}
