// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.audio.KeyResult
import dev.stevenjin.stevenpiano.audio.KeyTest
import dev.stevenjin.stevenpiano.audio.KeyTestState
import dev.stevenjin.stevenpiano.audio.StepsPhase
import dev.stevenjin.stevenpiano.audio.StepsResult
import dev.stevenjin.stevenpiano.audio.StepsState
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.StepsCopy
import dev.stevenjin.stevenpiano.ui.screens.piano.pages.KeyMap
import dev.stevenjin.stevenpiano.ui.theme.LocalAttention
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

/**
 * Find the loudness steps' card (M83), composed over every screen as the safety check's is: a calm full-screen glass card
 * while the calibration runs, and after it until OK.
 *
 * - **Running**: "Finding the loudness steps", what to keep (a quiet room), and where it stands: the 24 V coming up, the
 *   room heard, then the key ("C4 · key 37 of 84") and about how long is left, the keyboard map filling in as the keys are
 *   done, the slim progress line; **Stop** (no PIN: anyone at the tablet may stop it, as the microphone's banner may).
 *   The screen stays on.
 * - **Found**: how many keys got how many steps, the keys not calibrated named and shown on the map in the attention amber
 *   (a warning: they spread their steps between the floor and the ceiling), then OK.
 * - **Stopped**: why, and that nothing was written (or how far the writing got), then OK.
 *
 * It holds every touch and Back while it shows; it fades in and out (a cut under reduced motion); nothing loops; every
 * control is 48 dp or more.
 */
@Composable
fun BoxScope.StepsCard() {
    val steps = LocalContext.current.graph.steps
    val state by steps.state.collectAsStateWithLifecycle()
    val reduced = rememberReducedMotion()
    KeepOn(state.running)
    AnimatedVisibility(
        visible = state.shown,
        modifier = Modifier.matchParentSize(),
        enter = Motion.enter(fadeIn(tween(Motion.EmphasisedMs, easing = Motion.Enter)), reduced),
        exit = Motion.exit(fadeOut(tween(Motion.StandardMs, easing = Motion.Leave)), reduced),
        label = "loudness steps",
    ) {
        BackHandler {}   // what lies beneath waits
        GlassSurface(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { paneTitle = StepsCopy.TITLE },
            fill = GlassFill.Sheet,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val window = maxHeight
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = window)
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(Modifier.widthIn(max = CARD_WIDTH).fillMaxWidth()) {
                            val result = state.result
                            when {
                                state.running -> Running(state, onStop = steps::stop)
                                result != null -> Found(result, onDismiss = steps::dismiss)
                                else -> Stopped(state, onDismiss = steps::dismiss)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        Modifier.semantics {
            heading()
            liveRegion = LiveRegionMode.Polite
        },
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Line(text: String, modifier: Modifier = Modifier.padding(top = 8.dp)) {
    Text(text, modifier, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The calibration running: where it stands, the map, the progress line, and Stop. */
@Composable
private fun Running(state: StepsState, onStop: () -> Unit) {
    Title(StepsCopy.TITLE)
    Line(StepsCopy.LINE)
    Spacer(Modifier.size(24.dp))
    val key = state.key
    val now = when {
        state.phase == StepsPhase.Rail -> StepsCopy.RAIL
        state.phase == StepsPhase.Room -> StepsCopy.ROOM
        state.phase == StepsPhase.Writing -> StepsCopy.WRITING
        key != null -> StepsCopy.key(key, state.number, state.total)
        else -> StepsCopy.ROOM
    }
    Text(
        now,
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.titleLarge.merge(Tabular),
        color = MaterialTheme.colorScheme.onSurface,
    )
    state.leftMs?.takeIf { state.phase == StepsPhase.Keys }?.let { left ->
        Text(StepsCopy.left(left), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge.merge(Tabular), color = LocalTertiary.current)
    }
    Box(Modifier.padding(vertical = 12.dp)) {
        // The keys done so far, the one struck now in the content colour, those to come faint.
        KeyMap(KeyTestState(running = true, key = key, done = state.number, total = state.total), said = now)
    }
    ProgressHairline(state.progress, Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onStop, modifier = Modifier.heightIn(min = TOUCH)) { Text(StepsCopy.STOP) }
    }
}

/** The steps found: how many keys got how many, those not calibrated, and OK. */
@Composable
private fun Found(result: StepsResult, onDismiss: () -> Unit) {
    Title(StepsCopy.FOUND)
    val summary = StepsCopy.summary(result)
    Text(summary, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge.merge(Tabular), color = MaterialTheme.colorScheme.onSurface)
    val uncalibrated = StepsCopy.uncalibrated(result.uncalibrated)
    Box(Modifier.padding(vertical = 12.dp)) {
        KeyMap(KeyTestState(results = KeyTest.KEYS.map { KeyResult(it, it !in result.uncalibrated) }), said = listOfNotNull(summary, uncalibrated).joinToString(". "))
    }
    if (uncalibrated != null) Text(uncalibrated, style = MaterialTheme.typography.bodyLarge, color = LocalAttention.current)
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TOUCH)) { Text(StepsCopy.OK) }
    }
}

/** It stopped: why, what was written, and OK. */
@Composable
private fun Stopped(state: StepsState, onDismiss: () -> Unit) {
    Title(StepsCopy.STOPPED_TITLE)
    state.stopped?.let { Line(it, Modifier.padding(top = 12.dp)) }
    Line(if (state.written > 0 || state.fullPowerOff) StepsCopy.partly(state.written, state.fullPowerOff) else StepsCopy.NOTHING_WRITTEN)
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TOUCH)) { Text(StepsCopy.OK) }
    }
}

/** The screen stays on while it runs (a screen that slept would end it with the microphone): the window's own flag. */
@Composable
private fun KeepOn(on: Boolean) {
    val context = LocalContext.current
    DisposableEffect(on, context) {
        val window = if (on) context.findActivity()?.window else null
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val CARD_WIDTH = 560.dp
private val TOUCH = 48.dp
