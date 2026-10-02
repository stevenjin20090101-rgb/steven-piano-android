// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.ui.screens.studio

import android.net.Uri
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.data.TextLimits
import dev.stevenjin.stevenpiano.data.art.ArtSize
import dev.stevenjin.stevenpiano.data.art.CoverInput
import dev.stevenjin.stevenpiano.data.art.StudioCover
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.studio.JobKind
import dev.stevenjin.stevenpiano.studio.JobState
import dev.stevenjin.stevenpiano.studio.JobStep
import dev.stevenjin.stevenpiano.studio.ModelCatalogue
import dev.stevenjin.stevenpiano.studio.ModelEntry
import dev.stevenjin.stevenpiano.studio.StudioJob
import dev.stevenjin.stevenpiano.studio.TurnRecord
import dev.stevenjin.stevenpiano.studio.compose.PreviewRoll
import dev.stevenjin.stevenpiano.studio.style.StylePrompt
import dev.stevenjin.stevenpiano.studio.style.StyleResult
import dev.stevenjin.stevenpiano.ui.KioskGateSheet
import dev.stevenjin.stevenpiano.ui.LocalFloatingPadding
import dev.stevenjin.stevenpiano.ui.LocalIdleState
import dev.stevenjin.stevenpiano.ui.StudioCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ArtFrame
import dev.stevenjin.stevenpiano.ui.components.ArtworkImage
import dev.stevenjin.stevenpiano.ui.components.AuraHairline
import dev.stevenjin.stevenpiano.ui.components.AuraRing
import dev.stevenjin.stevenpiano.ui.components.AuraState
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.FilledButton
import dev.stevenjin.stevenpiano.ui.components.GlassHeaderPane
import dev.stevenjin.stevenpiano.ui.components.GlassSheet
import dev.stevenjin.stevenpiano.ui.components.GlassSurface
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.RollingText
import dev.stevenjin.stevenpiano.ui.components.ScreenHeader
import dev.stevenjin.stevenpiano.ui.components.SheetChip
import dev.stevenjin.stevenpiano.ui.components.easedIn
import dev.stevenjin.stevenpiano.ui.components.pressScale
import dev.stevenjin.stevenpiano.ui.components.readingWidth
import dev.stevenjin.stevenpiano.ui.components.secondaryText
import dev.stevenjin.stevenpiano.ui.rememberKioskGate
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** What the system's document picker offers for a recording: any audio, and Ogg some providers label as an application. */
private val RecordingTypes = arrayOf("audio/*", "application/ogg")

/** The system's document picker for one recording; [onPicked] hears the document chosen. */
@Composable
fun rememberRecordingPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onPicked(uri) }
    return remember(launcher) { { launcher.launch(RecordingTypes) } }
}

/** The options sheet's opening: its first choices, and what the history keeps of the idea it came from. */
private class OptionsOpen(val start: ComposeStart, val turn: TurnRecord)

