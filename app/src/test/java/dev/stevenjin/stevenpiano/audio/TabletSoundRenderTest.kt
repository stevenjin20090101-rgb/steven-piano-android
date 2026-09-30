// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.midi.SmfParser
import dev.stevenjin.stevenpiano.studio.WavReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The evidence the emulator can't give (v1.8 — M25): Clair de lune (piano-midi.de's, the library's) played
 * by the app's engine into the tablet's sampler on the real SoundFont, the Upright Piano KW, and written as
 * the output writes it, a 48 kHz stereo WAV to listen to. Runs only with `-PpianoSound=<the .sf2>`
 * (`-PpianoRender=<file.wav>` for where it goes; `-PpianoRenderMidi=<file.mid>` for another piece); the WAV
 * reads back through the app's [WavReader] at the piece's length, never clipped, at a sensible level, and the
 * figures (how fast it rendered, the most voices at once) are printed for BUILD_SPEC.
 */
class TabletSoundRenderTest {
    @Test
    fun `Clair de lune on the Upright Piano KW, rendered as the tablet plays it`() {
        val sf2 = System.getProperty("stevenpiano.pianoSound")?.let(::File)
        assumeTrue("Run with -PpianoSound=<upright-piano-kw-v1.sf2> to render with the real SoundFont", sf2 != null && sf2.isFile)
        val midi = File(System.getProperty("stevenpiano.pianoRenderMidi") ?: "../../midi/piano-midi.de/debussy/deb_clai.mid")
        assumeTrue("${midi.path} not found", midi.isFile)
        val out = File(System.getProperty("stevenpiano.pianoRender") ?: "build/tablet-sound/${midi.nameWithoutExtension}.wav")
        val rate = 48_000

        val loadStarted = System.nanoTime()
        val font = Sf2Reader.read(sf2!!)
        val loadMs = (System.nanoTime() - loadStarted) / 1e6
        val piece = SmfParser.parse(midi.readBytes())
        val renderStarted = System.nanoTime()
        val mono = OfflineRender.piece(font, piece, rate, volume = Sampler.DEFAULT_VOLUME)
        val renderSeconds = (System.nanoTime() - renderStarted) / 1e9
        OfflineRender.writeWav(out, mono, rate)

        val seconds = mono.size.toDouble() / rate
        val back = out.inputStream().buffered().use { WavReader.decode(it, fileBytes = out.length()) }
        assertEquals(rate, back.sourceRate)
        assertEquals(2, back.sourceChannels)
        assertEquals(seconds, back.seconds, 0.01)
        assertTrue("the piece and its tail", seconds > piece.durationMicros / 1e6)
        val peak = OfflineRender.peak(mono)
        val rms = OfflineRender.rms(mono)
        val clipped = mono.count { kotlin.math.abs(it) >= 32_767f / 32_768f }
        assertEquals("no sample at full scale", 0, clipped)
        assertTrue("peak $peak", peak in 0.05f..0.999f)
        assertTrue("rms $rms", rms in 0.005f..0.3f)
        println(
            String.format(
                Locale.ROOT,
                "TabletSoundRender: %s, %d regions, read in %.0f ms; %s: %.1f s rendered in %.2f s (%.0f× real time) at %d Hz; " +
                    "peak %.3f (%.1f dBFS), rms %.4f (%.1f dBFS), at most %d voices; %s (%d bytes)",
                font.name, font.regions.size, loadMs, midi.name, seconds, renderSeconds, seconds / renderSeconds, rate,
                peak, 20 * kotlin.math.log10(peak.toDouble()), rms, 20 * kotlin.math.log10(rms.toDouble()), OfflineRender.maxVoices,
                out.absolutePath, out.length(),
            ),
        )
    }
}
