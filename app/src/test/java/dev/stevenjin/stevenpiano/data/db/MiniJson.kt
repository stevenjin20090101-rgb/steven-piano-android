// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

/**
 * Just enough JSON to read Room's exported schemas in a JVM test (android.jar's org.json is a
 * stub there): objects become maps, arrays lists, numbers doubles.
 */
object MiniJson {
    fun parse(text: String): Any? = Reader(text).run { value().also { skipSpace(); check(at == text.length) { "Trailing text at $at" } } }

    private class Reader(val text: String) {
        var at = 0

        fun value(): Any? {
            skipSpace()
            return when (val c = text[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("Unexpected '$c' at $at")
            }
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            expect('{')
            skipSpace()
            if (text[at] == '}') return map.also { at++ }
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                expect(':')
                map[key] = value()
                skipSpace()
                if (text[at] == ',') at++ else return map.also { expect('}') }
            }
        }

        private fun array(): List<Any?> {
            val list = ArrayList<Any?>()
            expect('[')
            skipSpace()
            if (text[at] == ']') return list.also { at++ }
            while (true) {
                list += value()
                skipSpace()
                if (text[at] == ',') at++ else return list.also { expect(']') }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = text[at++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> out.append(text.substring(at, at + 4).toInt(16).toChar()).also { at += 4 }
                        else -> out.append(e)
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
            return text.substring(start, at).toDouble()
        }

        private fun word(word: String, value: Any?): Any? {
            check(text.startsWith(word, at)) { "Expected $word at $at" }
            at += word.length
            return value
        }

        private fun expect(c: Char) {
            check(text[at] == c) { "Expected '$c' at $at, found '${text[at]}'" }
            at++
        }

        fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }
    }
}