/**
 * The Studio tab (DESIGN.md › v1.12 — Studio as a tab, › v1.13.1 — the stage): the header "Studio" with its byline
 * and two glyphs, History and Models; under it the stage, one column at reading width centred on the whole width (no
 * second pane on tablets). Idle, the line "What should the piano play?", the idea box in the aura's ring, the live
 * understood line and the suggestions, centred in the space above the bar or the keyboard; while a turn waits or
 * runs, or its result is fresh, the column eases upward (320 ms, a cut under reduced motion) so the box sits in the
 * upper third, and the one card ([StudioStage.card]) stands under it. Every turn is in the History sheet. Nothing but
 * the reason line on a device Studio can't run on. Kiosk mode follows [StudioAccess]: typing ideas is free; Attach,
 * Models, Keep, Discard and removing a turn ask for the PIN.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(onListen: (Long) -> Unit) {
    val graph = LocalContext.current.graph
    val vm = viewModel { StudioViewModel(graph) }
    val support by vm.support.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val turns by vm.turns.collectAsStateWithLifecycle()
    val memory by vm.stageMemory.collectAsStateWithLifecycle()
    val understood by vm.understood.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val favourite by vm.favourite.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val nothingYet by vm.nothingYet.collectAsStateWithLifecycle()
    val gate = rememberKioskGate()
    val context = LocalContext.current
    val pick = rememberRecordingPicker { uri -> vm.transcribe(context, uri) }
    var text by rememberSaveable { mutableStateOf("") }
    var models by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf<OptionsOpen?>(null) }
    LaunchedEffect(Unit) { vm.checkSupport() }
    LaunchedEffect(text) { vm.typed(text) }
    val inSight = stageInSight(vm)

    val guarded: (StudioAction, () -> Unit) -> Unit = { action, block -> if (StudioAccess.needsPin(action, locked)) gate.run(block) else block() }
    val waiting = vm.waiting(jobs)
    val working = jobs.any { it.state == JobState.Running && it.kind != JobKind.Download }
    val unsupported = StudioCopy.unsupported(support)
    val card = remember(turns, memory, inSight) { vm.stageCard(turns, memory, inSight) }
    val adjust: (Turn) -> Unit = { turn -> options = OptionsOpen(vm.startFrom(turn), TurnRecord(understood = "", parentId = turn.rowId)) }

    val outer = LocalFloatingPadding.current
    val direction = LocalLayoutDirection.current
    val sides = PaddingValues(start = outer.calculateStartPadding(direction), end = outer.calculateEndPadding(direction))
    // The card shown during this visit (v1.14 — motion): one sent now arrives rising; one there as Studio opens doesn't.
    val reduced = rememberReducedMotion()
    val visit = remember { StudioVisit() }
    val scroll = rememberScrollState()

    GlassHeaderPane(
        scroll = scroll,
        modifier = Modifier.padding(sides),
        header = {
            ScreenHeader("Studio") {
                if (unsupported == null) {
                    GlyphButton(R.drawable.ic_history, "History") { history = true }
                    GlyphButton(R.drawable.ic_models, "Models") { guarded(StudioAction.Models) { models = true } }
                }
            }
        },
    ) {
        val floating = LocalFloatingPadding.current
        if (unsupported != null) {
            Column(Modifier.fillMaxSize().padding(top = floating.calculateTopPadding())) {
                Column(Modifier.readingWidth()) { NoteLine("$unsupported.") }
            }
            return@GlassHeaderPane
        }
        // The stage keeps clear of the bar below, or of the keyboard while it is up: the column is centred above it.
        val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val clear = (floating.calculateBottomPadding() - ime).coerceAtLeast(0.dp)
        val lift by animateFloatAsState(if (card != null) 1f else 0f, Motion.timed(Motion.EmphasisedMs, reduced), label = "stage")
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(top = floating.calculateTopPadding(), bottom = clear)) {
            StageColumn(
                height = maxHeight,
                lift = { lift },
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll),
                headline = { Headline() },
                box = {
                    PromptBar(
                        text = text,
                        onText = { text = it },
                        canSend = StudioAccess.canSend(text, waiting) && library != null,
                        working = working,
                        onAttach = { guarded(StudioAction.Attach, pick) },
                        onOptions = { options = OptionsOpen(vm.startFrom(understood), TurnRecord(prompt = text.takeIf { it.isNotBlank() }, unused = understood?.unused.orEmpty())) },
                        onSend = { vm.send(text) { text = "" } },
                    )
                },
                understood = { UnderstoodLine(understood?.takeIf { text.isNotBlank() }, locked) },
                below = {
                    if (card != null) {
                        StageCard(
                            card,
                            modifier = Modifier.easedIn(rememberArrival(card, visit, reduced), rise = ARRIVAL_RISE),
                            locked = locked,
                            waiting = waiting,
                            installed = installed,
                            jobs = jobs,
                            preview = vm.preview,
                            onListen = { card.pieceId?.let(onListen) },
                            onKeep = { guarded(StudioAction.Keep) { card.pieceId?.let(vm::keep) } },
                            onDiscard = { guarded(StudioAction.Discard) { card.pieceId?.let(vm::discard) } },
                            onAgain = { vm.again(card) },
                            onAdjust = { adjust(card) },
                            onCancel = { vm.cancel(card) },
                        )
                    } else {
                        Suggestions(favourite) { chosen -> text = chosen }
                    }
                },
                foot = { if (card == null && nothingYet && turns.isEmpty()) FootLine() },
            )
        }
    }
    if (models) ModelsSheet(vm, installed, jobs, onDismiss = { models = false })
    if (history) {
        HistorySheet(
            StudioStage.history(turns),
            locked = locked,
            waiting = waiting,
            onDismiss = { history = false },
            onListen = { turn ->
                history = false
                turn.pieceId?.let(onListen)
            },
            onAgain = { turn ->
                history = false
                vm.again(turn)
            },
            onAdjust = { turn ->
                history = false
                adjust(turn)
            },
            onKeep = { turn -> guarded(StudioAction.Keep) { turn.pieceId?.let(vm::keep) } },
            onDiscard = { turn -> guarded(StudioAction.Discard) { turn.pieceId?.let(vm::discard) } },
            onRemove = { turn -> guarded(StudioAction.DeleteTurn) { vm.remove(turn) } },
            onCancel = vm::cancel,
        )
    }
    options?.let { open -> ComposeSheet(onDismiss = { options = null }, start = open.start, turn = open.turn) }
    KioskGateSheet(gate)
}

/**
 * The one-minute rule's eyes ([StageMemory]): whether the stage is in sight. The tab is away while its screen is
 * stopped (another tab, the app in the background) or the resting screen covers it; the view model hears each going
 * and coming back.
 */
