// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ui.theme.Tabular

/**
 * A compact "−  100%  +" stepper over [range] in steps of [step]. The value is set in tabular
 * figures at a fixed minimum width, so nothing shifts as it changes; each end disables its button.
 * With [rolling] (the tempo, v1.14 — motion) its digits roll as they change ([RollingText]).
 */
@Composable
fun StepperControl(
    value: Int,
    range: IntRange,
    step: Int,
    format: (Int) -> String,
    decreaseLabel: String,
    increaseLabel: String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    rolling: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(R.drawable.ic_remove, decreaseLabel, enabled = value > range.first) {
            onChange((value - step).coerceIn(range))
        }
        if (rolling) {
            RollingText(
                format(value),
                Modifier.widthIn(min = 56.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                arrangement = Arrangement.Center,
            )
        } else {
            Text(
                format(value),
                modifier = Modifier.widthIn(min = 56.dp),
                style = MaterialTheme.typography.labelLarge.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        GlyphButton(R.drawable.ic_add, increaseLabel, enabled = value < range.last) {
            onChange((value + step).coerceIn(range))
        }
    }
}
