// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Keys screen's keys stay keyboard-shaped: at most 320 dp tall, or 45 % of the screen where that is less. */
class KeysHeightCapTest {
    @Test
    fun `a tablet upright gets 320 dp keys, not the whole height`() {
        assertEquals(320.dp, keysHeightCap(1_230.dp))
    }

    @Test
    fun `a phone upright is capped at 320 dp too`() {
        assertEquals(320.dp, keysHeightCap(790.dp))
    }

    @Test
    fun `a phone on its side gets 45 percent of its height`() {
        assertEquals(171.dp.value, keysHeightCap(380.dp).value, 0.01f)
    }
}