@Composable
private fun stageInSight(vm: StudioViewModel): Boolean {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resting = LocalIdleState.current?.idle == true
    val inSight = lifecycle.isAtLeast(Lifecycle.State.STARTED) && !resting
    DisposableEffect(vm, inSight) {
        if (inSight) vm.stageShown()
        onDispose { if (inSight) vm.stageLeft() }
    }
    return inSight
}

/** The turns one visit to Studio has shown, and when it began: what is there as it opens is no arrival. */
private class StudioVisit {
    private val start = SystemClock.uptimeMillis()
    val shown = HashSet<String>()

    /** Past the visit's first moments, when its history has come: a turn appearing now is a new one. */
    fun settled(): Boolean = SystemClock.uptimeMillis() - start > VISIT_SETTLE_MS
}

/**
 * A turn's card arriving (v1.14 — motion): a turn that appears while Studio is open (sent, Another like it, Try
 * again), made in the last [ARRIVAL_MS], fades in and rises 12 dp over 320 ms, decelerating, once ([easedIn]). Shown
 * under its key and its job's ([StudioVisit.shown]), so a turn that becomes its history row doesn't arrive twice. The
 * history, a turn scrolled back into view, and everything under reduced motion: null, simply there.
 */
@Composable
private fun rememberArrival(turn: Turn, visit: StudioVisit, reduced: Boolean): Animatable<Float, AnimationVector1D>? {
    val rising = remember(turn.key) {
        val names = listOfNotNull(turn.key, turn.job?.let { "job:${it.id}" })
        val seen = names.any { it in visit.shown }
        visit.shown += names
        // A turn shown from its job alone has no row yet: it was made just now.
        val recent = turn.createdAt == Long.MAX_VALUE || System.currentTimeMillis() - turn.createdAt in 0..ARRIVAL_MS
        if (!seen && recent && visit.settled() && !reduced) Animatable(0f) else null
    }
    if (rising != null) {
        LaunchedEffect(rising) { rising.animateTo(1f, Motion.timed(Motion.EmphasisedMs, reduced = false, easing = Motion.Enter)) }
    }
    return rising
}

private const val ARRIVAL_MS = 5_000L
private const val VISIT_SETTLE_MS = 600L
private val ARRIVAL_RISE = 12.dp

/**
 * The stage's column, laid out by hand in [height] (the space between the header and the bar or the keyboard): the
 * box's middle at [IDLE_AT] of it while idle and at [WORKING_AT] with a card on the stage ([lift] easing from 0 to 1
 * between them), the headline [HEAD_GAP] above the box, the understood line under it, [below] (the suggestions or the
 * card) [BELOW_GAP] under the box or [LINE_GAP] under the line, and [foot] at the bottom; each centred across the
 * width. The gaps keep the text clear of the aura's glow. Taller than [height], it scrolls.
 */
