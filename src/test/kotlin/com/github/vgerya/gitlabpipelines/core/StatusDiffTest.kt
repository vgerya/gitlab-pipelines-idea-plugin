package com.github.vgerya.gitlabpipelines.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatusDiffTest {

    private val lastPoll = ts("2024-05-01T10:00:00Z")
    private val all = NotifyPrefs.ALL

    private fun seenOf(vararg mrs: MergeRequest): Map<String, Seen> =
        mrs.associate { it.key to Seen(it.pipeline?.id, it.pipeline?.status) }

    @Test
    fun baselineProducesNoEvents() {
        val current = listOf(
            mr(1, pipeline(10, PipelineStatus.FAILED, ts("2024-05-01T10:30:00Z"))),
            mr(2, pipeline(20, PipelineStatus.SUCCESS, ts("2024-05-01T10:30:00Z"))),
        )
        val result = StatusDiff.diff(null, current, all, lastPoll)
        assertTrue(result.events.isEmpty())
        assertEquals(seenOf(*current.toTypedArray()), result.next)
    }

    @Test
    fun runningToFailedEmitsOneEvent() {
        val before = mr(1, pipeline(10, PipelineStatus.RUNNING))
        val after = mr(1, pipeline(10, PipelineStatus.FAILED, ts("2024-05-01T10:01:00Z")))
        val result = StatusDiff.diff(seenOf(before), listOf(after), all, lastPoll)
        assertEquals(1, result.events.size)
        val event = result.events.single()
        assertEquals(PipelineStatus.FAILED, event.status)
        assertEquals(after, event.mr)
        assertEquals("$BASE/g/p/-/pipelines/10", event.url)
        assertEquals(Seen(GlobalIds.pipeline(10), PipelineStatus.FAILED), result.next[after.key])
    }

    @Test
    fun unchangedFailedProducesNoRepeat() {
        val failed = mr(1, pipeline(10, PipelineStatus.FAILED, ts("2024-05-01T10:01:00Z")))
        val first = StatusDiff.diff(seenOf(mr(1, pipeline(10, PipelineStatus.RUNNING))), listOf(failed), all, lastPoll)
        assertEquals(1, first.events.size)
        val second = StatusDiff.diff(first.next, listOf(failed), all, ts("2024-05-01T10:02:00Z"))
        assertTrue(second.events.isEmpty())
        val third = StatusDiff.diff(second.next, listOf(failed), all, ts("2024-05-01T10:03:00Z"))
        assertTrue(third.events.isEmpty())
    }

    @Test
    fun successThenNewFailedPipelineEmits() {
        val prev = seenOf(mr(1, pipeline(10, PipelineStatus.SUCCESS)))
        val result = StatusDiff.diff(prev, listOf(mr(1, pipeline(11, PipelineStatus.FAILED))), all, lastPoll)
        assertEquals(listOf(PipelineStatus.FAILED), result.events.map { it.status })
    }

    @Test
    fun sameStatusNewPipelineIdEmits() {
        val prev = seenOf(mr(1, pipeline(10, PipelineStatus.FAILED)))
        val result = StatusDiff.diff(prev, listOf(mr(1, pipeline(11, PipelineStatus.FAILED))), all, lastPoll)
        assertEquals(1, result.events.size)
    }

    @Test
    fun nonTerminalStatusesNeverEmit() {
        val prev = seenOf(mr(1, pipeline(10, PipelineStatus.SUCCESS)))
        val nonTerminal = PipelineStatus.values().filter { !it.terminal }
        for (s in nonTerminal) {
            val result = StatusDiff.diff(prev, listOf(mr(1, pipeline(11, s))), all, lastPoll)
            assertTrue(result.events.isEmpty(), "status $s must not notify")
        }
    }

    @Test
    fun newMrAfterBaselineFinishedAfterLastPollEmits() {
        val prev = seenOf(mr(1))
        val newMr = mr(2, pipeline(20, PipelineStatus.SUCCESS, ts("2024-05-01T10:00:30Z")))
        val result = StatusDiff.diff(prev, listOf(mr(1), newMr), all, lastPoll)
        assertEquals(listOf(newMr.key), result.events.map { it.mr.key })
    }

    @Test
    fun newMrAfterBaselineFinishedBeforeLastPollIsSilent() {
        val prev = seenOf(mr(1))
        val old = mr(2, pipeline(20, PipelineStatus.FAILED, ts("2024-05-01T09:00:00Z")))
        val noFinish = mr(3, pipeline(30, PipelineStatus.MANUAL, finishedAt = null))
        val result = StatusDiff.diff(prev, listOf(old, noFinish), all, lastPoll)
        assertTrue(result.events.isEmpty())
        assertEquals(setOf(old.key, noFinish.key), result.next.keys)
    }

    @Test
    fun prefsToggles() {
        val prev = seenOf(
            mr(1, pipeline(10, PipelineStatus.RUNNING)),
            mr(2, pipeline(20, PipelineStatus.RUNNING)),
            mr(3, pipeline(30, PipelineStatus.RUNNING)),
            mr(4, pipeline(40, PipelineStatus.RUNNING)),
            mr(5, pipeline(50, PipelineStatus.RUNNING)),
        )
        val current = listOf(
            mr(1, pipeline(10, PipelineStatus.FAILED)),
            mr(2, pipeline(20, PipelineStatus.SUCCESS)),
            mr(3, pipeline(30, PipelineStatus.CANCELED)),
            mr(4, pipeline(40, PipelineStatus.SKIPPED)),
            mr(5, pipeline(50, PipelineStatus.MANUAL)),
        )
        fun statuses(p: NotifyPrefs) = StatusDiff.diff(prev, current, p, lastPoll).events.map { it.status }.toSet()

        assertEquals(PipelineStatus.values().filter { it.terminal }.toSet(), statuses(all))
        assertEquals(setOf(PipelineStatus.FAILED), statuses(NotifyPrefs(failed = true, success = false, canceled = false)))
        assertEquals(setOf(PipelineStatus.SUCCESS), statuses(NotifyPrefs(failed = false, success = true, canceled = false)))
        assertEquals(
            setOf(PipelineStatus.CANCELED, PipelineStatus.SKIPPED, PipelineStatus.MANUAL),
            statuses(NotifyPrefs(failed = false, success = false, canceled = true)),
        )
        assertTrue(statuses(NotifyPrefs(failed = false, success = false, canceled = false)).isEmpty())
    }

    @Test
    fun disabledEventStillUpdatesSeenSoItIsNotRepeatedLater() {
        val prev = seenOf(mr(1, pipeline(10, PipelineStatus.RUNNING)))
        val failed = mr(1, pipeline(10, PipelineStatus.FAILED))
        val off = NotifyPrefs(failed = false, success = true, canceled = true)
        val first = StatusDiff.diff(prev, listOf(failed), off, lastPoll)
        assertTrue(first.events.isEmpty())
        assertTrue(StatusDiff.diff(first.next, listOf(failed), all, lastPoll).events.isEmpty())
    }

    @Test
    fun removedMrIsDroppedFromNext() {
        val prev = seenOf(mr(1, pipeline(10, PipelineStatus.FAILED)), mr(2, pipeline(20, PipelineStatus.RUNNING)))
        val result = StatusDiff.diff(prev, listOf(mr(2, pipeline(20, PipelineStatus.RUNNING))), all, lastPoll)
        assertEquals(setOf(GlobalIds.mergeRequest(2)), result.next.keys)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun mrWithoutPipeline() {
        val prev = seenOf(mr(1, pipeline(10, PipelineStatus.FAILED)))
        val result = StatusDiff.diff(prev, listOf(mr(1)), all, lastPoll)
        assertTrue(result.events.isEmpty())
        assertEquals(Seen(null, null), result.next[GlobalIds.mergeRequest(1)])
        // pipeline appears later and is already failed → event
        val later = StatusDiff.diff(result.next, listOf(mr(1, pipeline(11, PipelineStatus.FAILED))), all, lastPoll)
        assertEquals(1, later.events.size)
    }

    @Test
    fun eventUrlFallsBackToMrUrl() {
        val prev = seenOf(mr(1, pipeline(10, PipelineStatus.RUNNING)))
        val current = mr(1, pipeline(10, PipelineStatus.FAILED, webUrl = null))
        val event = StatusDiff.diff(prev, listOf(current), all, lastPoll).events.single()
        assertEquals(current.webUrl, event.url)
    }
}
