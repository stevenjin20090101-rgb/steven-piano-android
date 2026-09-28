// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import dev.stevenjin.stevenpiano.ui.theme.CarbonPrimary
import dev.stevenjin.stevenpiano.ui.theme.CarbonSecondary
import dev.stevenjin.stevenpiano.ui.theme.DarkScheme
import dev.stevenjin.stevenpiano.ui.theme.InkSurface
import dev.stevenjin.stevenpiano.ui.theme.LightScheme
import dev.stevenjin.stevenpiano.ui.theme.PaperSurface
import dev.stevenjin.stevenpiano.ui.theme.SilverPrimary
import dev.stevenjin.stevenpiano.ui.theme.SilverSecondary
import dev.stevenjin.stevenpiano.ui.theme.Wcag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's outlined buttons (ActionButton, Disconnect and Cancel, Shuffle, Add MIDI files) label in
 * the content colour, and in the secondary one only when unavailable (DESIGN.md › v1.5 › Action row),
 * not in Material 3's outlined default, the secondary ink whether enabled or not.
 */
class ActionLabelsTest {
    @Test
    fun `an outlined button reads in the content colour, the secondary one when unavailable, in both appearances`() {
        val dark = ActionLabels.of(DarkScheme)
        assertEquals(SilverPrimary, dark.enabled)
        assertEquals(SilverSecondary, dark.disabled)
        val light = ActionLabels.of(LightScheme)
        assertEquals(CarbonPrimary, light.enabled)
        assertEquals(CarbonSecondary, light.disabled)
    }

    @Test
    fun `an unavailable button is told apart from an available one, and both stay readable`() {
        for ((labels, surface) in listOf(ActionLabels.of(DarkScheme) to InkSurface, ActionLabels.of(LightScheme) to PaperSurface)) {
            assertNotEquals("Material's outlined default gave both the same grey", labels.enabled, labels.disabled)
            assertTrue("enabled: ${Wcag.contrast(labels.enabled, surface)}", Wcag.contrast(labels.enabled, surface) >= 7.0)
            assertTrue("disabled: ${Wcag.contrast(labels.disabled, surface)}", Wcag.contrast(labels.disabled, surface) >= 4.5)
        }
    }
}
