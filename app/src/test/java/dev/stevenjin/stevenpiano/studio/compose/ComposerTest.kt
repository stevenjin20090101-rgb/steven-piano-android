// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.compose

import dev.stevenjin.stevenpiano.studio.ModelCatalogue
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * The real composer (v1.7 — M24): `composer-v1.onnx` through ONNX Runtime 1.28.0 for the JVM, driven the
 * app's way ([OrtComposerModel]: prefill, then a token at a time through the key-value cache). From the
 * Bach seed, greedy decoding under the package's masks must give the INT8 file's own 64 tokens, as the
 * Mac and the emulator did in the spike; and a sampled run must carry on through a slide of the window.
 * The model is found where `-PstudioModels=<dir>` (or the environment variable `STEVENPIANO_COMPOSER`,
 * `$STUDIO_WORK/exports`, `~/studio-work/exports`) says; without it these are skipped.
 */
class ComposerTest {
    companion object {
        private var file: File? = null

        @BeforeClass
        @JvmStatic
        fun find() {
            file = ComposerFixtures.modelFile()
        }
    }

    private fun model(): File {
        assumeTrue("composer-v1.onnx not found (-PstudioModels=<dir>, or STEVENPIANO_COMPOSER=<file>): skipped", file != null)
        return file!!
    }

    @Test
    fun `greedy through the cache, the INT8 model gives the fixture's own 64 tokens`() {
        val file = model()
        assertEquals("the pinned file", ModelCatalogue.composer.sha256, VerifiedDownloader.sha256Of(file))
        OrtComposerModel(file).use { model ->
            val seed = AmtTokenizer.decode(ComposerFixtures.inputTokens)
            val started = System.nanoTime()
            val out = Sampler(model).generate(seed, SamplingSettings.Greedy, budget = 64)
            println("ComposerTest: 64 greedy tokens in %.0f ms (with the 214-token prefill)".format((System.nanoTime() - started) / 1e6))
            assertArrayEquals(ComposerFixtures.int8Continuation, out.tokens)
            assertEquals(0, out.slides)
            // PyTorch's own 64 agree up to token 45, where its top two were 0.15 logits apart (docs/STUDIO_SPIKE.md).
            val torch = ComposerFixtures.torchContinuation
            assertEquals(45, torch.indices.first { torch[it] != out.tokens[it] })

            // The prompt builder's seed of the Bach file is the same, and so is what follows it.
            val prompt = PromptBuilder.build(SeedPiece("Prelude in C major", "Johann Sebastian Bach", ComposerFixtures.bach), ComposeRequest())
            val again = Sampler(model).generate(prompt.events, SamplingSettings.Greedy, budget = 64)
            assertArrayEquals(ComposerFixtures.int8Continuation, again.tokens)
        }
    }

    @Test
    fun `sampling carries on through a slide of the window, piano notes and rests only`() {
        val file = model()
        OrtComposerModel(file).use { model ->
            val prompt = PromptBuilder.build(SeedPiece("Prelude in C major", "Johann Sebastian Bach", ComposerFixtures.bach), ComposeRequest(Mood.Bright))
            val started = System.nanoTime()
            val out = Sampler(model, Random(24)).generate(prompt.events, prompt.sampling, budget = 1_500)
            val ms = (System.nanoTime() - started) / 1e6
            println(
                "ComposerTest: %d tokens sampled in %.0f ms (%.2f ms a token), %d slides, %d events to %.1f s, stop %s"
                    .format(out.tokens.size, ms, ms / out.tokens.size, out.slides, out.events.size, AmtTokenizer.maxTime(out.events) / 100.0, out.stop),
            )
            assertEquals(Stop.Budget, out.stop)
            assertEquals(500, out.events.size)
            assertTrue("the seed's 214 positions and 1,500 tokens can't fit in 1,024", out.slides >= 1)
            var current = prompt.currentTime
            for (e in out.events) {
                assertTrue("$e before $current", e.time >= current)
                assertTrue(e.duration in 0 until Amt.MAX_DUR)
                assertTrue("$e", e.isRest || e.instrument == 0)
                current = e.time
            }
            assertTrue("mostly notes", out.events.count { !it.isRest } > 400)
        }
    }
}
