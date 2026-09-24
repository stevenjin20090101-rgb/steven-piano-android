// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

/** Messages as readable hex, e.g. "90 3C 40", so failures show what the piano would get. */
fun MidiBatch.hex(): List<String> = (0 until size).map { hex(status(it), data1(it), data2(it)) }

fun hex(status: Int, data1: Int, data2: Int): String = "%02X %02X %02X".format(status, data1, data2)
