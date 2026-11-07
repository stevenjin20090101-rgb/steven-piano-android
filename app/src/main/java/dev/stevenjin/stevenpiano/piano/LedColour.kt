// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the LED strip's colour comes from (M80, Piano › Lights and screen › Colour): the tablet's own choice, kept with
 * the app's other settings, never the piano's. [ALBUM_ART]: the loaded piece's cover, else the custom colour; [CUSTOM]:
 * the colour picked on the page (`ledcolor`); [PALETTE]: the piano's Reactive palettes, as before M80 (the default, so
 * an updated piano looks as it did until someone chooses).
 */
enum class LedColourSource { ALBUM_ART, CUSTOM, PALETTE }

/**
 * The loaded piece's cover as the strip takes it ([LedColour.plan]): [rgb] its colour (0xRRGGBB), null for a grey cover
 * or none. The keeper holds a null [CoverTint] while a new piece's cover is read, so the strip never flashes back to the
 * custom colour between two covers.
 */
data class CoverTint(val rgb: Int?) {
    companion object {
        val None = CoverTint(null)
    }
}

/**
 * The piano's LED colour (M80, firmware 2.1.2; `~/.claude/plans/m80-led-colour-firmware-brief.md`), pure: its four keys
 * and its fact, what each accepts, the levels the page offers, and what the tablet sends for each choice. A piano whose
 * dump lacks `!ledcolorproto=1` gets none of it: nothing shows and nothing is sent.
 *
 * - `ledcolor RRGGBB`: the strip's own colour (Static, and the Solid palette in Reactive); saved.
 * - `ledtint RRGGBB|off`: a live colour over it, the album art's; **never saved** (the piano's `save` and its own
 *   auto-save ignore it, a restart clears it), and on the tablet always a temporary write ([PianoSettingsRepository.tint]).
 * - `ledfade 0..5000`: the cross-fade, ms, whenever the colour shown changes; saved.
 * - `ledambient 0..64`: Reactive's background glow, of 255 before `ledbright`; saved.
 *
 * Every key reads back lower case (`ledcolor=ff8800`, `ledtint=off`), so the tablet writes lower case too.
 */
object LedColour {
    const val COLOR = "ledcolor"
    const val TINT = "ledtint"
    const val FADE = "ledfade"
    const val AMBIENT = "ledambient"

    /** The fact a piano with the four keys reports: `!ledcolorproto=1`. */
    const val PROTO_FACT = "ledcolorproto"

    /** `ledtint off`: the custom colour shows. */
    const val OFF = "off"

    val names: Set<String> = setOf(COLOR, TINT, FADE, AMBIENT)

    /** The strip's mode and palette, as the table names them, and the values the colour cares about. */
    const val MODE = "ledmode"
    const val PALETTE = "reactcolor"
    const val STATIC = "1"
    const val REACTIVE = "3"
    const val SOLID = "1"

    const val FADE_MAX_MS = 5_000
    const val AMBIENT_MAX = 64

    /** Whether the piano's [facts] say it has the four keys. */
    fun supported(facts: Map<String, String>?): Boolean = facts?.get(PROTO_FACT)?.trim() == "1"

    /** Whether [value] is what the piano takes for [name] (one of [names]): six hex digits, `off` for the tint, a number in range. */
    fun accepts(name: String, value: String): Boolean = when (name) {
        COLOR -> HEX.matches(value)
        TINT -> value == OFF || HEX.matches(value)
        FADE -> value.toIntOrNull()?.let { it in 0..FADE_MAX_MS } == true
        AMBIENT -> value.toIntOrNull()?.let { it in 0..AMBIENT_MAX } == true
        else -> false
    }

    /** [rgb] (0xRRGGBB) as the wire writes it: six lower-case hex digits. */
    fun hex(rgb: Int): String = "%06x".format(Locale.ROOT, rgb and 0xFFFFFF)

    /** The colour a `ledcolor`/`ledtint` value names; null for `off`, anything else, or nothing. */
    fun rgb(wire: String?): Int? = wire?.trim()?.takeIf { HEX.matches(it) }?.toInt(16)

    /** One named level of a choice ([GLOW], [FADES]): its chip's [label], the [value] it writes. */
    data class Level(val label: String, val value: Int, val spoken: String = label)

    /** Background glow (Reactive): Off, Low, Medium, High, as `ledambient`. */
    val GLOW: List<Level> = listOf(Level("Off", 0), Level("Low", 12), Level("Medium", 24), Level("High", 40))

    /** Colour change (MOTION): how long the strip fades to a new colour, as `ledfade`. */
    val FADES: List<Level> = listOf(
        Level("Instant", 0),
        Level("0.5 s", 500, "half a second"),
        Level("1.5 s", 1_500, "one and a half seconds"),
        Level("3 s", 3_000, "3 seconds"),
    )

