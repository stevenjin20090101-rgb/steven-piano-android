// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.sort

import dev.stevenjin.stevenpiano.data.db.RankCandidate
import dev.stevenjin.stevenpiano.net.Deezer
import dev.stevenjin.stevenpiano.net.DeezerBusyException
import dev.stevenjin.stevenpiano.net.DeezerTrack
import dev.stevenjin.stevenpiano.net.DeezerUrls
import dev.stevenjin.stevenpiano.net.FakeTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * M82: world popularity from Deezer, against a fake server: the search's exact address, its answer read, the best match
 * picked by title (for a song and for a classical piece Deezer files under its pianist), the album's label for the Disney
 * section, Deezer's own "quota" error waited out, and a piece with nothing to ask for never asked.
 */
class WorldRanksTest {
    private val transport = FakeTransport()
    private val deezer = Deezer(io = Dispatchers.Unconfined, log = {}, transport = transport)

    private val capaldi = "https://api.deezer.com/search?q=someone+you+loved+lewis+capaldi&limit=25"
    private val capaldiJson = """
        {"data": [
          {"id": 1, "title": "Someone You Loved (Piano Version)", "title_short": "Someone You Loved", "rank": 990000,
           "artist": {"name": "Piano Tribute Players"}, "album": {"id": 10, "title": "Piano Hits"}},
          {"id": 2, "title": "Someone You Loved (Live)", "title_short": "Someone You Loved", "title_version": "(Live)", "rank": 400000,
           "artist": {"name": "Lewis Capaldi"}, "album": {"id": 11, "title": "Live"}},
          {"id": 3, "title": "Someone You Loved", "title_short": "Someone You Loved", "rank": 950000,
           "artist": {"name": "Lewis Capaldi"}, "album": {"id": 302127, "title": "Divinely Uninspired To A Hellish Extent"}},
          {"id": 4, "title": "Before You Go", "rank": 900000, "artist": {"name": "Lewis Capaldi"}, "album": {"id": 302127}},
          {"id": 5, "rank": 5, "artist": {"name": "Untitled"}}
        ], "total": 5}
    """.trimIndent()

    private val chopin = "https://api.deezer.com/search?q=nocturne+in+e+flat+major+op+9+no+2+chopin&limit=25"
    private val chopinJson = """
        {"data": [
          {"title": "Nocturnes, Op. 9: No. 2 in E-Flat Major", "title_short": "Nocturnes, Op. 9: No. 2 in E-Flat Major", "rank": 610000,
           "artist": {"name": "Arthur Rubinstein"}, "album": {"id": 20, "title": "Chopin: Nocturnes"}},
          {"title": "Nocturne in E-Flat Major", "rank": 700000, "artist": {"name": "Some Other Pianist"}, "album": {"id": 21, "title": "Relaxing Piano"}}
        ]}
    """.trimIndent()

    @Test
    fun `the search is Deezer's own, and its answer is read, untitled tracks skipped`() = runBlocking {
        assertEquals(capaldi, DeezerUrls.search(" someone you loved lewis capaldi ", 25))
        assertTrue(DeezerUrls.allowed(capaldi))
        for (refused in listOf("http://api.deezer.com/search?q=x", "https://api.deezer.com:8443/x", "https://evil.example/x", "https://x@api.deezer.com/", "https://api.deezer.com.evil.example/")) {
            assertFalse(refused, DeezerUrls.allowed(refused))
        }
        transport.ok(capaldi, capaldiJson.toByteArray())
        val tracks = deezer.search("someone you loved lewis capaldi", 25)
        assertEquals(4, tracks.size)
        assertEquals("Someone You Loved", tracks[2].title)
        assertEquals(950_000, tracks[2].rank)
        assertEquals(302_127L, tracks[2].albumId)
        assertEquals("Lewis Capaldi", tracks[2].artist)
    }

