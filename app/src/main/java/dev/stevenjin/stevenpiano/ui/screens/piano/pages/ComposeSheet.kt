// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.studio.ComposeOrder
import dev.stevenjin.stevenpiano.studio.ModelCatalogue
import dev.stevenjin.stevenpiano.studio.SeedChoice
import dev.stevenjin.stevenpiano.studio.StudioSupport
import dev.stevenjin.stevenpiano.studio.compose.ComposeFailures
import dev.stevenjin.stevenpiano.studio.compose.ComposeRequest
import dev.stevenjin.stevenpiano.studio.compose.Mood
import dev.stevenjin.stevenpiano.studio.compose.MusicKey
import dev.stevenjin.stevenpiano.studio.compose.PromptBuilder
import dev.stevenjin.stevenpiano.ui.StudioCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.PieceSearch
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SheetChip
import dev.stevenjin.stevenpiano.ui.components.SheetNote
import dev.stevenjin.stevenpiano.ui.components.StepperButtons
import dev.stevenjin.stevenpiano.ui.components.StepperRow

/** What the sheet knows of its seed: still reading it, none to be had (an empty library), or the piece and its key and tempo. */
private sealed interface Seed {
    data object Reading : Seed

    data object None : Seed

    data class Ready(val choice: SeedChoice) : Seed
}

