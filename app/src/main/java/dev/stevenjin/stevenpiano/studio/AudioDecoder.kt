// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import android.content.ContentResolver
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteOrder

/** A recording to transcribe: a document the person picked (its read grant kept for the job), or a file of the app's own (a web upload). */
sealed interface AudioSource {
    data class Document(val uri: Uri) : AudioSource

    data class Local(val file: File) : AudioSource
}

/**
 * Any recording the device can decode, as 16 kHz mono floats for the transcription model (v1.7 — M23):
 * WAV files through [WavReader] (the path the JVM tests cover), everything else (m4a, mp3, flac, ogg,
 * opus…) through Android's [MediaExtractor] and [MediaCodec], its PCM (16-bit or float) made mono and
 * resampled as it comes ([MonoTo16k]). Refused before decoding: files over [AudioLimits.MAX_FILE_BYTES],
 * and recordings whose container says they last over [AudioLimits.MAX_SECONDS]; what decodes past that
 * is cut off with the same refusal. Anything that can't be read is [AudioFailure.UNREADABLE]. Blocking,
 * on the job's own thread; [cancelled] is asked between buffers.
 */
class AudioDecoder(private val resolver: ContentResolver) : RecordingReader {
    override fun decode(source: AudioSource, cancelled: () -> Boolean): DecodedAudio {
        if (sizeOf(source) > AudioLimits.MAX_FILE_BYTES) throw AudioFailure(AudioFailure.TOO_LARGE)
        val wav = try {
            open(source).use { input -> ByteArray(12).let { header -> readHeader(input, header) && WavReader.isWav(header) } }
        } catch (e: IOException) {
            throw AudioFailure(AudioFailure.UNREADABLE, e)
        } catch (e: SecurityException) {   // the picker's grant is gone
            throw AudioFailure(AudioFailure.UNREADABLE, e)
        }
        return if (wav) {
            try {
                BufferedInputStream(open(source), BUFFER).use { WavReader.decode(it, cancelled) }
            } catch (e: IOException) {
                throw AudioFailure(AudioFailure.UNREADABLE, e)
            }
        } else {
            decodeWithCodec(source, cancelled)
        }
    }

    /** The name the recording goes by (its display name, or the file's), for the piece's title. */
    override fun displayName(source: AudioSource): String? = when (source) {
        is AudioSource.Local -> source.file.name
        is AudioSource.Document -> runCatching {
            resolver.query(source.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun sizeOf(source: AudioSource): Long = when (source) {
        is AudioSource.Local -> source.file.length()
        is AudioSource.Document -> runCatching {
            resolver.openFileDescriptor(source.uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
    }

    private fun open(source: AudioSource): InputStream = when (source) {
        is AudioSource.Local -> FileInputStream(source.file)
        is AudioSource.Document -> resolver.openInputStream(source.uri) ?: throw FileNotFoundException("No stream")
    }

    private fun readHeader(input: InputStream, header: ByteArray): Boolean {
        var got = 0
        while (got < header.size) {
            val n = input.read(header, got, header.size - got)
            if (n < 0) return false
            got += n
        }
        return true
    }

    private fun decodeWithCodec(source: AudioSource, cancelled: () -> Boolean): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val descriptor = (source as? AudioSource.Document)?.let {
            try {
                resolver.openFileDescriptor(it.uri, "r")
            } catch (e: Exception) {
                extractor.release()
                throw AudioFailure(AudioFailure.UNREADABLE, e)
            } ?: run {
                extractor.release()
                throw AudioFailure(AudioFailure.UNREADABLE)
            }
        }
        try {
            when (source) {
                is AudioSource.Local -> extractor.setDataSource(source.file.path)
                is AudioSource.Document -> extractor.setDataSource(descriptor!!.fileDescriptor)
            }
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioFailure(AudioFailure.UNREADABLE)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            if (durationUs > AudioLimits.MAX_SECONDS * 1_000_000L) throw AudioFailure(AudioFailure.TOO_LONG)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            return drain(extractor, codec, durationUs, cancelled)
        } catch (e: AudioFailure) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw AudioFailure(AudioFailure.UNREADABLE, e)
        } catch (e: IllegalStateException) {   // MediaCodec.CodecException among them
            throw AudioFailure(AudioFailure.UNREADABLE, e)
        } catch (e: IllegalArgumentException) {
            throw AudioFailure(AudioFailure.UNREADABLE, e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            runCatching { descriptor?.close() }
        }
    }

    /** Feeds the extractor's samples to the codec and hands its PCM to [MonoTo16k] until the end of the stream. */
    private fun drain(extractor: MediaExtractor, codec: MediaCodec, durationUs: Long, cancelled: () -> Boolean): DecodedAudio {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var sink: MonoTo16k? = null
        var channels = 1
        var floatPcm = false
        var samples = FloatArray(0)
        while (true) {
            if (cancelled()) throw CancellationException("The recording's reading was cancelled")
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index < 0) continue   // try again later, or the output format changed (read below with the first buffer)
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val current = sink ?: codec.outputFormat.let { out ->
                    val rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) && out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    val frames = if (durationUs > 0) durationUs * rate / 1_000_000L else rate.toLong() * 60
                    MonoTo16k(rate, channels, frames).also { sink = it }
                }
                buffer.position(info.offset).limit(info.offset + info.size)
                val ordered = buffer.order(ByteOrder.nativeOrder())
                val count = if (floatPcm) info.size / 4 else info.size / 2
                if (samples.size < count) samples = FloatArray(count)
                if (floatPcm) {
                    ordered.asFloatBuffer().get(samples, 0, count)
                } else {
                    val shorts = ordered.asShortBuffer()
                    for (i in 0 until count) samples[i] = shorts.get(i) / 32768f
                }
                current.frames(samples, count / channels)
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        return (sink ?: throw AudioFailure(AudioFailure.EMPTY)).finish()
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val BUFFER = 64 * 1024
    }
}
