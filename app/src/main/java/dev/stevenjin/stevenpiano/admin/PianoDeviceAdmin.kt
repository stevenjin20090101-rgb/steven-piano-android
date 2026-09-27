// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.admin

import android.app.admin.DeviceAdminReceiver

/**
 * The school tablet's one-time setup makes the app its device owner (`adb shell dpm
 * set-device-owner dev.stevenjin.stevenpiano/.admin.PianoDeviceAdmin`, README › School tablet),
 * for one thing only: its own updates install without a tap (see `UpdateInstaller`). It asks for
 * no policies (`res/xml/device_admin.xml` lists none), locks nothing and hides nothing; the tablet
 * works as before. The way back, over adb, is [DeviceOwnerRelease]. Its name must stay the same in
 * every release, or an update would drop the device owner. Only the system (holding
 * BIND_DEVICE_ADMIN) can reach it.
 */
class PianoDeviceAdmin : DeviceAdminReceiver()
