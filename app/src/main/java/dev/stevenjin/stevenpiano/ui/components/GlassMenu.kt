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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

/**
 * The material of menus, popovers and dialogs (DESIGN.md › v1.9): the sheets' glass
 * ([GlassFill.Sheet], 0.86) over the app beneath, blurred, in [shape], with the hairline round it
 * and the specular line along its top; today's elevated tone and hairline where the glass cannot be
 * drawn. On it the tertiary grey gives way to the secondary, which reads 4.5:1 or more over the worst
 * backdrop there (GlassTokensTest). Transient: its blur runs only while it is open.
 */
@Composable
fun GlassMenuContainer(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier,
        shape = shape,
        edge = GlassEdge.Outline,
        fill = GlassFill.Sheet,
        solid = MaterialTheme.colorScheme.surfaceContainer,
        content = content,
    )
}

/**
 * A row's or a tile's menu (Material's dropdown menu, its placement and its items) on the menus'
 * glass ([GlassMenuContainer]'s material); its shadow as before.
 */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MenuDefaults.shape
    val look = rememberGlassLook(
        shape,
        LocalHazeState.current,
        GlassEdge.Outline,
        GlassFill.Sheet,
        blur = true,
        solid = MenuDefaults.containerColor,
        outline = LocalHairline.current,
        edgeAlpha = GlassShown,
        band = GlassHidden,
    )
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        // Material lays this on the menu's column, inside its surface: the glass fills the whole menu.
        modifier = modifier.then(look.modifier),
        offset = offset,
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        GlassText(look.glass, GlassFill.Sheet) { content() }
    }
}

/**
 * A dialog on the dialogs' glass ([GlassMenuContainer]'s material, the dialog's 24 dp corners), laid
 * out as Material's alert dialog: [title] (the content colour), [text] (the secondary grey), then the
 * buttons at the end, [dismissButton] before [confirmButton]; 24 dp in, at least 280 dp wide.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier, properties = properties) {
        GlassDialogSurface {
            Column(Modifier.padding(DialogPadding)) {
                if (title != null) {
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                        LocalTextStyle provides MaterialTheme.typography.titleLarge,
                    ) {
                        Box(Modifier.padding(bottom = 16.dp).align(Alignment.Start)) { title() }
                    }
                }
                if (text != null) {
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                        LocalTextStyle provides MaterialTheme.typography.bodyLarge,
                    ) {
                        Box(
                            Modifier
                                .weight(1f, fill = false)
                                .padding(bottom = 24.dp)
                                .align(Alignment.Start),
                        ) { text() }
                    }
                }
                FlowRow(
                    Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

/** A dialog's own glass, for dialogs laid out by hand (the time picker): [GlassMenuContainer] in the dialog's corners. */
@Composable
fun GlassDialogSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    GlassSurface(
        modifier,
        shape = AlertDialogDefaults.shape,
        edge = GlassEdge.Outline,
        fill = GlassFill.Sheet,
        solid = AlertDialogDefaults.containerColor,
        content = content,
    )
}

/**
 * A small popover anchored to the control it is composed beside (as Material's menus are): the
 * menus' glass with 16 dp of room inside, below the anchor with its end at the anchor's end (above it
 * where there is no room below), kept 8 dp inside the window; it takes the room its [content] asks for
 * (a slider row gives itself a width). Outside taps and Back close it ([onDismissRequest]). It fades in
 * and out over 120 ms, a cut when motion is reduced. For a control's small settings, such as a
 * volume, in the place it is used.
 */
@Composable
fun GlassPopover(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    if (!state.currentState && !state.targetState) return
    val reduced = rememberReducedMotion()
    val density = LocalDensity.current
    val provider = remember(offset, density) { PopoverPosition(offset, density) }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            visibleState = state,
            enter = if (reduced) EnterTransition.None else fadeIn(tween(Motion.FastMs)),
            exit = if (reduced) ExitTransition.None else fadeOut(tween(Motion.FastMs)),
        ) {
            GlassMenuContainer(modifier) {
                Column(Modifier.padding(16.dp), content = content)
            }
        }
    }
}

/** Below the anchor, ends aligned (starts in right to left), above it where the window has no room below; 8 dp inside the window. */
private class PopoverPosition(private val offset: DpOffset, private val density: Density) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = with(density) { WINDOW_MARGIN.roundToPx() }
        val dx = with(density) { offset.x.roundToPx() }
        val dy = with(density) { offset.y.roundToPx() }
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width + dx else anchorBounds.left - dx
        val below = anchorBounds.bottom + dy
        val above = anchorBounds.top - dy - popupContentSize.height
        val y = if (below + popupContentSize.height <= windowSize.height - margin || above < margin) below else above
        val maxX = (windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin)
        val maxY = (windowSize.height - margin - popupContentSize.height).coerceAtLeast(margin)
        return IntOffset(x.coerceIn(margin, maxX), y.coerceIn(margin, maxY))
    }
}

private val WINDOW_MARGIN = 8.dp

/** Material's alert dialog's inner room. */
private val DialogPadding = 24.dp