    @Test
    fun `the best match is the artist's, by the closest title, then the most played`() = runBlocking {
        transport.ok(capaldi, capaldiJson.toByteArray()).ok(chopin, chopinJson.toByteArray())
        val song = WorldRankMatch.pick("Someone You Loved", "Lewis Capaldi", classical = false, deezer.search(WorldRankMatch.term("Someone You Loved", "Lewis Capaldi"), 25))
        assertEquals("not the tribute's, though it is played more; the studio track over the live one", 950_000, song?.rank)
        // A classical piece: Deezer names the pianist, the album names the composer.
        val nocturne = WorldRankMatch.pick(
            "Nocturne in E-flat major, Op. 9 No. 2",
            "Chopin",
            classical = true,
            deezer.search(WorldRankMatch.term("Nocturne in E-flat major, Op. 9 No. 2", "Chopin"), 25),
        )
        assertEquals(610_000, nocturne?.rank)
        assertNull("a song of the same name by someone else is no match", WorldRankMatch.pick("Perfect", "Ed Sheeran", false, deezer.search("perfect ed sheeran", 25)))
    }

    @Test
    fun `a classical piece is its work's most played recording, the catalogue number deciding which work`() {
        assertEquals("bwv 870", WorldRankMatch.catalogue("Prelude and Fugue in C Major, WTC II, BWV 870 [2]"))
        assertEquals("op 32 no 13", WorldRankMatch.catalogue("13 Préludes, Opus 32 (1910) — No. 13"))
        assertEquals("d 960", WorldRankMatch.catalogue("Sonata in B-flat Major, D960 [8]"))
        assertEquals("k 331", WorldRankMatch.catalogue("Sonata in A Major, KV 331"))
        assertNull(WorldRankMatch.catalogue("Clair de lune"))
        assertEquals("bwv 870 bach", WorldRankMatch.fallbackTerm("Prelude and Fugue in C Major, WTC II, BWV 870", "Bach"))
        fun track(title: String, rank: Int, artist: String = "Some Pianist", album: String = "Chopin: Nocturnes") = DeezerTrack(title, title, artist, album, 1, rank)
        val results = listOf(
            track("Nocturne in C-Sharp Minor, Op. posth.", 900_000),   // another work: no Op. 48 No. 1
            track("Nocturnes, Op. 48: No. 1 in C Minor", 300_000),
            track("Chopin: Nocturne in C Minor, Op. 48 No. 1", 450_000, artist = "Another Pianist"),
            track("Nocturne in C Minor, Op. 48 No. 1", 990_000, album = "Relaxing Piano"),   // nothing names Chopin
        )
        assertEquals(450_000, WorldRankMatch.pick("Nocturne in C Minor, Op. 48 No. 1", "Chopin", classical = true, results)?.rank)
        assertEquals("a set's title holds its pieces", 450_000, WorldRankMatch.pick("Nocturnes, Op. 48", "Chopin", classical = true, results)?.rank)
    }

    @Test
    fun `an album on a Disney label is Disney's, read from the album's answer`() = runBlocking {
        transport.ok(DeezerUrls.album(302127), """{"id": 302127, "title": "Divinely Uninspired", "label": "Columbia"}""".toByteArray())
        transport.ok(DeezerUrls.album(5000), """{"id": 5000, "title": "Frozen (Original Motion Picture Soundtrack)", "label": "Walt Disney Records"}""".toByteArray())
        transport.ok(DeezerUrls.album(5001), """{"error": {"type": "DataException", "message": "no data", "code": 800}}""".toByteArray())
        assertFalse(DisneyLabel.isDisney(deezer.albumLabel(302127)))
        assertTrue(DisneyLabel.isDisney(deezer.albumLabel(5000)))
        assertTrue(DisneyLabel.isDisney("Disney Music Group"))
        assertNull("Deezer's \"no data\" is no label", deezer.albumLabel(5001))
        assertNull("a missing album too", deezer.albumLabel(5002))
        transport.ok(DeezerUrls.album(5003), """{"error": {"type": "Exception", "message": "Quota limit exceeded", "code": 4}}""".toByteArray())
        try {
            deezer.albumLabel(5003)
            fail("Deezer's quota error is a wait")
        } catch (e: DeezerBusyException) {
            assertTrue(e.message.orEmpty(), "Quota" in e.message.orEmpty())
        }
    }

