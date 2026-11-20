package com.github.vgerya.gitlabpipelines.core

import java.time.Duration
import java.time.Instant

data class Counts(
    val failed: Int,
    val running: Int,
    val pending: Int,
    val success: Int,
    val other: Int,
    val none: Int,
    val total: Int,
) {
    companion object {
        val EMPTY = Counts(0, 0, 0, 0, 0, 0, 0)
    }
}

enum class WidgetKind { ERROR, FAILED, RUNNING, OK, EMPTY }

data class WidgetState(val kind: WidgetKind, val number: Int = 0)

object Summary {

    /** Removes drafts unless [showDrafts]. */
    fun filter(mrs: List<MergeRequest>, showDrafts: Boolean): List<MergeRequest> =
        if (showDrafts) mrs else mrs.filter { !it.draft }

    /** By category order, then by `pipeline.updatedAt ?: updatedAt` descending (nulls last). */
    fun sort(mrs: List<MergeRequest>): List<MergeRequest> = mrs.sortedWith(COMPARATOR)

    val COMPARATOR: Comparator<MergeRequest> = Comparator { a, b ->
        val byCategory = a.category.order.compareTo(b.category.order)
        if (byCategory != 0) return@Comparator byCategory
        val ta = a.pipeline?.updatedAt ?: a.updatedAt
        val tb = b.pipeline?.updatedAt ?: b.updatedAt
        val byTime = when {
            ta == null && tb == null -> 0
            ta == null -> 1
            tb == null -> -1
            else -> tb.compareTo(ta)
        }
        if (byTime != 0) byTime else a.key.compareTo(b.key)
    }

    fun compute(mrs: List<MergeRequest>): Counts {
        var failed = 0
        var running = 0
        var pending = 0
        var success = 0
        var other = 0
        var none = 0
        for (mr in mrs) {
            when (mr.category) {
                Category.FAILED -> failed++
                Category.RUNNING -> running++
                Category.PENDING -> pending++
                Category.SUCCESS -> success++
                Category.OTHER -> other++
                Category.NONE -> none++
            }
        }
        return Counts(failed, running, pending, success, other, none, mrs.size)
    }

    /**
     * AUTH/FORBIDDEN/NOT_CONFIGURED → ERROR; otherwise counts decide (transient errors keep the
     * counts-based state computed from cached data).
     */
    fun widgetState(counts: Counts, error: ErrorKind?): WidgetState = when {
        error == ErrorKind.AUTH || error == ErrorKind.FORBIDDEN || error == ErrorKind.NOT_CONFIGURED ->
            WidgetState(WidgetKind.ERROR)
        counts.failed > 0 -> WidgetState(WidgetKind.FAILED, counts.failed)
        counts.running + counts.pending > 0 -> WidgetState(WidgetKind.RUNNING, counts.running + counts.pending)
        counts.success > 0 -> WidgetState(WidgetKind.OK)
        else -> WidgetState(WidgetKind.EMPTY)
    }
}

/** Relative-time bucket for the "Updated" column; the UI maps it to localized text. */
sealed class TimeAgo {
    object JustNow : TimeAgo()
    data class Minutes(val n: Long) : TimeAgo()
    data class Hours(val n: Long) : TimeAgo()
    data class Days(val n: Long) : TimeAgo()
    data class Absolute(val instant: Instant) : TimeAgo()

    companion object {
        /** < 1 min → just now; < 1 h → minutes; < 24 h → hours; < 7 d → days; else absolute date. */
        fun of(then: Instant, now: Instant): TimeAgo {
            val d = Duration.between(then, now)
            if (d.isNegative || d.toMinutes() < 1) return JustNow
            return when {
                d.toMinutes() < 60 -> Minutes(d.toMinutes())
                d.toHours() < 24 -> Hours(d.toHours())
                d.toDays() < 7 -> Days(d.toDays())
                else -> Absolute(then)
            }
        }
    }
}
