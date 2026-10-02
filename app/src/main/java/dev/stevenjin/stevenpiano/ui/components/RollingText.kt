// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

/**
 * Figures whose digits roll (DESIGN.md › v1.14 — motion): the tempo, the Record pill's time, Studio's progress.
 * Each digit that changes slides 120 ms, the new one in from below when it is larger and from above when it is
 * smaller, in tabular figures so nothing shifts sideways. When anything but a digit changes (a number grows a
 * place, the words change) the text is simply replaced, as it is when motion is reduced. TalkBack reads [text] whole.
 */
@Composable
fun RollingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    arrangement: Arrangement.Horizontal = Arrangement.Start,
) {
    val tabular = style.merge(Tabular)
    if (rememberReducedMotion()) {
        Row(modifier, horizontalArrangement = arrangement) { Text(text, style = tabular, color = color) }
        return
    }
    Row(
        modifier
            .clipToBounds()
            .clearAndSetSemantics { this.text = AnnotatedString(text) },
        horizontalArrangement = arrangement,
    ) {
        // Same shape (the digits' places and every other character), same slots: only then do digits roll.
        key(RollingFigures.shape(text)) {
            for (run in RollingFigures.runs(text)) {
                if (run.first().isDigit()) RollingDigit(run.first(), tabular, color) else Text(run, style = tabular, color = color)
            }
        }
    }
}

@Composable
private fun RollingDigit(digit: Char, style: TextStyle, color: Color) {
    AnimatedContent(
        targetState = digit,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically(tween(Motion.QuickMs, easing = Motion.Enter)) { h -> if (up) h else -h } + fadeIn(tween(Motion.QuickMs))) togetherWith
                (slideOutVertically(tween(Motion.QuickMs, easing = Motion.Leave)) { h -> if (up) -h else h } + fadeOut(tween(Motion.QuickMs)))
        },
        label = "digit",
    ) { shown -> Text(shown.toString(), style = style, color = color) }
}

/** How [RollingText] cuts its text: each digit alone, everything between digits as one run. Pure. */
internal object RollingFigures {
    /** The text with every digit as 0: two texts of one shape differ in their digits alone. */
    fun shape(text: String): String = buildString(text.length) { for (c in text) append(if (c.isDigit()) '0' else c) }

    fun runs(text: String): List<String> {
        val out = ArrayList<String>()
        val words = StringBuilder()
        for (c in text) {
            if (c.isDigit()) {
                if (words.isNotEmpty()) out += words.toString().also { words.clear() }
                out += c.toString()
            } else {
                words.append(c)
            }
        }
        if (words.isNotEmpty()) out += words.toString()
        return out
    }
}
