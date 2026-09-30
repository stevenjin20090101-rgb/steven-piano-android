// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Reading surfaces (the library, the Piano tab) never run wider than this; rows keep 56 dp and 17 sp. */
val ReadingWidth: Dp = 720.dp

/** Caps a reading surface at [ReadingWidth] and centres it; on a phone it is simply the full width. */
fun Modifier.readingWidth(): Modifier = fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = ReadingWidth)

/**
 * Side padding that centres a [ReadingWidth] column in [available] width, for lists that must
 * still scroll from anywhere across the screen, [top] over the first row (the header's glass, which
 * the list scrolls beneath) and [bottom] under the last row (what the floating controls cover, so it
 * can rise above them).
 */
fun readingPadding(available: Dp, top: Dp = 0.dp, bottom: Dp = 0.dp): PaddingValues =
    ((available - ReadingWidth) / 2).coerceAtLeast(0.dp).let { side -> PaddingValues(start = side, top = top, end = side, bottom = bottom) }
