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
import android.os.BatteryManager

/** The tablet's battery now, as the firmware updater weighs it: its charge and whether it is charging. */
fun batteryState(context: Context): PowerState {
    val battery = context.getSystemService(BatteryManager::class.java) ?: return PowerState(null, charging = false)
    val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    return PowerState(percent, charging = battery.isCharging)
}
