// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

/**
 * RFC 4180 CSV: quoted fields may hold commas, line breaks and doubled quotes; lines end
 * with CRLF, LF or CR; a leading byte-order mark is ignored. Lenient about stray quotes.
 */
object CsvReader {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var atFieldStart = true
        var i = if (text.startsWith('﻿')) 1 else 0

        fun endField() {
            row += field.toString()
            field.setLength(0)
            atFieldStart = true
        }

        fun endRow() {
            endField()
            rows += row
            row = mutableListOf()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i++
                }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' && atFieldStart -> {
                    quoted = true
                    atFieldStart = false
                }
                c == ',' -> endField()
                c == '\r' -> if (text.getOrNull(i + 1) != '\n') endRow()
                c == '\n' -> endRow()
                else -> {
                    field.append(c)
                    atFieldStart = false
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty() || !atFieldStart) endRow()
        return rows
    }
}
