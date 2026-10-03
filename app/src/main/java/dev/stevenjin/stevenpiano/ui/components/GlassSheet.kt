// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A bottom sheet on glass (DESIGN.md › v1.9): Material's modal sheet, its scrim behind it as before,
 * and its container the sheets' glass ([GlassFill.Sheet], 0.86) over the app beneath, blurred, with
 * the hairline round its rounded top and the specular line along it, and the grabber (a 32 × 4 dp
 * pill in the secondary grey, with the sheet's accessibility actions: dismiss, and expand or collapse
 * where the sheet has a half-open state) inside the glass. Where the glass cannot be drawn
 * (transparency reduced, below API 31), today's sheet: the elevated tone, and the hairline. The
 * blur runs only while the sheet is open. Every sheet in the app is one: Up next, the piece sheet,
 * Add, the PIN sheets, a channel's volume, the schedule editor, Compose, the QR code, a share.
 * [content] sits as in Material's sheet, under the grabber, clear of the system bars.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) = AppAppearance {   // opened from over the cover's backdrop, still in the app's appearance (v1.18 — M49)
    val shape = BottomSheetDefaults.ExpandedShape
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        dragHandle = null,
        // The glass reaches the sheet's every edge; its insets are kept inside it, as Material keeps them.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        GlassSurface(
            Modifier.fillMaxWidth(),
            shape = shape,
            fill = GlassFill.Sheet,
            solid = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(BottomSheetDefaults.windowInsets),
            ) {
                SheetGrabber(sheetState, onDismissRequest)
                content()
            }
        }
    }
}

/** The grabber, as Material's drag handle: 48 dp tall with its pill, and the sheet's actions for accessibility services. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.SheetGrabber(state: SheetState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pill = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .align(Alignment.CenterHorizontally)
            .semantics(mergeDescendants = true) {
                contentDescription = GRABBER
                dismiss(DISMISS) {
                    scope.launch { state.hide() }.invokeOnCompletion { if (!state.isVisible) onDismiss() }
                    true
                }
                if (state.currentValue == SheetValue.PartiallyExpanded) {
                    expand(EXPAND) {
                        scope.launch { state.expand() }
                        true
                    }
                } else if (state.hasPartiallyExpandedState) {
                    collapse(COLLAPSE) {
                        scope.launch { state.partialExpand() }
                        true
                    }
                }
            }
            .padding(vertical = 22.dp)
            .size(width = 32.dp, height = 4.dp)
            .background(pill, CircleShape),
    )
}

private const val GRABBER = "Drag handle"
private const val DISMISS = "Close sheet"
private const val EXPAND = "Expand sheet"
private const val COLLAPSE = "Collapse sheet"