@Composable
private fun StageColumn(
    height: Dp,
    lift: () -> Float,
    modifier: Modifier,
    headline: @Composable () -> Unit,
    box: @Composable () -> Unit,
    understood: @Composable () -> Unit,
    below: @Composable () -> Unit,
    foot: @Composable () -> Unit,
) {
    Layout(contents = listOf(headline, box, understood, below, foot), modifier = modifier) { slots, constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val (head, bar, line, under, end) = slots.map { slot -> slot.map { it.measure(loose) } }
        val room = height.roundToPx()
        val edge = STAGE_EDGE.roundToPx()
        val middle = room * (IDLE_AT + (WORKING_AT - IDLE_AT) * lift())
        val headTop = (middle - bar.tall() / 2f - HEAD_GAP.toPx() - head.tall()).roundToInt().coerceAtLeast(edge)
        val barTop = headTop + head.tall() + HEAD_GAP.roundToPx()
        val lineTop = barTop + bar.tall()
        val belowTop = maxOf(lineTop + BELOW_GAP.roundToPx(), lineTop + line.tall() + LINE_GAP.roundToPx())
        val belowEnd = belowTop + under.tall()
        val footTop = maxOf(room - end.tall() - edge, belowEnd + FOOT_GAP.roundToPx())
        val total = maxOf(room, if (end.isEmpty()) belowEnd + edge else footTop + end.tall() + edge)
        layout(width, total) {
            fun List<Placeable>.at(top: Int) = fold(top) { y, it -> it.place((width - it.width) / 2, y); y + it.height }
            head.at(headTop)
            bar.at(barTop)
            line.at(lineTop)
            under.at(belowTop)
            end.at(footTop)
        }
    }
}

private fun List<Placeable>.tall(): Int = sumOf { it.height }

/** Where the box's middle sits in the stage's height: idle, a little above the middle (the suggestions are under it); with a card, a third of the way down. */
private const val IDLE_AT = 0.46f
private const val WORKING_AT = 1f / 3

/** The stage's spacing: the headline above the box, the suggestions or the card under it (or under the understood line), the foot under them, and the stage's top and bottom edges. */
private val HEAD_GAP = 40.dp
private val BELOW_GAP = 32.dp
private val LINE_GAP = 16.dp
private val FOOT_GAP = 24.dp
private val STAGE_EDGE = 16.dp

