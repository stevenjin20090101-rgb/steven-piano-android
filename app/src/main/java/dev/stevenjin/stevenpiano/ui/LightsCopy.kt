// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.piano.CoverTint
import dev.stevenjin.stevenpiano.piano.LedColour
import dev.stevenjin.stevenpiano.piano.LedColourSource
import dev.stevenjin.stevenpiano.piano.LedRestChoice

/** One of the custom colour's preset swatches (M80): its colour (0xRRGGBB) and the name TalkBack says. */
data class LedSwatch(val name: String, val rgb: Int)

/**
 * The words of the strip's colour (M80, Piano › Lights and screen): the rows' labels, the choice's chips, the line saying
 * what the loaded piece's cover gives, the preset swatches and how a colour is named aloud; and (M81) Between pieces. The
 * notes under the rows are in [SettingNotes] with the others.
 */
object LightsCopy {
    const val COLOUR = "Colour"
    const val CUSTOM_COLOUR = "Custom colour"
    const val HUE = "Hue"
    const val SATURATION = "Saturation"
    const val BACKGROUND_GLOW = "Background glow"
    const val COLOUR_CHANGE = "Colour change"

    /** M81: what the strip shows while nothing plays, in Reactive. */
    const val BETWEEN_PIECES = "Between pieces"

    fun source(source: LedColourSource): String = when (source) {
        LedColourSource.ALBUM_ART -> "Album art"
        LedColourSource.CUSTOM -> "Custom"
        LedColourSource.PALETTE -> "Palette"
    }

    /** The note under Colour, for the choice shown. */
    fun sourceNote(source: LedColourSource): String = when (source) {
        LedColourSource.ALBUM_ART -> SettingNotes.LED_COLOUR_ALBUM_ART
        LedColourSource.CUSTOM -> SettingNotes.LED_COLOUR_CUSTOM
        LedColourSource.PALETTE -> SettingNotes.LED_COLOUR_PALETTE
    }

    /** Between pieces' chips (M81). */
    fun rest(choice: LedRestChoice): String = when (choice) {
        LedRestChoice.SOLID -> "Solid colour"
        LedRestChoice.NOTES -> "Notes only"
    }

    /** The note under Between pieces, for the choice shown (M81). */
    fun restNote(choice: LedRestChoice): String = when (choice) {
        LedRestChoice.SOLID -> SettingNotes.LED_REST_SOLID
        LedRestChoice.NOTES -> SettingNotes.LED_REST_NOTES
    }

    /** With Album art: what the loaded piece ([title], null with none) gives the strip now ([cover], null while it is read). */
    fun now(title: String?, cover: CoverTint?): String = when {
        title == null -> "No piece loaded: the custom colour shows"
        cover == null -> "Reading the cover of $title…"
        cover.rgb == null -> "No colour in this cover: the custom colour shows"
        else -> "From the cover of $title"
    }

    /** The custom colour's preset swatches, warm to cool, every one bright enough for the strip. */
    val SWATCHES: List<LedSwatch> = listOf(
        LedSwatch("Warm white", 0xFFB066),
        LedSwatch("White", 0xFFFFFF),
        LedSwatch("Red", 0xFF1A1A),
        LedSwatch("Orange", 0xFF6A00),
        LedSwatch("Amber", 0xFFA000),
        LedSwatch("Yellow", 0xFFE000),
        LedSwatch("Green", 0x2BFF3A),
        LedSwatch("Teal", 0x00FFB0),
        LedSwatch("Sky blue", 0x00C8FF),
        LedSwatch("Blue", 0x1A4DFF),
        LedSwatch("Violet", 0x8A2BFF),
        LedSwatch("Pink", 0xFF3D9A),
    )

    /**
     * How [rgb] is said aloud: a swatch's own name, else its hue in plain words ("orange", "sky blue"), "pale" before a
     * washed-out one, "white" for one with almost no colour.
     */
    fun name(rgb: Int): String {
        SWATCHES.firstOrNull { it.rgb == rgb and 0xFFFFFF }?.let { return it.name.lowercase() }
        val hsv = LedColour.hsv(rgb)
        if (hsv.saturation < WHITE_BELOW) return "white"
        val hue = HUES.first { hsv.hue < it.first }.second
        return if (hsv.saturation < PALE_BELOW) "pale $hue" else hue
    }

    private const val WHITE_BELOW = 0.12f
    private const val PALE_BELOW = 0.45f

    /** Hue names by the degree each ends before. */
    private val HUES = listOf(
        15f to "red", 40f to "orange", 52f to "amber", 70f to "yellow", 95f to "lime", 150f to "green", 175f to "teal",
        200f to "sky blue", 245f to "blue", 270f to "indigo", 295f to "violet", 330f to "magenta", 350f to "pink", 361f to "red",
    )
}
