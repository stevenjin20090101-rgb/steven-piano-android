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
import dev.stevenjin.stevenpiano.data.art.CoverInput
import dev.stevenjin.stevenpiano.data.art.CoverNote
import dev.stevenjin.stevenpiano.data.art.Png
import dev.stevenjin.stevenpiano.data.art.StudioCover
import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import dev.stevenjin.stevenpiano.data.imports.TitleHeuristics
import dev.stevenjin.stevenpiano.midi.SmfWriter
import dev.stevenjin.stevenpiano.studio.compose.ComposeFailures
import dev.stevenjin.stevenpiano.studio.compose.Composition
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToLong

/** What Studio needs of the library: a new piece in, its line on the piece sheet, and the piece out again (Discard). */
interface StudioLibrary {
    /**
     * Imports the MIDI file [bytes] as a piece called [title] by [composer], through the importer's own
     * path ([fileName] names the file), quietly (v1.14 — M37: no import bar); its id, or null when it wasn't added.
     */
    suspend fun add(fileName: String, bytes: ByteArray, title: String, composer: String): Long?

    /** [description] becomes the piece's own line on its sheet (an artwork row with no source). */
    suspend fun describe(pieceId: Long, description: String)

    /** The piece leaves the library, the player (silenced first if it plays it) and its artwork. */
    suspend fun discard(pieceId: Long)

    /** Whether the piece is still in the library (it may have been deleted from its menu meanwhile). */
    suspend fun exists(pieceId: Long): Boolean

    /** [png] becomes the piece's own cover (v1.12 — M30); false when it could not be kept. */
    suspend fun setCover(pieceId: Long, png: ByteArray): Boolean = false

    /** The built-in playlist "Made in Studio" follows Studio's history (v1.12 — M30). */
    suspend fun shelve() = Unit
}

/** A piece Studio made: its library [id] and [title], and its cover's kind when one was drawn ("drawn"). */
data class StudioPiece(val id: Long, val title: String, val cover: String? = null)

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
    suspend fun add(transcription: Transcription, recordingName: String?, at: ZonedDateTime, coverSeed: Long = 0L): StudioPiece {
        if (transcription.notes.isEmpty()) throw StudioFailure(StudioFailures.NO_NOTES)
        val title = title(recordingName, at)
        val bytes = midi(transcription, title, "${ComposerNames.STUDIO}, ${STAMP.format(at)}")
        val fileName = title.replace('/', '-').replace('\\', '-') + ".mid"
        val id = library.add(fileName, bytes, title, ComposerNames.STUDIO) ?: throw StudioFailure(StudioFailures.NOT_SAVED)
        library.describe(id, description(at))
        val notes = transcription.notes.map { CoverNote((it.onset * 1000).toLong(), (it.offset * 1000).toLong(), it.pitch, it.velocity) }
        val drawn = cover(id, CoverInput(notes, null, null, null, coverSeed))
        return StudioPiece(id, title, if (drawn) DRAWN else null)
    }

    /**
     * A composition into a piece of the library (v1.7 — M24): "Composition · Sep 28, 2026 2:05 PM" by
     * [ComposerNames.STUDIO], its notes written at the tempo it was composed at ([Composition.bpm]) with
     * "Made in Studio, <time>" as its text, then described "Made in Studio · in the manner of [mannerOf]"
     * ([compositionDescription]). One without a note is refused ([ComposeFailures.NO_MUSIC]); one the
     * library couldn't take is [StudioFailures.NOT_SAVED].
     */
    suspend fun addComposition(
        composition: Composition,
        mannerOf: String,
        at: ZonedDateTime,
        title: String = compositionTitle(at),
        cover: CoverInput? = null,
    ): StudioPiece {
        if (composition.notes.isEmpty()) throw StudioFailure(ComposeFailures.NO_MUSIC)
        val bytes = SmfWriter.write(
            notes = composition.notes,
            title = title,
            text = "${ComposerNames.STUDIO}, ${STAMP.format(at)}",
            tempoMicros = SmfWriter.tempoOf(composition.bpm),
        )
        val fileName = title.replace('/', '-').replace('\\', '-') + ".mid"
        val id = library.add(fileName, bytes, title, ComposerNames.STUDIO) ?: throw StudioFailure(StudioFailures.NOT_SAVED)
        library.describe(id, compositionDescription(mannerOf))
        val drawn = cover != null && cover(id, cover)
        return StudioPiece(id, title, if (drawn) DRAWN else null)
    }

    /** "Made in Studio" brought up to date; a failure changes nothing (the next refresh tries again). */
    suspend fun shelve() {
        try {
            library.shelve()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The playlist catches up at the next start or import.
        }
    }

    /**
     * Piece [pieceId]'s own cover, drawn from [input] (v1.12 — M30): [StudioCover] at 768 px, a PNG, kept as the
     * piece's artwork. False when it could not be drawn or kept: the piece is saved all the same.
     */
    suspend fun cover(pieceId: Long, input: CoverInput): Boolean = try {
        library.setCover(pieceId, Png.encode(StudioCover.render(input), StudioCover.SIZE, StudioCover.SIZE))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    } catch (e: OutOfMemoryError) {
        false
    }

    /** "Composition · Sep 28, 2026 2:05 PM": the date and the time in the device's own style. */
    fun compositionTitle(at: ZonedDateTime): String = "Composition · ${date(at)} ${TIME.withLocale(locale).format(at)}"

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
        /** A cover drawn from the music. */
        const val DRAWN = "drawn"

        /** A transcription's line in the history: "Transcription of Clair de lune.m4a" (the recording's own name). */
        fun transcribeLine(recordingName: String): String = "Transcription of ${TextLimits.clip(recordingName, TextLimits.TITLE)}"

        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        private const val MANNER = "${ComposerNames.STUDIO} · in the manner of "

        /** A composition's line on its sheet: "Made in Studio · in the manner of Clair de lune (Claude Debussy)". */
        fun compositionDescription(mannerOf: String): String = MANNER + mannerOf

        /** What a sheet's [description] says the piece is in the manner of, when a composition's: "Clair de lune (Claude Debussy)". */
        fun mannerOf(description: String?): String? =
            description?.takeIf { it.startsWith(MANNER) }?.removePrefix(MANNER)?.trim()?.takeIf { it.isNotEmpty() }

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
