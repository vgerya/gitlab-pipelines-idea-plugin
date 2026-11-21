package com.github.vgerya.gitlabpipelines.core

import java.time.Duration
import java.time.Instant

/** Pipeline timing helpers (pure). */
object PipelineTime {

    /** Placeholder shown when there is no value (e.g. the pipeline has not started yet). */
    const val NONE = "—"

    /** `true` while the pipeline is executing, i.e. its duration grows with the clock. */
    fun isRunning(p: Pipeline): Boolean =
        (p.status == PipelineStatus.RUNNING || p.status == PipelineStatus.CANCELING) &&
            p.startedAt != null && p.finishedAt == null

    /**
     * Duration in seconds: for a running pipeline the time elapsed since [Pipeline.startedAt]; otherwise
     * GitLab's [Pipeline.duration], falling back to `finishedAt - startedAt`. `null` if not started.
     */
    fun durationSeconds(p: Pipeline, now: Instant = Instant.now()): Long? {
        val started = p.startedAt
        if (isRunning(p) && started != null) return Duration.between(started, now).seconds.coerceAtLeast(0)
        p.duration?.let { return it.coerceAtLeast(0) }
        val finished = p.finishedAt
        if (started != null && finished != null) return Duration.between(started, finished).seconds.coerceAtLeast(0)
        return null
    }

    /** `45s`, `4m 12s`, `1h 03m`, `2d 5h`; [NONE] for `null`. */
    fun formatDuration(seconds: Long?): String {
        if (seconds == null || seconds < 0) return NONE
        val d = seconds / 86_400
        val h = (seconds % 86_400) / 3_600
        val m = (seconds % 3_600) / 60
        val s = seconds % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    /** Formatted duration of [p] at [now]. */
    fun durationText(p: Pipeline?, now: Instant = Instant.now()): String =
        formatDuration(p?.let { durationSeconds(it, now) })
}

/** Human-readable pipeline refs (pure). */
object RefDisplay {

    private val MR_REF = Regex("^refs/merge-requests/(\\d+)/(head|merge|train)$")

    /**
     * `refs/merge-requests/881/head` → `MR !881`, `…/merge` → `MR !881 (merged result)`,
     * `…/train` → `MR !881 (merge train)`, `refs/heads/x` → `x`, `refs/tags/v1` → `v1`; anything else as is.
     */
    fun format(ref: String?): String {
        val r = ref?.trim().orEmpty()
        if (r.isEmpty()) return ""
        MR_REF.matchEntire(r)?.let { m ->
            val iid = m.groupValues[1]
            return when (m.groupValues[2]) {
                "merge" -> "MR !$iid (merged result)"
                "train" -> "MR !$iid (merge train)"
                else -> "MR !$iid"
            }
        }
        if (r.startsWith("refs/heads/")) return r.removePrefix("refs/heads/")
        if (r.startsWith("refs/tags/")) return r.removePrefix("refs/tags/")
        return r
    }
}
