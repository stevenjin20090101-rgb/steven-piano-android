// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

import dev.stevenjin.stevenpiano.update.UpdateServer

/**
 * The emulator's firmware update (debug builds only, `Emulator.firmware`; v1.24 — M57): the [server] its releases come
 * from and the [publicKey] they are signed with, in place of GitHub's and the author's.
 */
class FirmwareStandIn(val server: UpdateServer, val publicKey: ByteArray)
