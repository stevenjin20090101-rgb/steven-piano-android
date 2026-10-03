// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

/**
 * Every note the Piano tab gained in v1.13 (M31b), in one place for the designer's review: one plain
 * sentence under a row that needed one, sentence case, no full stop, under 60 characters, no jargon
 * (DESIGN.md › v1.13 › Notes). The piano's own settings are keyed by their firmware name; the table
 * ([dev.stevenjin.stevenpiano.piano.PianoSettings]) takes them, so the web panel shows the same words.
 * Notes written before v1.13 stay where they were.
 */
object SettingNotes {
    /** The piano's settings, by firmware name. */
    val piano: Map<String, String> = mapOf(
        // Sound and touch › LOUDNESS
        "fullpower" to "Every note strikes at full strength",
        "volume" to "How loud the piano plays overall",
        // Fine tuning › TOUCH
        "velcurve" to "Shapes how loudness rises from soft to hard",
        "velmult" to "Scales every note's loudness up or down",
        "min" to "How hard the quietest white key strikes",
        "minblack" to "How hard the quietest black key strikes",
        "max" to "How hard the loudest note strikes",
        // Fine tuning › TIMING
        "humanvel" to "Adds a little random loudness to sound human",
        "humantime" to "Adds a little random timing to sound human",
        "burstgap" to "Notes this close together count as a burst",
        "burstboost" to "Extra strength for fast runs of notes",
        "minstrike" to "The shortest time a key is held down",
        "isostrike" to "How long a note on its own is struck",
        "isogap" to "Silence that makes the next note count as lone",
        "gap" to "The pause before the same key strikes again",
        "hold" to "The longest a key stays down, to keep coils cool",
        "restrike" to "Strikes a held note again after this long",
        // Fine tuning › RELEASE and DRIVE
        "softrelease" to "Lets keys up gently instead of dropping them",
        "releasepwm" to "How gently a key is let up",
        "releasems" to "How long a key takes to be let up",
        "freq" to "How fast the coils are driven; change it if they hum",
        // Lights and screen › Strip set-up and THE PIANO'S SCREEN
        "ledoffset" to "Moves the lit notes along the strip",
        "ledscale" to "Stretches or shrinks the notes along the strip",
        "ledtail" to "LEDs left dark at the far end",
        "ledglow" to "How many LEDs light up beside each note",
        "decay" to "How quickly a lit note fades out",
        "dimfloor" to "How bright the piano's screen stays when dimmed",
        // Pedal
        "pedalhalf" to "Follows partway pedal presses, not just up and down",
        "pedalup" to "Where the pedal rests when it is up",
        "pedaldown" to "Where the pedal sits when it is pressed",
    )

    // ---- The piano's rows that are not settings --------------------------------------------------

    const val TEST_LED = "Lights one key's LED to line up the strip"
    const val READ_STATUS = "Asks the piano for its health report"
    const val SAVE_TO_PIANO = "Keeps these settings after the piano restarts"
    const val ALL_KEYS_OFF = "Lets go of every key and the pedal at once"

    // ---- INSTRUMENTS -------------------------------------------------------------------------------

    const val AUTO_CONNECT = "Connects to the piano when the app opens"

    // ---- PLAYING -----------------------------------------------------------------------------------

    const val DEFAULT_TEMPO = "How fast pieces start; Now playing can change it"
    const val VELOCITY = "Plays every piece louder or softer"
    const val FOLD = "Moves notes the piano lacks into its range"
    const val SKIP_DRUMS = "Skips the drum part of a file"

    // ---- SHARING -----------------------------------------------------------------------------------

    const val WEB_PANEL = "Control the piano from a browser"
    const val GUESTS_CAN_REQUEST = "Anyone with the poster's code can ask for a piece"
    const val APPROVE_FIRST = "A request waits for you before it plays"
    const val RELAY_ADDRESS = "The relay this tablet is enrolled with"

    // ---- THIS TABLET -------------------------------------------------------------------------------

    const val ALBUM_BACKDROP = "The album's colours drift behind the player while playing"
    const val RESTING_BACKGROUND = "Black, or the app's own paper or ink"
    const val RESTING_SHOWS = "The piece's art and notes, or the paper roll"
    const val CHECK_AUTOMATICALLY = "Looks for a newer version by itself"
    const val CHECK_FOR_APP_UPDATES = "Looks for a newer Steven Piano now"
    const val FETCH_EVERY_COMPOSER = "Looks up every composer on Wikipedia now"

    /** Every note above, for the rules' test. */
    val all: List<String> = piano.values + listOf(
        TEST_LED, READ_STATUS, SAVE_TO_PIANO, ALL_KEYS_OFF, AUTO_CONNECT, DEFAULT_TEMPO, VELOCITY, FOLD, SKIP_DRUMS,
        WEB_PANEL, GUESTS_CAN_REQUEST, APPROVE_FIRST, RELAY_ADDRESS, ALBUM_BACKDROP, RESTING_BACKGROUND, RESTING_SHOWS,
        CHECK_AUTOMATICALLY, CHECK_FOR_APP_UPDATES, FETCH_EVERY_COMPOSER,
    )
}