/** The stage's question, in the display style. */
@Composable
private fun Headline() {
    Text(
        HEADLINE,
        Modifier.readingWidth().padding(horizontal = 16.dp).semantics { heading() },
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
}

/** Suggestions that fill the box, centred: three ideas and the composer the library holds most pieces by. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(favourite: String?, onChoose: (String) -> Unit) {
    FlowRow(
        Modifier.readingWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val chips = SUGGESTIONS + listOfNotNull(favourite?.let { "In the manner of $it" })
        for (chip in chips) SheetChip(chip, chosen = false, description = "Suggestion: $chip") { onChoose(chip) }
    }
}

/** With nothing made yet, one quiet line at the stage's foot: where pieces go. */
@Composable
private fun FootLine() {
    Text(
        FOOT_LINE,
        Modifier.readingWidth().padding(horizontal = 24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private const val HEADLINE = "What should the piano play?"
private const val FOOT_LINE = "Pieces you make are kept in History and in the Library's Made in Studio playlist."
private val SUGGESTIONS = listOf("Calm and slow", "A bright waltz, 2 minutes", "Stormy and fast, in D minor")

/**
 * The idea box (glass on its solid surface, rounded [BOX_RADIUS], at least [BOX_HEIGHT] tall, in the aura's ring):
 * attach · the idea (one to four lines, 200 characters, a counter only past 160) · Options · Send (a filled circle,
 * off while the box is empty or three ideas wait). Nothing passes beneath it, so its glass doesn't blur.
 */
@Composable
private fun PromptBar(
    text: String,
    onText: (String) -> Unit,
    canSend: Boolean,
    working: Boolean,
    onAttach: () -> Unit,
    onOptions: () -> Unit,
    onSend: () -> Unit,
) {
    val idle = LocalIdleState.current
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    val shape = RoundedCornerShape(BOX_RADIUS)
    val aura = if (working) AuraState.Working else if (focused) AuraState.Focused else AuraState.Rest
    AuraRing(aura, shape, Modifier.readingWidth().padding(horizontal = 16.dp)) {
        GlassSurface(Modifier.fillMaxWidth(), shape = shape, blur = false) {
            Row(Modifier.fillMaxWidth().heightIn(min = BOX_HEIGHT).padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                GlyphButton(R.drawable.ic_attach, StudioCopy.TRANSCRIBE, onClick = onAttach)
                Box(Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(
                        value = text,
                        onValueChange = { value ->
                            onText(TextLimits.clip(value, StylePrompt.MAX_CHARS))
                            idle?.touch()   // typing counts as activity: the resting screen stays away
                        },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Describe a piece" },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        minLines = 1,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                        interactionSource = focus,
                        decorationBox = { field ->
                            if (text.isEmpty()) Text("Describe a piece…", style = MaterialTheme.typography.bodyLarge, color = secondaryText())
                            field()
                        },
                    )
                }
                val count = text.codePointCount(0, text.length)
                if (count > COUNTER_FROM) {
                    Text(
                        "$count/${StylePrompt.MAX_CHARS}",
                        Modifier.padding(bottom = 14.dp, start = 4.dp),
                        style = MaterialTheme.typography.labelSmall.merge(Tabular),
                        color = secondaryText(),
                    )
                }
                GlyphButton(R.drawable.ic_tune, "Options", onClick = onOptions)
                SendButton(canSend, onSend)
            }
        }
    }
}

private val BOX_HEIGHT = 64.dp
private val BOX_RADIUS = 32.dp

/**
 * What the box understands of what is typed (250 ms after the last key) and the words not used, under the box on a
 * small glass of its own, whose solid surface keeps the aura's glow from behind the words; nothing while the box is
 * empty. One polite live region.
 */
@Composable
private fun UnderstoodLine(understood: StyleResult?, locked: Boolean) {
    Box(Modifier.readingWidth().padding(horizontal = 26.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        if (understood == null) return@Box
        GlassSurface(Modifier.padding(top = 12.dp).fillMaxWidth(), shape = RoundedCornerShape(12.dp), blur = false) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(understood.line, style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = MaterialTheme.colorScheme.onSurface)
                StudioAccess.unusedLine(understood.unused, locked)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = secondaryText()) }
            }
        }
    }
}


private const val COUNTER_FROM = 160

/**
 * Send: a filled circle in the content colour, the arrow in the surface's; off while there is nothing to send. Sending
 * gives the light tick play gives, and the circle scales to 0.97 while pressed (v1.14 — motion).
 */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val press = remember { MutableInteractionSource() }
    IconButton(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onClick()
        },
        enabled = enabled,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = LocalHairline.current,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.size(48.dp).padding(2.dp).pressScale(press),
        interactionSource = press,
    ) {
        Icon(painterResource(R.drawable.ic_send), contentDescription = "Send")
    }
}

