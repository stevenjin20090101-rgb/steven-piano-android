// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

import android.content.Context
import dev.stevenjin.stevenpiano.diag.AndroidSystemProbe

/**
 * The tablet's battery now, as the firmware updater weighs it: its charge and whether it is charging (or full, on the
 * charger). One reader since v1.18 (M46): the System page's ([AndroidSystemProbe.battery]).
 */
fun batteryState(context: Context): PowerState {
    val battery = AndroidSystemProbe.battery(context) ?: return PowerState(null, charging = false)
    return PowerState(battery.percent, charging = battery.charging == true)
}
