// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.reducedMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Press feedback (DESIGN.md › v1.14 — motion): while [interaction] is pressed the control is drawn at
 * [Motion.PressedScale], on the press spring, and springs back on release or cancel; a new touch takes it from
 * wherever it is. It is drawn, never laid out, so nothing beside it moves and the touch is never in its way. Under
 * reduced motion it does nothing (the ripple is the feedback). For the glass controls, the tiles, the chips and the
 * filled buttons; rows keep the ripple alone.
 */
fun Modifier.pressScale(interaction: InteractionSource): Modifier = this then PressScaleElement(interaction)

private data class PressScaleElement(val interaction: InteractionSource) : ModifierNodeElement<PressScaleNode>() {
    override fun create(): PressScaleNode = PressScaleNode(interaction)

    override fun update(node: PressScaleNode) = node.follow(interaction)

    override fun InspectorInfo.inspectableProperties() {
        name = "pressScale"
        properties["interaction"] = interaction
    }
}

private class PressScaleNode(private var interaction: InteractionSource) :
    Modifier.Node(),
    LayoutModifierNode,
    CompositionLocalConsumerModifierNode {
    private var scale = Animatable(1f)
    private var following: Job? = null

    override fun onAttach() = listen()

    // Detached (scrolled away, or its slot reused for another row) it comes back at rest.
    override fun onDetach() {
        following = null
        scale = Animatable(1f)
    }

    /** A new source (the control was given another): listen to that one instead. */
    fun follow(source: InteractionSource) {
        if (source == interaction) return
        interaction = source
        if (isAttached) listen()
    }

    private fun listen() {
        following?.cancel()
        if (scale.value != 1f) coroutineScope.launch { scale.snapTo(1f) }
        if (reducedMotion(currentValueOf(LocalContext).contentResolver)) return
        val source = interaction
        following = coroutineScope.launch {
            val held = ArrayList<PressInteraction.Press>(1)
            source.interactions.collect { event ->
                when (event) {
                    is PressInteraction.Press -> held += event
                    is PressInteraction.Release -> held -= event.press
                    is PressInteraction.Cancel -> held -= event.press
                    else -> return@collect
                }
                val target = if (held.isEmpty()) 1f else Motion.PressedScale
                launch { scale.animateTo(target, Motion.press()) }
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            // Read in the layer: a press redraws the control's layer, nothing is composed or laid out again.
            placeable.placeWithLayer(0, 0) {
                val shown = scale.value
                scaleX = shown
                scaleY = shown
            }
        }
    }
}

/**
 * Material's filled button with the press feedback ([pressScale]): the app's filled actions (Connect, Update,
 * Restart, Retry, Load, Add, Listen) keep Material's look and add the scale.
 */
@Composable
fun FilledButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Button(onClick = onClick, modifier = modifier.pressScale(press), enabled = enabled, colors = colors, interactionSource = press, content = content)
}