    @Test
    fun `a run looks every piece up once, waits out Deezer's quota, asks a song's album, and never asks for a piece with no artist`() = runBlocking {
        val frozen = "https://api.deezer.com/search?q=let+it+go+idina+menzel&limit=25"
        var quota = true
        transport.answers[frozen] = FakeTransport.Answer(200, body = """{"error": {"message": "Quota limit exceeded", "code": 4}}""".toByteArray())
        transport.ok(DeezerUrls.album(5000), """{"label": "Walt Disney Records"}""".toByteArray())
        val pieces = mutableListOf(
            RankCandidate(1, "Let It Go", "Idina Menzel", genre = 2, rankKey = "let it go|idina menzel"),
            RankCandidate(2, "Untitled sketch", "", genre = 2, rankKey = "untitled sketch|"),
            RankCandidate(3, "Nocturne in E-flat major, Op. 9 No. 2", "Chopin", genre = 1, rankKey = "nocturne in e-flat major, op. 9 no. 2|chopin"),
        )
        pieces += RankCandidate(4, "Prelude and Fugue in C Major, WTC II, BWV 870", "Bach", genre = 1, rankKey = "prelude and fugue in c major, wtc ii, bwv 870|bach")
        transport.ok(chopin, chopinJson.toByteArray())
        // Its full title finds nothing (Deezer wants every word); its catalogue number and composer do.
        val bach = "https://api.deezer.com/search?q=prelude+and+fugue+in+c+major+wtc+ii+bwv+870+bach&limit=25"
        val bwv = "https://api.deezer.com/search?q=bwv+870+bach&limit=25"
        transport.ok(bwv, """{"data": [{"title": "The Well-Tempered Clavier, Book 2: Prelude and Fugue No. 1 in C Major, BWV 870", "rank": 135740, "artist": {"name": "Keith Jarrett"}, "album": {"id": 30, "title": "J.S. Bach: The Well-Tempered Clavier, Book II"}}]}""".toByteArray())
        val kept = mutableListOf<List<Any?>>()
        val waits = mutableListOf<Long>()
        val ranks = WorldRanks(
            api = deezer,
            store = object : WorldRanks.Store {
                override suspend fun candidates(after: Long, limit: Int) = pieces.filter { it.id > after }.take(limit)

                override suspend fun keep(id: Long, rank: Int?, disney: Boolean?, at: Long, key: String) {
                    kept += listOf(id, rank, disney, key)
                    pieces.removeAll { it.id == id }
                }
            },
            online = { true },
            clock = { 1_000L },
            pause = { ms ->
                waits += ms
                if (quota) {
                    quota = false
                    transport.ok(frozen, """{"data": [{"title": "Let It Go", "rank": 870000, "artist": {"name": "Idina Menzel"}, "album": {"id": 5000, "title": "Frozen"}}]}""".toByteArray())
                }
            },
        )
        assertEquals(4, ranks.run())
        assertEquals(
            listOf(
                listOf(1L, 870_000, true, "let it go|idina menzel"),
                listOf(2L, null, null, "untitled sketch|"),
                listOf(3L, 610_000, null, "nocturne in e-flat major, op. 9 no. 2|chopin"),   // a classical piece's album is not asked
                listOf(4L, 135_740, null, "prelude and fugue in c major, wtc ii, bwv 870|bach"),
            ),
            kept,
        )
        assertEquals("the quota waited out once", listOf(WorldRanks.FIRST_BACKOFF_MS), waits)
        val asked = transport.opened.map { it.first }
        assertEquals(listOf(frozen, frozen, DeezerUrls.album(5000), chopin, bach, bwv), asked)
        assertEquals("nothing is left to ask", 0, ranks.run())
        assertEquals(asked.size, transport.opened.size)
    }
}