    /** Which of [levels] the piano's [wire] value is; null when it holds another (set elsewhere) or is unknown. */
    fun levelOf(levels: List<Level>, wire: String?): Int? {
        val value = wire?.trim()?.toIntOrNull() ?: return null
        return levels.indexOfFirst { it.value == value }.takeIf { it >= 0 }
    }

    /** The sources the strip's [mode] offers: all three in Reactive, Album art and Custom in Static, none otherwise. */
    fun sources(mode: String?): List<LedColourSource> = when (mode?.trim()) {
        REACTIVE -> LedColourSource.entries
        STATIC -> listOf(LedColourSource.ALBUM_ART, LedColourSource.CUSTOM)
        else -> emptyList()
    }

    /** [source] as the page shows it in [mode]: Static has no palettes, so Palette reads (and is) the custom colour there. */
    fun shown(source: LedColourSource, mode: String?): LedColourSource =
        if (source == LedColourSource.PALETTE && mode?.trim() != REACTIVE) LedColourSource.CUSTOM else source

    /** What the piano should hold for a choice: the Solid palette ([solid], Reactive only) and the [tint] (`off` or six hex digits). */
    data class Plan(val solid: Boolean, val tint: String)

    /**
     * The commands for [source] in [mode] with the loaded piece's [cover] (M80): Palette, the palette as chosen and
     * `ledtint off`; Custom, `reactcolor 1` in Reactive and `ledtint off`; Album art, `reactcolor 1` in Reactive and the
     * cover's colour as the tint, `off` for a grey cover or none, so the custom colour shows.
     */
    fun plan(source: LedColourSource, mode: String?, cover: Int?): Plan {
        val reactive = mode?.trim() == REACTIVE
        return when (source) {
            LedColourSource.PALETTE -> Plan(solid = false, tint = OFF)
            LedColourSource.CUSTOM -> Plan(solid = reactive, tint = OFF)
            LedColourSource.ALBUM_ART -> Plan(solid = reactive, tint = cover?.let(::hex) ?: OFF)
        }
    }

    /** The background glow does anything only in Reactive on the Solid palette: with Album art or Custom (firmware 2.1.2). */
    fun glowShows(source: LedColourSource, mode: String?): Boolean =
        mode?.trim() == REACTIVE && shown(source, mode) != LedColourSource.PALETTE

    /**
     * Whether the Lights page shows [row] (M80) for [ready] (null: not read yet) and the tablet's [chosen] source. Only the
     * colour's rows and the palette depend on it ([dependsOnColour]): on a piano without `!ledcolorproto=1` the colour's
     * rows never show and the palette always does, as before. With it: Colour and the custom colour in Static and Reactive
     * (the custom colour with Album art, as its fallback, and Custom), the palette in Reactive with Palette, the background
     * glow in Reactive with Album art or Custom, and the colour change always.
     */
    fun shows(row: PianoRow, ready: PianoState.Ready?, chosen: LedColourSource): Boolean {
        if (!dependsOnColour(row)) return true
        val supported = ready != null && supported(ready.facts)
        val mode = ready?.values?.get(MODE)?.trim()
        val source = shown(chosen, mode)
        val coloured = sources(mode).isNotEmpty()
        return when (row) {
            PianoRow.ColourSource -> supported && coloured
            PianoRow.CustomColour -> supported && coloured && source != LedColourSource.PALETTE
            PianoRow.BackgroundGlow -> supported && glowShows(chosen, mode)
            PianoRow.ColourChange -> supported
            else -> !supported || (mode == REACTIVE && source == LedColourSource.PALETTE)   // the palette
        }
    }

    /** The rows that come and go with the colour's choice ([shows]): its own four, and the Reactive palette. */
    fun dependsOnColour(row: PianoRow): Boolean = when (row) {
        PianoRow.ColourSource, PianoRow.CustomColour, PianoRow.BackgroundGlow, PianoRow.ColourChange -> true
        is PianoRow.Control -> row.setting.name == PALETTE
        else -> false
    }

    // ---- Colour as hue, saturation and value (the custom colour's sliders, the cover's colour) ---------------------

    /** A colour as hue (degrees, 0 until 360), saturation and value (0 to 1). */
    data class Hsv(val hue: Float, val saturation: Float, val value: Float)

    fun hsv(rgb: Int): Hsv {
        val r = (rgb shr 16 and 0xFF) / 255f
        val g = (rgb shr 8 and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val chroma = hi - lo
        val hue = when {
            chroma == 0f -> 0f
            hi == r -> 60f * ((g - b) / chroma)
            hi == g -> 60f * ((b - r) / chroma + 2f)
            else -> 60f * ((r - g) / chroma + 4f)
        }
        return Hsv(((hue % 360f) + 360f) % 360f, if (hi == 0f) 0f else chroma / hi, hi)
    }

    /** The colour of [hue], [saturation] and [value] as 0xRRGGBB. */
    fun rgb(hue: Float, saturation: Float, value: Float = 1f): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private val HEX = Regex("^[0-9a-fA-F]{6}$")
}