/**
 * Compose a piece (DESIGN.md › v1.7 — M24), in a sheet with its drag handle, from the Studio page's
 * COMPOSE and the Library's + sheet. The eyebrow names the seed ("In the manner of Clair de lune
 * (Claude Debussy)") over **Compose a piece**; MOOD, four chips (Calm · Bright · Wild · Melancholy:
 * Melancholy turns the key to the seed's minor while no key has been chosen); KEY, the twelve tonics in
 * the mode's own spelling and Major · Minor; TEMPO, 40–200 bpm, the seed's own until changed; LENGTH,
 * 1–5 minutes; IN THE MANNER OF, the piece (the one played last by default) and Change, a search over
 * the library; the note ("Runs on this tablet. About a minute for a two-minute piece.", and that the
 * model downloads first while it isn't here); Cancel and **Compose**, which queues the job and closes
 * the sheet. A new seed brings its own key and tempo. The seed is only ever a piece of the library, and
 * nothing typed reaches the model: the search only chooses among pieces.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeSheet(onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    val studio = graph.studio
    val installed by studio.models.installed.collectAsStateWithLifecycle()
    val support by studio.availability.support.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { studio.availability.check() }

    var chosenPiece by rememberSaveable { mutableStateOf<Long?>(null) }
    var mood by rememberSaveable { mutableStateOf(Mood.Calm) }
    // Null until chosen here: the key and tempo follow the seed (and the key the mood) until then.
    var chosenKey by rememberSaveable { mutableStateOf<Int?>(null) }
    var chosenBpm by rememberSaveable { mutableStateOf<Int?>(null) }
    var minutes by rememberSaveable { mutableIntStateOf(DEFAULT_MINUTES) }
    var choosing by rememberSaveable { mutableStateOf(false) }

    val seed by produceState<Seed>(Seed.Reading, chosenPiece) {
        value = Seed.Reading
        value = studio.seedChoice(chosenPiece)?.let { Seed.Ready(it) } ?: Seed.None
    }
    val choice = (seed as? Seed.Ready)?.choice
    val facts = choice?.facts
    val key = chosenKey?.let { MusicKey.all[it] } ?: facts?.let { PromptBuilder.suggestedKey(mood, it.key) }
    val bpm = chosenBpm ?: facts?.bpm

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Eyebrow(
                choice?.let { "In the manner of ${PromptBuilder.mannerOf(it.title, it.composer)}" } ?: "Studio",
                Modifier.padding(horizontal = 16.dp),
            )
            Text(
                "Compose a piece",
                Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            SectionEyebrow("Mood")
            ChipRow {
                for (option in Mood.entries) SheetChip(option.label, option == mood) { mood = option }
            }

            SectionEyebrow("Key")
            ChipRow {
                for (tonic in 0..11) {
                    val option = MusicKey(tonic, key?.minor ?: false)
                    SheetChip(option.tonicName, key?.tonic == tonic, description = spoken(option.label), enabled = key != null) {
                        chosenKey = MusicKey.all.indexOf(option)
                    }
                }
            }
            ChipRow {
                for (minor in listOf(false, true)) {
                    SheetChip(if (minor) "Minor" else "Major", key?.minor == minor, enabled = key != null) {
                        key?.let { chosenKey = MusicKey.all.indexOf(MusicKey(it.tonic, minor)) }
                    }
                }
            }

            SectionEyebrow("Tempo")
            val shownBpm = bpm ?: PromptBuilder.MIN_BPM
            StepperRow(
                "Tempo",
                unit = "BPM",
                enabled = bpm != null,
                description = "Tempo, $shownBpm beats a minute",
                note = facts?.let { if (bpm == it.bpm) "The piece's own tempo" else "The piece's own: ${it.bpm} bpm" },
            ) {
                StepperButtons(
                    shown = bpm?.toString() ?: "–",
                    canDown = bpm != null && shownBpm > PromptBuilder.MIN_BPM,
                    canUp = bpm != null && shownBpm < PromptBuilder.MAX_BPM,
                    downLabel = "Slower, $shownBpm beats a minute",
                    upLabel = "Faster, $shownBpm beats a minute",
                    onDown = { chosenBpm = (shownBpm - 1).coerceAtLeast(PromptBuilder.MIN_BPM) },
                    onUp = { chosenBpm = (shownBpm + 1).coerceAtMost(PromptBuilder.MAX_BPM) },
                )
            }

            SectionEyebrow("Length")
            StepperRow("Length", unit = "MIN", description = "Length, $minutes ${if (minutes == 1) "minute" else "minutes"}") {
                StepperButtons(
                    shown = minutes.toString(),
                    canDown = minutes > PromptBuilder.MIN_MINUTES,
                    canUp = minutes < PromptBuilder.MAX_MINUTES,
                    downLabel = "Shorter, $minutes ${if (minutes == 1) "minute" else "minutes"}",
                    upLabel = "Longer, $minutes ${if (minutes == 1) "minute" else "minutes"}",
                    onDown = { minutes = (minutes - 1).coerceAtLeast(PromptBuilder.MIN_MINUTES) },
                    onUp = { minutes = (minutes + 1).coerceAtMost(PromptBuilder.MAX_MINUTES) },
                )
            }

            SectionEyebrow("In the manner of")
            when (val current = seed) {
                Seed.Reading -> SheetNote("Reading the piece…")
                Seed.None -> SheetNote(ComposeFailures.EMPTY_LIBRARY)
                is Seed.Ready -> SeedRow(current.choice, choosing) { choosing = !choosing }
            }
            if (choosing) {
                PieceSearch(chosenId = choice?.pieceId) { id ->
                    // A new seed brings its own key and tempo.
                    chosenPiece = id
                    chosenKey = null
                    chosenBpm = null
                    choosing = false
                }
            }

            NoteLine(
                StudioCopy.withDownload(StudioCopy.COMPOSE_NOTE, ModelCatalogue.composer, installed),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.widthIn(min = 8.dp))
                ActionButton(
                    "Compose",
                    onClick = {
                        val ready = choice ?: return@ActionButton
                        studio.compose(ComposeOrder(ready.pieceId, ComposeRequest(mood, key, bpm, minutes)), name = ready.title)
                        onDismiss()
                    },
                    enabled = choice != null && key != null && bpm != null && support != StudioSupport.NoRuntime && support != StudioSupport.TooLittleMemory,
                    description = choice?.let { "Compose a piece in the manner of ${it.title}" },
                )
            }
        }
    }
}

/** A wrapping row of chips, as the schedule editor's. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { selectableGroup() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
    HairlineDivider(startInset = 16.dp)
}

/** The seed: its title, then its composer, key and tempo; Change opens the search (Close shuts it). */
@Composable
private fun SeedRow(choice: SeedChoice, choosing: Boolean, onChange: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        ) {
            Text(choice.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Eyebrow(
                listOfNotNull(choice.composer, choice.facts.key.label, "${choice.facts.bpm} bpm").joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                uppercase = false,
            )
        }
        Spacer(Modifier.width(16.dp))
        ActionButton(if (choosing) "Close" else "Change", onClick = onChange, description = if (choosing) "Close the search" else "Choose another piece")
    }
    HairlineDivider(startInset = 16.dp)
}

/** "F♯ major" as TalkBack says it: "F sharp major". */
private fun spoken(label: String): String = label.replace("♯", " sharp").replace("♭", " flat")

/** Two minutes: what the note's "about a minute" is about. */
private const val DEFAULT_MINUTES = 2
