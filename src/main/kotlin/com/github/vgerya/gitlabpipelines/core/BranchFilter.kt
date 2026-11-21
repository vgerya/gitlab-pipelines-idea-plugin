package com.github.vgerya.gitlabpipelines.core

/** Merge request filter of the tool window. */
enum class FilterMode {
    /** All merge requests. */
    ALL,

    /** MRs of the GitLab projects the IDE project's git remotes point to. */
    CURRENT_PROJECT,

    /** As [CURRENT_PROJECT], and the MR source branch is checked out in a matching repository. */
    CURRENT_BRANCH;

    companion object {
        fun parse(s: String?): FilterMode? = s?.trim()?.uppercase()?.let { v -> values().firstOrNull { it.name == v } }

        /** Saved mode, or the 1.1 boolean "only current project" migrated to [CURRENT_PROJECT]. */
        fun resolve(saved: String?, legacyOnlyCurrentProject: Boolean): FilterMode =
            parse(saved) ?: if (legacyOnlyCurrentProject) CURRENT_PROJECT else ALL
    }
}

/** A local git repository: its remote URLs and checked-out branch (`null` = detached HEAD / unknown). */
data class LocalRepo(val remoteUrls: List<String>, val currentBranch: String?)

/** A local repository resolved against the configured GitLab: lower-cased project paths + current branch. */
data class RepoState(val projectPaths: Set<String>, val currentBranch: String?)

/** Pure filtering of merge requests by the IDE project's repositories. */
object BranchFilter {

    private const val HEAD_REF_PREFIX = "ref: refs/heads/"

    /** Branch name from the content of `.git/HEAD` (`ref: refs/heads/<name>`); `null` for a detached HEAD. */
    fun parseHead(text: String?): String? {
        val line = text?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        if (!line.startsWith(HEAD_REF_PREFIX)) return null
        return line.removePrefix(HEAD_REF_PREFIX).trim().takeIf { it.isNotEmpty() }
    }

    /** Resolves [repos] against [baseUrl]; repositories without a remote on that GitLab are dropped. */
    fun resolve(repos: Collection<LocalRepo>, baseUrl: String?): List<RepoState> = repos.mapNotNull { repo ->
        val paths = GitRemotes.projectPaths(repo.remoteUrls, baseUrl)
        if (paths.isEmpty()) null else RepoState(paths, repo.currentBranch?.takeIf { it.isNotBlank() })
    }

    /** All project paths of [repos]. */
    fun projectPaths(repos: Collection<RepoState>): Set<String> = repos.flatMapTo(LinkedHashSet()) { it.projectPaths }

    /** Distinct checked-out branches of [repos] (detached repositories skipped). */
    fun currentBranches(repos: Collection<RepoState>): List<String> = repos.mapNotNull { it.currentBranch }.distinct()

    fun matchesProject(mr: MergeRequest, repos: Collection<RepoState>): Boolean {
        val path = mr.projectPath.lowercase()
        return repos.any { path in it.projectPaths }
    }

    /**
     * Some repository has a remote for the MR's project AND has the MR source branch checked out
     * (branch names are compared exactly, as git does).
     */
    fun matchesBranch(mr: MergeRequest, repos: Collection<RepoState>): Boolean {
        val path = mr.projectPath.lowercase()
        return repos.any { it.currentBranch != null && it.currentBranch == mr.sourceBranch && path in it.projectPaths }
    }

    fun filter(mrs: List<MergeRequest>, mode: FilterMode, repos: Collection<RepoState>): List<MergeRequest> = when (mode) {
        FilterMode.ALL -> mrs
        FilterMode.CURRENT_PROJECT -> mrs.filter { matchesProject(it, repos) }
        FilterMode.CURRENT_BRANCH -> mrs.filter { matchesBranch(it, repos) }
    }
}