/**
 * The stage's one card, on the content layer (no glass), as each turn's card was in 1.12: the cover, the title, what
 * was understood and not used, the steps with the current one in the content colour, the hairline, "42% · 0:50 of
 * 2:00 · about 40 s left", the preview roll and Cancel while it runs; made, Listen, Keep, Discard, Another like it and
 * Adjust…, and the credits; failed, one plain line and Try again. The card is one group for TalkBack, its summary said
 * at each step. Its solid surface covers the aura's glow beneath it. Removing a turn is History's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageCard(
    turn: Turn,
    modifier: Modifier = Modifier,
    locked: Boolean,
    waiting: Int,
    installed: Set<String>,
    jobs: List<StudioJob>,
    preview: StateFlow<PreviewRoll?>,
    onListen: () -> Unit,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
    onAgain: () -> Unit,
    onAdjust: () -> Unit,
    onCancel: () -> Unit,
) {
    val job = turn.job
    val running = turn.state == TurnState.Running
    val summary = when {
        job != null && !job.state.finished -> StudioCopy.spoken(job)
        else -> listOfNotNull(turn.title, stateWord(turn)).joinToString(", ")
    }
    Column(
        modifier
            .readingWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = summary },
    ) {
        if (running) AuraHairline(AuraState.Working)
        Row(Modifier.padding(14.dp)) {
            TurnCover(turn, Modifier.size(COVER))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(turn.title ?: workingTitle(turn), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (turn.understood.isNotBlank() && turn.understood != turn.asked) {
                    Text(turn.understood, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StudioAccess.unusedLine(turn.unused, locked)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (job != null && !job.state.finished) Steps(job, jobs, installed)
                turn.note?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (running && job != null && job.kind == JobKind.Compose) {
            val roll by preview.collectAsStateWithLifecycle()
            GenerationRoll(roll, job.targetMs, Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(ROLL))
        }
        // The finished card's actions fade in where Cancel was (v1.14 — motion); a cut when motion is reduced.
        val reducedMotion = rememberReducedMotion()
        AnimatedContent(
            targetState = turn.finished,
            transitionSpec = {
                Motion.change(fadeIn(tween(Motion.StandardMs, easing = Motion.Enter)), fadeOut(tween(Motion.QuickMs, easing = Motion.Leave)), reducedMotion) using null
            },
            label = "card actions",
        ) { finished ->
            FlowRow(
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    !finished -> ActionButton("Cancel", onClick = onCancel, description = "Cancel ${turn.title ?: workingTitle(turn)}")
                    turn.listenable -> {
                        ListenButton(onListen)
                        if (turn.state == TurnState.Made) {
                            ActionButton("Keep", onClick = onKeep)
                            ActionButton("Discard", onClick = onDiscard)
                        }
                        if (!turn.transcription) {
                            ActionButton("Another like it", onClick = onAgain, enabled = StudioAccess.canAgain(waiting))
                            ActionButton("Adjust…", onClick = onAdjust)
                        }
                    }
                    turn.state == TurnState.Failed || turn.state == TurnState.Interrupted ->
                        if (!turn.transcription) ActionButton("Try again", onClick = onAgain, enabled = StudioAccess.canAgain(waiting))
                    else -> if (!turn.transcription) ActionButton("Another like it", onClick = onAgain, enabled = StudioAccess.canAgain(waiting))
                }
            }
        }
        if (turn.listenable || turn.state == TurnState.Discarded) {
            Eyebrow(StudioCopy.credits(turn.seedTitle, turn.seedComposer, turn.transcription), Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp))
        }
    }
}


/** A title while the piece has none yet: "In the manner of …" or the recording's name. */
private fun workingTitle(turn: Turn): String = turn.job?.let(StudioCopy::jobName) ?: if (turn.transcription) "Transcription" else "A new piece"

private fun stateWord(turn: Turn): String = when (turn.state) {
    TurnState.Waiting -> "waiting"
    TurnState.Running -> "running"
    TurnState.Made -> "ready: listen, then keep it or discard it"
    TurnState.Kept -> "kept"
    TurnState.Discarded -> "discarded"
    TurnState.Gone -> "deleted from the library"
    TurnState.Failed -> "it didn't finish"
    TurnState.Cancelled -> "cancelled"
    TurnState.Interrupted -> "stopped when the app closed"
}

private val COVER = 96.dp
private val ROLL = 120.dp

/** Listen, the card's filled action. */
@Composable
private fun ListenButton(onClick: () -> Unit) {
    FilledButton(
        onClick = onClick,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface),
    ) { Text("Listen") }
}

/**
 * The steps as a row of words, the current one in the content colour ("Reading the piece · Composing · Shaping ·
 * Saving", the composing model's download first while it comes), the hairline easing between values, and the
 * figures in tabular digits.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Steps(job: StudioJob, jobs: List<StudioJob>, installed: Set<String>) {
    val download = jobs.lastOrNull { it.kind == JobKind.Download && !it.state.finished }
    val waitingForModel = job.state == JobState.Queued && download != null && job.kind == JobKind.Compose && ModelCatalogue.composer.name !in installed
    val ink = MaterialTheme.colorScheme.onSurface
    val quiet = LocalTertiary.current
    FlowRow(Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (waitingForModel) {
            Text("Downloading the composing model · ${StudioCopy.megabytes(download!!.bytes, download.total)}", style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = ink)
        } else {
            val steps = job.steps.ifEmpty { listOf(job.step) }
            steps.forEachIndexed { i, step ->
                if (i > 0) Text("·", style = MaterialTheme.typography.bodyMedium, color = quiet)
                val current = job.state == JobState.Running && step == job.step
                Text(StudioCopy.stepWord(step, job.kind), style = MaterialTheme.typography.bodyMedium, color = if (current) ink else quiet)
            }
        }
    }
    if (job.state == JobState.Running) {
        val measured = job.step == JobStep.Composing || job.step == JobStep.Transcribing
        ProgressHairline(if (measured) job.progress ?: 0f else null, Modifier.padding(top = 10.dp))
        if (job.step == JobStep.Composing) {
            // The figures roll as they change (v1.14 — motion), each part whole on its line; TalkBack reads the line.
            val figures = StudioCopy.figures(job)
            val parts = figures.split(" · ")
            FlowRow(Modifier.padding(top = 6.dp).clearAndSetSemantics { text = AnnotatedString(figures) }) {
                parts.forEachIndexed { i, part ->
                    RollingText(if (i < parts.lastIndex) "$part · " else part, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else if (job.state == JobState.Queued && !waitingForModel) {
        Text("Waiting", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The piece's cover: its own once saved (drawn from its notes), else the one drawn at once from its settings, in
 * the art's frame.
 */
