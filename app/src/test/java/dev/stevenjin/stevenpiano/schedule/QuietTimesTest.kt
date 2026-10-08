// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import dev.stevenjin.stevenpiano.data.db.ScheduleEntity
import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Quiet times (DESIGN.md › v1.20 — M54): sections built from the rows and back, what may be saved, the quiet at a moment
 * across midnight and across sections, the next block; the gate's stop as a block begins and Play anyway's lift; the
 * repository replacing the quiet rows alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuietTimesTest {
    private val zone = ZoneId.of("Europe/London")

    /** 2026-10-05 is a Monday. */
    private fun at(date: String, time: String): ZonedDateTime = ZonedDateTime.of(LocalDateTime.parse("${date}T$time"), zone)

    private fun ms(at: ZonedDateTime): Long = at.toInstant().toEpochMilli()

    private fun hm(text: String): Int = text.substringBefore(':').toInt() * 60 + text.substringAfter(':').toInt()

    private fun block(from: String, to: String) = QuietBlock(hm(from), hm(to))

    private fun quiet(id: Long, name: String, days: Int, from: String, to: String) =
        ScheduleEntity(id, days, hm(from), ScheduleKind.QUIET, name, hm(to), null, true, createdAt = 0)

    private val monday = Occurrences.dayBit(java.time.DayOfWeek.MONDAY)
    private val sunday = Occurrences.dayBit(java.time.DayOfWeek.SUNDAY)

    @Test
    fun `sections come from the quiet rows, by name and days in the order saved, their blocks by start, and go back to rows`() {
        val rows = listOf(
            quiet(1, "School days", Occurrences.WEEKDAYS, "09:40", "10:30"),
            ScheduleEntity(2, Occurrences.WEEKDAYS, 750, ScheduleKind.CHANNEL, "calm", 795, 70, true, createdAt = 0),   // a timed play's row
            quiet(3, "Night", Occurrences.ALL_DAYS, "21:00", "07:00"),
            quiet(4, "School days", Occurrences.WEEKDAYS, "08:40", "09:30"),
            quiet(5, "School days", Occurrences.WEEKENDS, "10:00", "11:00"),
        )
        val sections = QuietTimes.sections(rows)
        assertEquals(
            listOf(
                QuietSection("School days", Occurrences.WEEKDAYS, listOf(block("08:40", "09:30"), block("09:40", "10:30"))),
                QuietSection("Night", Occurrences.ALL_DAYS, listOf(block("21:00", "07:00"))),
                QuietSection("School days", Occurrences.WEEKENDS, listOf(block("10:00", "11:00"))),
            ),
            sections,
        )
        val kept = QuietTimes.rows(sections, createdAt = 42).mapIndexed { i, row -> row.copy(id = i + 1L) }
        assertTrue(kept.all { it.kind == ScheduleKind.QUIET && it.volumePct == null && it.enabled && it.createdAt == 42L })
        assertEquals("back again, the same", sections, QuietTimes.sections(kept))
        assertEquals("a name is kept trimmed", "Night", QuietTimes.rows(listOf(QuietSection("  Night ", 1, listOf(block("21:00", "07:00")))), 0).single().target)
    }

    @Test
    fun `what may be saved, in the editors' words`() {
        val school = QuietSection("School days", Occurrences.WEEKDAYS, listOf(block("08:40", "09:30"), block("09:40", "10:30")))
        assertNull(QuietTimes.validate(listOf(school)))
        assertNull("none at all", QuietTimes.validate(emptyList()))
        assertEquals(QuietTimes.TOO_MANY_SECTIONS, QuietTimes.validate((1..13).map { school.copy(name = "S$it") }))
        assertNull(QuietTimes.validate((1..12).map { school.copy(name = "S$it") }))
        assertEquals(QuietTimes.NO_NAME, QuietTimes.validate(listOf(school.copy(name = "   "))))
        assertEquals(QuietTimes.LONG_NAME, QuietTimes.validate(listOf(school.copy(name = "x".repeat(41)))))
        assertNull(QuietTimes.validate(listOf(school.copy(name = " " + "x".repeat(40) + " "))))
        assertEquals(QuietTimes.SAME_NAME, QuietTimes.validate(listOf(school, school.copy(name = "school DAYS ", days = Occurrences.WEEKENDS))))
        assertEquals(QuietTimes.NO_DAY, QuietTimes.validate(listOf(school.copy(days = 0))))
        assertEquals(QuietTimes.NO_DAY, QuietTimes.validate(listOf(school.copy(days = 128))))
        assertEquals(QuietTimes.NO_BLOCK, QuietTimes.validate(listOf(school.copy(blocks = emptyList()))))
        val sixteen = (0 until 16).map { QuietBlock(it * 60, it * 60 + 30) }
        assertNull(QuietTimes.validate(listOf(school.copy(blocks = sixteen))))
        assertEquals(QuietTimes.TOO_MANY_BLOCKS, QuietTimes.validate(listOf(school.copy(blocks = sixteen + QuietBlock(1_400, 1_410)))))
        assertEquals(QuietTimes.SAME_TIMES, QuietTimes.validate(listOf(school.copy(blocks = listOf(block("08:40", "08:40"))))))
        assertEquals(QuietTimes.NOT_A_TIME, QuietTimes.validate(listOf(school.copy(blocks = listOf(QuietBlock(0, 1_440))))))
        assertEquals("8:40–9:30 and 9:00–9:50 overlap.", QuietTimes.validate(listOf(school.copy(blocks = listOf(block("09:00", "09:50"), block("08:40", "09:30"))))))
        assertEquals("one ending as the next starts meets nothing", null, QuietTimes.validate(listOf(school.copy(blocks = listOf(block("08:40", "09:30"), block("09:30", "10:20"))))))
        assertEquals("the same block twice", "8:40–9:30 and 8:40–9:30 overlap.", QuietTimes.validate(listOf(school.copy(blocks = listOf(block("08:40", "09:30"), block("08:40", "09:30"))))))
        // A block crossing midnight: into the next morning's on weekdays, not on a Monday alone (Tuesday is not its day).
        val night = listOf(block("21:00", "07:00"), block("06:00", "08:00"))
        assertEquals("6:00–8:00 and 21:00–7:00 overlap.", QuietTimes.validate(listOf(QuietSection("Night", Occurrences.WEEKDAYS, night))))
        assertNull(QuietTimes.validate(listOf(QuietSection("Night", monday, night))))
        // Sunday night's runs into Monday's.
        assertEquals("1:00–3:00 and 22:00–2:00 overlap.", QuietTimes.validate(listOf(QuietSection("Late", sunday or monday, listOf(block("22:00", "02:00"), block("01:00", "03:00"))))))
        assertNull("sections may overlap each other", QuietTimes.validate(listOf(school, QuietSection("Assembly", monday, listOf(block("09:00", "09:20"))))))
    }

    @Test
    fun `the quiet at a moment, across midnight, and the next block`() {
        val rows = listOf(quiet(1, "Night", monday, "21:00", "07:00"), quiet(2, "School days", Occurrences.WEEKDAYS, "08:40", "09:30"))
        assertNull(QuietTimes.at(rows, at("2026-10-05", "20:59")))
        assertEquals(QuietSpell(at("2026-10-05", "21:00"), at("2026-10-06", "07:00")), QuietTimes.at(rows, at("2026-10-05", "21:00")))
        assertEquals("Monday night runs into Tuesday", QuietSpell(at("2026-10-05", "21:00"), at("2026-10-06", "07:00")), QuietTimes.at(rows, at("2026-10-06", "06:59")))
        assertNull("its end is no longer quiet", QuietTimes.at(rows, at("2026-10-06", "07:00")))
        assertNull("Tuesday night is not its day", QuietTimes.at(rows, at("2026-10-06", "23:00")))
        assertEquals(at("2026-10-05", "21:00"), QuietTimes.next(rows, at("2026-10-05", "09:30")))
        assertEquals("strictly after", at("2026-10-06", "08:40"), QuietTimes.next(rows, at("2026-10-05", "21:00")))
        assertEquals("Friday's last, then Monday's", at("2026-10-12", "08:40"), QuietTimes.next(rows, at("2026-10-09", "09:00")))
        assertNull(QuietTimes.next(emptyList(), at("2026-10-05", "09:00")))
        assertNull("a timed play's row is no quiet", QuietTimes.at(listOf(rows[1].copy(kind = ScheduleKind.PLAYLIST)), at("2026-10-05", "09:00")))
    }

    @Test
    fun `the quiet runs on through blocks that touch or overlap it, of any section, and the last block begun is its since`() {
        val rows = listOf(
            quiet(1, "Periods", monday, "08:40", "09:30"),
            quiet(2, "Periods", monday, "09:30", "10:20"),
            quiet(3, "Exams", monday, "10:00", "11:00"),
        )
        assertEquals(QuietSpell(at("2026-10-05", "08:40"), at("2026-10-05", "11:00")), QuietTimes.at(rows, at("2026-10-05", "09:00")))
        assertEquals(QuietSpell(at("2026-10-05", "09:30"), at("2026-10-05", "11:00")), QuietTimes.at(rows, at("2026-10-05", "09:45")))
        assertEquals(QuietSpell(at("2026-10-05", "10:00"), at("2026-10-05", "11:00")), QuietTimes.at(rows, at("2026-10-05", "10:30")))
    }

    @Test
    fun `Add block proposes ten minutes after the last block's end, as long as it`() {
        assertEquals(block("09:40", "10:30"), QuietTimes.proposed(listOf(block("08:40", "09:30")), start = 0))
        assertEquals("after a block crossing midnight", block("00:30", "01:20"), QuietTimes.proposed(listOf(block("23:30", "00:20")), start = 0))
        assertEquals("the last as listed", block("12:10", "12:40"), QuietTimes.proposed(listOf(block("13:00", "14:00"), block("11:30", "12:00")), start = 0))
        assertEquals("none yet: from the start given", block("10:00", "10:50"), QuietTimes.proposed(emptyList(), start = hm("10:00")))
    }

    @Test
    fun `the words, on the tablet's 24-hour clock`() {
        val nine = at("2026-10-05", "09:00")
        assertEquals("Quiet now · until 9:30", QuietCopy.status(QuietNow(now = true, until = ms(at("2026-10-05", "09:30"))), nine))
        assertEquals("Lifted until 9:30", QuietCopy.status(QuietNow(now = true, until = ms(at("2026-10-05", "09:30")), overridden = true), nine))
        assertEquals("Next quiet time 13:05", QuietCopy.status(QuietNow(next = ms(at("2026-10-05", "13:05"))), nine))
        assertEquals("Next quiet time Tue 8:40", QuietCopy.status(QuietNow(next = ms(at("2026-10-06", "08:40"))), nine))
        assertEquals("No quiet times set", QuietCopy.status(QuietNow(), nine))
        assertEquals("a day or more away: its weekday", "Quiet until Wed 9:30", QuietCopy.hub(QuietNow(now = true, until = ms(at("2026-10-07", "09:30"))), nine))
        assertEquals("Quiet until 9:30. Use Play anyway.", QuietCopy.refusal("9:30"))
        assertEquals("8:40–9:30", QuietCopy.range(block("08:40", "09:30")))
    }

    @Test
    fun `the gate stops the player once as a block begins, Play anyway lifts it until the block ends, and a new block is quiet again`() = runTest {
        var clock = ms(at("2026-10-05", "08:30"))
        val rows = MutableStateFlow<List<ScheduleEntity>?>(null)
        val gate = QuietGate(rows, clock = { clock }, zone = { zone })
        var hushes = 0
        gate.start(backgroundScope) { hushes++ }
        rows.value = listOf(quiet(1, "School days", Occurrences.WEEKDAYS, "08:40", "09:30"), quiet(2, "School days", Occurrences.WEEKDAYS, "09:40", "10:30"))
        runCurrent()
        assertEquals(QuietNow(next = ms(at("2026-10-05", "08:40"))), gate.state.value)
        assertFalse(gate.override())
        assertEquals(0, hushes)

        clock = ms(at("2026-10-05", "08:41"))
        assertTrue("a play is weighed at the moment it is asked", gate.holds())
        gate.check()
        gate.check()
        assertEquals("the player stops once a block", 1, hushes)
        assertEquals(QuietNow(now = true, until = ms(at("2026-10-05", "09:30")), next = ms(at("2026-10-05", "09:40"))), gate.state.value)

        assertTrue(gate.override())
        assertTrue(gate.state.value.overridden)
        clock = ms(at("2026-10-05", "09:10"))
        assertFalse("lifted until the block ends", gate.holds())
        clock = ms(at("2026-10-05", "09:31"))
        gate.check()
        assertEquals(QuietNow(next = ms(at("2026-10-05", "09:40"))), gate.state.value)
        clock = ms(at("2026-10-05", "09:41"))
        gate.check()
        assertTrue("a new block is quiet again", gate.state.value.holds)
        assertEquals(2, hushes)
        assertTrue(gate.override())
        assertFalse(gate.holds())
    }

    @Test
    fun `a lift holds until a block of another section begins, and the app starting inside a block stops the player`() = runTest {
        var clock = ms(at("2026-10-05", "08:30"))
        val rows = MutableStateFlow<List<ScheduleEntity>?>(listOf(quiet(1, "Mornings", monday, "08:00", "10:00"), quiet(2, "Exams", monday, "09:00", "11:00")))
        val gate = QuietGate(rows, clock = { clock }, zone = { zone })
        var hushes = 0
        gate.start(backgroundScope) { hushes++ }
        runCurrent()
        assertEquals("started inside a block: the player stops", 1, hushes)
        assertEquals(ms(at("2026-10-05", "11:00")), gate.state.value.until)
        assertTrue(gate.override())
        clock = ms(at("2026-10-05", "08:59"))
        assertFalse(gate.holds())
        clock = ms(at("2026-10-05", "09:01"))
        assertTrue("Exams began after the lift", gate.holds())
        gate.check()
        assertEquals(2, hushes)
    }

    @Test
    fun `the repository replaces every quiet block at once and keeps the timed plays' rows`() = runBlocking {
        val timed = ScheduleEntity(1, Occurrences.WEEKDAYS, 750, ScheduleKind.CHANNEL, "calm", 795, 70, true, createdAt = 0)
        val dao = FakeScheduleDao(listOf(timed, quiet(2, "Old", monday, "08:00", "09:00")))
        var transactions = 0
        val repository = ScheduleRepository(dao, clock = { 7L }) { block ->
            transactions++
            block()
        }
        val school = QuietSection("School days", Occurrences.WEEKDAYS, listOf(block("08:40", "09:30"), block("09:40", "10:30")))
        repository.replaceQuiet(listOf(school))
        assertEquals(1, transactions)
        assertEquals(listOf(school), repository.sections())
        assertEquals("the timed play's row stays, never read", timed, dao.snapshot.first())
        assertEquals(3, dao.snapshot.size)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.replaceQuiet(listOf(school.copy(days = 0))) } }
        repository.replaceQuiet(emptyList())
        assertEquals(listOf(timed), dao.snapshot)
    }
}
