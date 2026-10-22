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
import androidx.compose.runtime.getValue
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
import dev.stevenjin.stevenpiano.ui.ListenCopy
import dev.stevenjin.stevenpiano.ui.SafetyCopy
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

/**
 * While the tablet's microphone is on (v1.23 — M55; DESIGN.md › v1.23 — M55), on every screen, the resting screen too: a
 * glass capsule at the top, the microphone and "Microphone on · listening from the web panel" (or, for the tablet's own key
 * test, "Microphone on · testing every key"; for the safety check's sweep, "Microphone on · safety check": v1.26 — M61),
 * with **Stop**, which ends every hearing and the key test (no PIN: anyone at the tablet may stop it; a safety check
 * stopped so runs again before the next piece). It slides in and out (a cut under reduced motion); nothing about it loops.
 */
@Composable
fun MicrophoneBanner(modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val listen by graph.remote.listening.state.collectAsStateWithLifecycle()
    val safety by graph.safety.state.collectAsStateWithLifecycle()
    val reduced = rememberReducedMotion()
    AnimatedVisibility(
        visible = listen.on,
        modifier = modifier,
        enter = Motion.enter(fadeIn(tween(Motion.StandardMs, easing = Motion.Enter)) + slideInVertically(tween(Motion.StandardMs, easing = Motion.Enter)) { -it / 2 }, reduced),
        exit = Motion.exit(fadeOut(tween(Motion.QuickMs, easing = Motion.Leave)) + slideOutVertically(tween(Motion.QuickMs, easing = Motion.Leave)) { -it / 2 }, reduced),
        label = "microphone banner",
    ) {
        GlassSurface(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 560.dp),
            shape = CircleShape,
            edge = GlassEdge.Outline,
            blur = false,
        ) {
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        listen.panel -> ListenCopy.BANNER_PANEL
                        safety.running -> SafetyCopy.BANNER
                        else -> ListenCopy.BANNER_TEST
                    },
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = graph.remote::stopListening) { Text(ListenCopy.STOP) }
            }
        }
    }
}
