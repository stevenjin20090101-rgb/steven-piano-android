// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/** What a schedule may be, as the editor and the web panel check it, and how the repository keeps them. */
class ScheduleDraftTest {
    private val calm = ScheduleDraft(days = 31, startMinute = 750, kind = ScheduleKind.CHANNEL, target = "calm", endMinute = 795, volumePct = 70)

    @Test
    fun `a draft says what keeps it from being saved`() {
        assertNull(calm.problem)
        assertEquals(ScheduleRules.NO_DAY, calm.copy(days = 0).problem)
        assertEquals(ScheduleRules.NO_DAY, calm.copy(days = 128).problem)
        assertEquals(ScheduleRules.SAME_TIMES, calm.copy(endMinute = 750).problem)
        assertEquals("The start must be a time of day.", calm.copy(startMinute = 1_440).problem)
        assertEquals("The end must be a time of day.", calm.copy(endMinute = -1).problem)
        assertEquals(ScheduleRules.NO_TARGET, calm.copy(kind = null).problem)
        assertEquals(ScheduleRules.NO_TARGET, calm.copy(target = null).problem)
        assertEquals("That isn't a channel.", calm.copy(target = "Calm Channel").problem)
        assertEquals("That isn't a playlist or a piece.", calm.copy(kind = ScheduleKind.PLAYLIST, target = "calm").problem)
        assertEquals("That isn't a playlist or a piece.", calm.copy(kind = ScheduleKind.PIECE, target = "0").problem)
        assertNull(calm.copy(kind = ScheduleKind.PIECE, target = "42").problem)
        assertEquals("The volume runs from 0 to 100%.", calm.copy(volumePct = 101).problem)
        assertNull("no volume: the piano as it is", calm.copy(volumePct = null).problem)
        assertNull("until the end", calm.copy(endMinute = null).problem)
        assertNull("past midnight", calm.copy(startMinute = 1_410, endMinute = 30).problem)
    }

    @Test
    fun `a new draft starts on weekdays at the next whole hour, for an hour`() {
        val fresh = ScheduleDraft.fresh(LocalTime.of(9, 41))
        assertEquals(31, fresh.days)
        assertEquals(600, fresh.startMinute)
        assertEquals(660, fresh.endMinute)
        assertEquals(70, fresh.volumePct)
        assertEquals(ScheduleRules.NO_TARGET, fresh.problem)
        val late = ScheduleDraft.fresh(LocalTime.of(23, 10), ScheduleKind.CHANNEL, "calm", 55)
        assertEquals("midnight", 0, late.startMinute)
        assertEquals(60, late.endMinute)
        assertNull(late.problem)
        assertEquals(55, late.volumePct)
        assertEquals(31 xor 4, fresh.toggle(DayOfWeek.WEDNESDAY).days)
        assertEquals(31, fresh.toggle(DayOfWeek.WEDNESDAY).toggle(DayOfWeek.WEDNESDAY).days)
    }

    @Test
    fun `the repository keeps what was made when, refuses a fiftieth-and-first, and knows a schedule gone`() = runBlocking {
        val dao = FakeScheduleDao()
        var now = 1_000L
        val repository = ScheduleRepository(dao) { now }
        val saved = repository.save(calm) as SaveResult.Saved
        assertEquals(1L, saved.schedule.id)
        assertEquals(1_000L, saved.schedule.createdAt)
        now = 2_000L
        val edited = repository.save(ScheduleDraft.of(saved.schedule).copy(startMinute = 760)) as SaveResult.Saved
        assertEquals(1L, edited.schedule.id)
        assertEquals("made when it was first saved", 1_000L, edited.schedule.createdAt)
        assertEquals(760, repository.get(1)!!.startMinute)
        repository.setEnabled(1, false)
        assertEquals(false, repository.list().single().enabled)
        repository.delete(1)
        assertEquals(SaveResult.Gone, repository.save(ScheduleDraft.of(edited.schedule)))
        repeat(ScheduleRules.MAX_SCHEDULES) { assertTrue(repository.save(calm) is SaveResult.Saved) }
        assertEquals(SaveResult.TooMany, repository.save(calm))
        assertEquals(ScheduleRules.MAX_SCHEDULES, repository.list().size)
    }
}
