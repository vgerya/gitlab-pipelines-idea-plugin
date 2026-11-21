package com.github.vgerya.gitlabpipelines.core

import java.time.Instant

/** Display/sort group of a pipeline status. Lower [order] is shown first. */
enum class Category(val order: Int) {
    FAILED(0), RUNNING(1), PENDING(2), SUCCESS(3), OTHER(4), NONE(5)
}

/** GitLab `PipelineStatusEnum` (+ [UNKNOWN] for anything we do not recognise). */
enum class PipelineStatus(val category: Category, val terminal: Boolean) {
    CREATED(Category.PENDING, false),
    WAITING_FOR_RESOURCE(Category.PENDING, false),
    PREPARING(Category.PENDING, false),
    WAITING_FOR_CALLBACK(Category.PENDING, false),
    PENDING(Category.PENDING, false),
    SCHEDULED(Category.PENDING, false),
    RUNNING(Category.RUNNING, false),
    CANCELING(Category.RUNNING, false),
    SUCCESS(Category.SUCCESS, true),
    FAILED(Category.FAILED, true),
    CANCELED(Category.OTHER, true),
    SKIPPED(Category.OTHER, true),
    MANUAL(Category.OTHER, true),
    UNKNOWN(Category.OTHER, false);

    companion object {
        /** Case-insensitive; `null`, blank or unrecognised values map to [UNKNOWN]. */
        fun parse(s: String?): PipelineStatus {
            val normalized = s?.trim()?.uppercase() ?: return UNKNOWN
            if (normalized.isEmpty()) return UNKNOWN
            return values().firstOrNull { it != UNKNOWN && it.name == normalized } ?: UNKNOWN
        }
    }
}

enum class Role { AUTHOR, ASSIGNEE, REVIEWER }

data class Pipeline(
    val id: String,
    val iid: String?,
    val status: PipelineStatus,
    val rawStatus: String,
    val label: String?,
    val webUrl: String?,
    val updatedAt: Instant?,
    val finishedAt: Instant?,
    /** Only filled for the "recent pipelines" list (not for head pipelines). */
    val ref: String? = null,
    val sha: String? = null,
    val createdAt: Instant? = null,
    /** When the pipeline started running (`null` while it has not started). */
    val startedAt: Instant? = null,
    /** Run time in seconds as reported by GitLab (excludes queue time); `null` until finished. */
    val duration: Long? = null,
)

data class MergeRequest(
    val key: String,
    val iid: Int,
    val title: String,
    val webUrl: String,
    val draft: Boolean,
    val sourceBranch: String,
    val projectPath: String,
    val updatedAt: Instant?,
    val roles: Set<Role>,
    val pipeline: Pipeline?,
) {
    val category: Category get() = pipeline?.status?.category ?: Category.NONE
}

data class User(val id: Long, val username: String, val name: String)

/** Global-id helpers so that GraphQL and REST produce identical keys. */
object GlobalIds {
    const val MERGE_REQUEST_PREFIX = "gid://gitlab/MergeRequest/"
    const val PIPELINE_PREFIX = "gid://gitlab/Ci::Pipeline/"

    fun mergeRequest(id: Long): String = MERGE_REQUEST_PREFIX + id
    fun pipeline(id: Long): String = PIPELINE_PREFIX + id

    /** Numeric tail of a global id (`gid://gitlab/Ci::Pipeline/42` → 42), or `null`. */
    fun numericId(gid: String?): Long? = gid?.substringAfterLast('/')?.trim()?.toLongOrNull()
}
