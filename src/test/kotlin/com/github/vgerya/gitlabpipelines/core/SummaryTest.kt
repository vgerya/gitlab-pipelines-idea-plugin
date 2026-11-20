package com.github.vgerya.gitlabpipelines.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SummaryTest {

    // ------------------------------------------------------------ status mapping (§6)

    private val expectedMapping: Map<String, Pair<Category, Boolean>> = mapOf(
        "failed" to (Category.FAILED to true),
        "running" to (Category.RUNNING to false),
        "canceling" to (Category.RUNNING to false),
        "pending" to (Category.PENDING to false),
        "created" to (Category.PENDING to false),
        "waiting_for_resource" to (Category.PENDING to false),
        "waiting_for_callback" to (Category.PENDING to false),
        "preparing" to (Category.PENDING to false),
        "scheduled" to (Category.PENDING to false),
        "success" to (Category.SUCCESS to true),
        "manual" to (Category.OTHER to true),
        "canceled" to (Category.OTHER to true),
        "skipped" to (Category.OTHER to true),
    )

    @Test
    fun allThirteenStatusesParseCaseInsensitively() {
        assertEquals(13, expectedMapping.size)
        for ((raw, expected) in expectedMapping) {
            val lower = PipelineStatus.parse(raw)
            val upper = PipelineStatus.parse(raw.uppercase())
            assertEquals(raw.uppercase(), lower.name, "lower-case $raw")
            assertEquals(lower, upper, "upper-case $raw")
            assertEquals(expected.first, lower.category, "category of $raw")
            assertEquals(expected.second, lower.terminal, "terminal flag of $raw")
        }
    }

    @Test
    fun unknownAndNullStatuses() {
        assertEquals(PipelineStatus.UNKNOWN, PipelineStatus.parse(null))
        assertEquals(PipelineStatus.UNKNOWN, PipelineStatus.parse(""))
        assertEquals(PipelineStatus.UNKNOWN, PipelineStatus.parse("exploded"))
        assertEquals(PipelineStatus.UNKNOWN, PipelineStatus.parse("unknown"))
        assertEquals(Category.OTHER, PipelineStatus.UNKNOWN.category)
        assertFalse(PipelineStatus.UNKNOWN.terminal)
        assertEquals(PipelineStatus.WAITING_FOR_RESOURCE, PipelineStatus.parse(" Waiting_For_Resource "))
    }

    @Test
    fun mergeRequestWithoutPipelineIsNone() {
        assertEquals(Category.NONE, mr(1).category)
        assertEquals(Category.FAILED, mr(1, pipeline(1, PipelineStatus.FAILED)).category)
    }

    // ------------------------------------------------------------ filter / sort

    @Test
    fun draftFilter() {
        val list = listOf(mr(1), mr(2, draft = true), mr(3))
        assertEquals(3, Summary.filter(list, showDrafts = true).size)
        assertEquals(listOf(1, 3), Summary.filter(list, showDrafts = false).map { it.iid })
    }

    @Test
    fun sortByCategoryThenMostRecentFirst() {
        val list = listOf(
            mr(1),                                                                  // NONE
            mr(2, pipeline(2, PipelineStatus.SUCCESS, ts("2024-01-01T10:00:00Z"))),
            mr(3, pipeline(3, PipelineStatus.FAILED, ts("2024-01-01T09:00:00Z"))),
            mr(4, pipeline(4, PipelineStatus.RUNNING, updatedAt = ts("2024-01-01T08:00:00Z"))),
            mr(5, pipeline(5, PipelineStatus.PENDING, updatedAt = ts("2024-01-01T08:00:00Z"))),
            mr(6, pipeline(6, PipelineStatus.CANCELED, ts("2024-01-01T08:00:00Z"))),
            mr(7, pipeline(7, PipelineStatus.FAILED, ts("2024-01-02T09:00:00Z"))),
            mr(8, pipeline(8, PipelineStatus.FAILED, updatedAt = null), updatedAt = ts("2024-01-03T00:00:00Z")),
            mr(9, pipeline(9, PipelineStatus.FAILED, updatedAt = null)),          // no time at all → last in group
        )
        assertEquals(listOf(8, 7, 3, 9, 4, 5, 2, 6, 1), Summary.sort(list).map { it.iid })
    }

    // ------------------------------------------------------------ counts / widget

    @Test
    fun computeCounts() {
        val list = listOf(
            mr(1, pipeline(1, PipelineStatus.FAILED)),
            mr(2, pipeline(2, PipelineStatus.FAILED)),
            mr(3, pipeline(3, PipelineStatus.RUNNING)),
            mr(4, pipeline(4, PipelineStatus.CANCELING)),
            mr(5, pipeline(5, PipelineStatus.PENDING)),
            mr(6, pipeline(6, PipelineStatus.SUCCESS)),
            mr(7, pipeline(7, PipelineStatus.MANUAL)),
            mr(8, pipeline(8, PipelineStatus.UNKNOWN)),
            mr(9),
        )
        assertEquals(Counts(failed = 2, running = 2, pending = 1, success = 1, other = 2, none = 1, total = 9), Summary.compute(list))
        assertEquals(Counts.EMPTY, Summary.compute(emptyList()))
    }

    @Test
    fun widgetStateRules() {
        val c = Counts(failed = 2, running = 1, pending = 2, success = 4, other = 0, none = 0, total = 9)
        assertEquals(WidgetState(WidgetKind.ERROR), Summary.widgetState(c, ErrorKind.AUTH))
        assertEquals(WidgetState(WidgetKind.ERROR), Summary.widgetState(c, ErrorKind.FORBIDDEN))
        assertEquals(WidgetState(WidgetKind.ERROR), Summary.widgetState(Counts.EMPTY, ErrorKind.NOT_CONFIGURED))
        assertEquals(WidgetState(WidgetKind.FAILED, 2), Summary.widgetState(c, null))
        // transient errors keep the counts-based state
        assertEquals(WidgetState(WidgetKind.FAILED, 2), Summary.widgetState(c, ErrorKind.NETWORK))
        assertEquals(WidgetState(WidgetKind.FAILED, 2), Summary.widgetState(c, ErrorKind.RATE_LIMIT))
        assertEquals(WidgetState(WidgetKind.RUNNING, 3), Summary.widgetState(c.copy(failed = 0), null))
        assertEquals(WidgetState(WidgetKind.RUNNING, 3), Summary.widgetState(c.copy(failed = 0, running = 3, pending = 0), ErrorKind.SERVER))
        assertEquals(WidgetState(WidgetKind.OK), Summary.widgetState(c.copy(failed = 0, running = 0, pending = 0), null))
        assertEquals(WidgetState(WidgetKind.EMPTY), Summary.widgetState(Counts(0, 0, 0, 0, 2, 3, 5), null))
        assertEquals(WidgetState(WidgetKind.EMPTY), Summary.widgetState(Counts.EMPTY, null))
    }

    // ------------------------------------------------------------ relative time

    @Test
    fun timeAgoBuckets() {
        val now = ts("2024-05-10T12:00:00Z")
        assertEquals(TimeAgo.JustNow, TimeAgo.of(ts("2024-05-10T11:59:30Z"), now))
        assertEquals(TimeAgo.JustNow, TimeAgo.of(ts("2024-05-10T12:05:00Z"), now)) // clock skew
        assertEquals(TimeAgo.Minutes(5), TimeAgo.of(ts("2024-05-10T11:55:00Z"), now))
        assertEquals(TimeAgo.Hours(3), TimeAgo.of(ts("2024-05-10T09:00:00Z"), now))
        assertEquals(TimeAgo.Days(2), TimeAgo.of(ts("2024-05-08T11:00:00Z"), now))
        assertTrue(TimeAgo.of(ts("2024-04-01T00:00:00Z"), now) is TimeAgo.Absolute)
    }
}
