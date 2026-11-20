package com.github.vgerya.gitlabpipelines.core

import java.time.Instant

/** What we last saw for an MR. */
data class Seen(val pipelineId: String?, val status: PipelineStatus?)

data class NotifyPrefs(val failed: Boolean, val success: Boolean, val canceled: Boolean) {
    /** FAILED → failed, SUCCESS → success, CANCELED/SKIPPED/MANUAL → canceled; others never. */
    fun allows(status: PipelineStatus): Boolean = when (status) {
        PipelineStatus.FAILED -> failed
        PipelineStatus.SUCCESS -> success
        PipelineStatus.CANCELED, PipelineStatus.SKIPPED, PipelineStatus.MANUAL -> canceled
        else -> false
    }

    companion object {
        val ALL = NotifyPrefs(failed = true, success = true, canceled = true)
    }
}

data class PipelineEvent(val mr: MergeRequest, val status: PipelineStatus, val url: String)

data class DiffResult(val next: Map<String, Seen>, val events: List<PipelineEvent>)

object StatusDiff {

    /**
     * Compares the previous state with the current MR list.
     *
     * - `prev == null` → baseline, no events;
     * - `next` contains only current MRs;
     * - an event is emitted for a terminal, enabled status if the pipeline id or status changed, or
     *   for an MR first seen after the baseline whose pipeline finished after [lastPollAt].
     */
    fun diff(
        prev: Map<String, Seen>?,
        current: List<MergeRequest>,
        prefs: NotifyPrefs,
        lastPollAt: Instant?,
    ): DiffResult {
        val next = LinkedHashMap<String, Seen>()
        val events = ArrayList<PipelineEvent>()
        for (mr in current) {
            val pipeline = mr.pipeline
            next[mr.key] = Seen(pipeline?.id, pipeline?.status)
            if (prev == null || pipeline == null) continue
            val status = pipeline.status
            if (!status.terminal || !prefs.allows(status)) continue
            val old = prev[mr.key]
            val changed = if (old != null) {
                old.pipelineId != pipeline.id || old.status != status
            } else {
                val finished = pipeline.finishedAt
                finished != null && lastPollAt != null && finished.isAfter(lastPollAt)
            }
            if (changed) events += PipelineEvent(mr, status, pipeline.webUrl ?: mr.webUrl)
        }
        return DiffResult(next, events)
    }
}