@Composable
private fun TurnCover(turn: Turn, modifier: Modifier) {
    val provisional: @Composable (Modifier) -> Unit = { frame -> ProvisionalCover(turn, frame) }
    val pieceId = turn.pieceId
    if (pieceId != null) ArtworkImage(ArtworkEntity.forPiece(pieceId), ArtSize.Tile, modifier, alignment = Alignment.Center, fallback = provisional) else provisional(modifier)
}

@Composable
private fun ProvisionalCover(turn: Turn, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, turn.key, turn.mood, turn.musicKey) {
        value = withContext(Dispatchers.Default) {
            val px = StudioCover.render(CoverInput.provisional(turn.musicKey, turn.mood, turn.bpm, turn.coverSeed), PROVISIONAL_PX)
            android.graphics.Bitmap.createBitmap(px, PROVISIONAL_PX, PROVISIONAL_PX, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    ArtFrame(modifier) {
        image?.let { androidx.compose.foundation.Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private const val PROVISIONAL_PX = 192

/** The notes being written, as they are written: time across (the length asked for fills the width), the keys up. */
@Composable
private fun GenerationRoll(roll: PreviewRoll?, targetMs: Long, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val track = LocalHairline.current
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)) {
        drawRect(track, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1f), size = androidx.compose.ui.geometry.Size(size.width, 1f))
        val notes = roll ?: return@Canvas
        val span = maxOf(targetMs / 10f, notes.lengthTicks.toFloat(), 1f)
        val noteHeight = maxOf(2f, size.height / 84f * 1.6f)
        for (i in 0 until notes.size) {
            val x = notes.onset(i) / span * size.width
            val w = maxOf(2f, notes.duration(i) / span * size.width)
            val y = size.height - (notes.key(i) - 24).coerceIn(0, 83) / 84f * size.height - noteHeight
            drawRect(ink.copy(alpha = 0.72f), topLeft = androidx.compose.ui.geometry.Offset(x, y), size = androidx.compose.ui.geometry.Size(w, noteHeight))
        }
    }
}

/**
 * History (a glass sheet from the header's second glyph): every turn, newest first, each with its cover, its title,
 * the idea's words (hidden while kiosk mode keeps the settings locked: "An idea typed here") and its state in an
 * eyebrow. A tap opens its actions under it: Cancel while it waits or runs; else Listen · Another like it (Try again
 * after a failure) · Adjust… · Keep · Discard · Remove, each where it applies. Keep, Discard and Remove ask for the PIN
 * in kiosk mode, as on the stage. The Library's Made in Studio playlist is unchanged beside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    turns: List<Turn>,
    locked: Boolean,
    waiting: Int,
    onDismiss: () -> Unit,
    onListen: (Turn) -> Unit,
    onAgain: (Turn) -> Unit,
    onAdjust: (Turn) -> Unit,
    onKeep: (Turn) -> Unit,
    onDiscard: (Turn) -> Unit,
    onRemove: (Turn) -> Unit,
    onCancel: (Turn) -> Unit,
) {
    GlassSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Eyebrow("Studio", Modifier.padding(horizontal = 16.dp))
        Text("History", Modifier.padding(horizontal = 16.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        if (turns.isEmpty()) {
            NoteLine("Nothing made yet.", Modifier.padding(bottom = 24.dp))
            return@GlassSheet
        }
        var open by rememberSaveable { mutableStateOf<String?>(null) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = HISTORY_HEIGHT), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            items(turns, key = { it.key }) { turn ->
                HistoryRow(
                    turn,
                    locked = locked,
                    waiting = waiting,
                    expanded = open == turn.key,
                    onToggle = { open = if (open == turn.key) null else turn.key },
                    onListen = { onListen(turn) },
                    onAgain = { onAgain(turn) },
                    onAdjust = { onAdjust(turn) },
                    onKeep = { onKeep(turn) },
                    onDiscard = { onDiscard(turn) },
                    onRemove = { onRemove(turn) },
                    onCancel = { onCancel(turn) },
                )
            }
        }
    }
}

/** One turn in History: its cover, state, title and words; tapped, its actions under it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(
    turn: Turn,
    locked: Boolean,
    waiting: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onListen: () -> Unit,
    onAgain: () -> Unit,
    onAdjust: () -> Unit,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = turn.title ?: workingTitle(turn)
    val words = if (turn.typed && !StudioAccess.showsTypedText(locked)) StudioAccess.HIDDEN else turn.asked
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "Hide its actions" else "Show its actions", onClick = onToggle)
                .semantics { stateDescription = if (expanded) "Actions shown" else "Actions hidden" }
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TurnCover(turn, Modifier.size(HISTORY_COVER))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow(StudioStage.eyebrow(turn.state))
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (words != title) Text(words, style = MaterialTheme.typography.bodyMedium, color = secondaryText(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (expanded) {
            FlowRow(
                Modifier.padding(start = 16.dp + HISTORY_COVER + 14.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!turn.finished) {
                    ActionButton("Cancel", onClick = onCancel, description = "Cancel $title")
                } else {
                    if (turn.listenable) ListenButton(onListen)
                    if (!turn.transcription) {
                        val failed = turn.state == TurnState.Failed || turn.state == TurnState.Cancelled || turn.state == TurnState.Interrupted
                        ActionButton(if (failed) "Try again" else "Another like it", onClick = onAgain, enabled = StudioAccess.canAgain(waiting))
                        ActionButton("Adjust…", onClick = onAdjust)
                    }
                    if (turn.state == TurnState.Made) {
                        ActionButton("Keep", onClick = onKeep)
                        ActionButton("Discard", onClick = onDiscard)
                    }
                    if (turn.rowId != null) ActionButton("Remove", onClick = onRemove, description = "Remove this turn from the history")
                }
            }
        }
        HairlineDivider(startInset = 16.dp)
    }
}

private val HISTORY_COVER = 56.dp
private val HISTORY_HEIGHT = 560.dp


/** Models (a glass sheet): Composing and Transcription with size and licence, Download, Cancel or Remove, as the old page's MODELS. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsSheet(vm: StudioViewModel, installed: Set<String>, jobs: List<StudioJob>, onDismiss: () -> Unit) {
    GlassSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Eyebrow("Studio", Modifier.padding(horizontal = 16.dp))
            Text("Models", Modifier.padding(horizontal = 16.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            for (model in ModelCatalogue.all) {
                val download = jobs.lastOrNull { it.kind == JobKind.Download && it.model == model.name }
                ModelRow(model, model.name in installed, download, vm)
            }
            NoteLine("Both run on this tablet. Nothing you type leaves it.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelRow(model: ModelEntry, installed: Boolean, download: StudioJob?, vm: StudioViewModel) {
    val running = download?.takeIf { !it.state.finished }
    val line = when {
        running != null -> StudioCopy.jobLine(running)
        installed -> "Installed · ${StudioCopy.modelLine(model)}"
        download?.state == JobState.Failed -> download.error ?: StudioCopy.modelLine(model)
        else -> "${StudioCopy.modelLine(model)} · ${model.use}"
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(model.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Eyebrow(line, Modifier.padding(top = 2.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
        if (running?.state == JobState.Running) ProgressHairline(running.progress ?: 0f, Modifier.padding(top = 10.dp))
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                running != null -> ActionButton("Cancel", onClick = { vm.cancelJob(running.id) }, description = "Cancel the download of the ${model.title.lowercase()} model")
                installed -> ActionButton("Remove", onClick = { vm.removeModel(model) }, description = "Remove the ${model.title.lowercase()} model")
                else -> ActionButton("Download", onClick = { vm.download(model) }, description = "Download the ${model.title.lowercase()} model, ${StudioCopy.size(model.sizeBytes)}")
            }
        }
    }
    HairlineDivider(startInset = 16.dp)
}
