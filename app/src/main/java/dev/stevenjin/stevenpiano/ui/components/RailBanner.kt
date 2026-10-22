// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.power.RailMessage
import dev.stevenjin.stevenpiano.ui.PowerCopy
import dev.stevenjin.stevenpiano.ui.theme.LocalAttention
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

/**
 * The 24 V rail's words on every screen (v1.26 — M60; DESIGN.md › v1.26 — M60), the resting screen too, as the
 * microphone's banner is (below it while that one shows): a glass capsule at the top with the plug's bolt. "Waking the
 * piano…" while the rail comes up and "24 V off" for a few seconds after it goes off, both calm and passing by themselves;
 * the failures ("The piano stopped answering — 24 V cut", "Lost the power plug", "Only 6 of 7 power boards answered") in
 * the attention amber, with **OK**, until read. It slides in and out (a cut under reduced motion); nothing about it loops.
 */
@Composable
fun RailBanner(modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val message by graph.rail.message.collectAsStateWithLifecycle()
    val listen by graph.remote.listening.state.collectAsStateWithLifecycle()
    val reduced = rememberReducedMotion()
    val last = remember { mutableStateOf<RailMessage?>(null) }
    message?.let { SideEffect { last.value = it } }
    val shown = message ?: last.value   // what slides out is the last one shown
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = Motion.enter(fadeIn(tween(Motion.StandardMs, easing = Motion.Enter)) + slideInVertically(tween(Motion.StandardMs, easing = Motion.Enter)) { -it / 2 }, reduced),
        exit = Motion.exit(fadeOut(tween(Motion.QuickMs, easing = Motion.Leave)) + slideOutVertically(tween(Motion.QuickMs, easing = Motion.Leave)) { -it / 2 }, reduced),
        label = "rail banner",
    ) {
        val now = shown ?: return@AnimatedVisibility
        val attention = now.attention
        GlassSurface(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = if (listen.on) MICROPHONE_BANNER_ROOM else 0.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 560.dp),
            shape = CircleShape,
            edge = GlassEdge.Outline,
            blur = false,
        ) {
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = if (attention) 4.dp else 20.dp)
                    .semantics { liveRegion = if (attention) LiveRegionMode.Assertive else LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_g_power),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (attention) LocalAttention.current else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    PowerCopy.message(now),
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (attention) LocalAttention.current else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (attention) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = graph.rail::dismissMessage) { Text(PowerCopy.OK) }
                }
            }
        }
    }
}

/** The microphone's capsule and its margins: this one sits under it while both show. */
private val MICROPHONE_BANNER_ROOM = 64.dp
