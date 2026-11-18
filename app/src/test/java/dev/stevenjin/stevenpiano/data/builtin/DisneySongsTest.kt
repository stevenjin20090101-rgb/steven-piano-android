// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.builtin

import dev.stevenjin.stevenpiano.data.db.PieceEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M82: the Disney section's own list, as the app ships it (`assets/disney_songs.json`): Disney's songs are found by
 * their composers, the films' names and their titles; songs of the same name by others, and other songs by the same
 * composers, are not. And an album on a Disney label makes any song Disney's.
 */
class DisneySongsTest {
    private val disney = DisneySongs.parse(File("src/main/assets/${DisneySongs.ASSET}").readText())

    @Test
    fun `Disney's songs are listed by composer, film or title`() {
        for ((composer, title) in listOf(
            "Idina Menzel" to "Let It Go",
            "" to "Do You Want to Build a Snowman?",
            "Elton John" to "Can You Feel the Love Tonight",
            "Alan Menken" to "Belle",
            "Kristen Anderson-Lopez & Robert Lopez" to "Into the Unknown",
            "Frozen" to "Let It Go",
            "Jessica Darrow" to "Surface Pressure",
            "Lin-Manuel Miranda" to "We Don't Talk About Bruno",
            "Unknown" to "Let It Go (From \"Frozen\")",
            "Randy Newman" to "You've Got a Friend in Me",
            "Phil Collins" to "You'll Be in My Heart",
        )) {
            assertTrue("$title by $composer", disney.listed(composer, title))
        }
    }

    @Test
    fun `songs of the same name by others, and the composers' other songs, are not`() {
        for ((composer, title) in listOf(
            "James Bay" to "Let It Go",
            "Elton John" to "Rocket Man",
            "Phil Collins" to "In the Air Tonight",
            "Lin-Manuel Miranda" to "My Shot",
            "Tim Rice" to "Superstar",
            "Coco Jones" to "ICU",
            "Ed Sheeran" to "Perfect",
            "Claude Debussy" to "Clair de lune",
            "Dwayne Johnson" to "Face Off",
        )) {
            assertFalse("$title by $composer", disney.listed(composer, title))
        }
    }

    @Test
    fun `an album on a Disney label makes a song Disney's`() {
        val song = PieceEntity(
            title = "Speechless", composer = "Naomi Scott", composerKey = "naomi scott", composerShort = "Naomi Scott", collection = null,
            sha256 = "s1", fileName = "s1.mid", sourceName = "", sizeBytes = 0, durationMs = 0, noteCount = 0, addedAt = 0,
            searchText = "speechless naomi scott", titleKey = "speechless",
        )
        assertFalse("not in the list: Speechless is a common title", disney.isDisney(song))
        assertTrue(disney.isDisney(song.copy(disneyLabel = true)))
        assertFalse(disney.isDisney(song.copy(disneyLabel = false)))
    }
}
