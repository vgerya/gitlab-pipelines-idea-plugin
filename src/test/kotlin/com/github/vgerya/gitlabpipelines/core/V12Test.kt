package com.github.vgerya.gitlabpipelines.core

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 1.2.0: branch filter, pipeline timing columns, ref display. */
class V12Test {

    private val base = "https://gitlab.example.com"

    private fun m(id: Long, project: String, branch: String, p: Pipeline? = null) =
        mr(id, pipeline = p).copy(projectPath = project, sourceBranch = branch)

    // ---------------------------------------------------------------- filter mode

    @Test
    fun filterModeParseAndMigration() {
        assertEquals(FilterMode.CURRENT_BRANCH, FilterMode.parse(" current_branch "))
        assertNull(FilterMode.parse("nope"))
        assertNull(FilterMode.parse(null))
        assertEquals(FilterMode.ALL, FilterMode.resolve(null, false))
        assertEquals(FilterMode.CURRENT_PROJECT, FilterMode.resolve(null, true))
        assertEquals(FilterMode.CURRENT_PROJECT, FilterMode.resolve("garbage", true))
        assertEquals(FilterMode.ALL, FilterMode.resolve("ALL", true))
        assertEquals(FilterMode.CURRENT_BRANCH, FilterMode.resolve("CURRENT_BRANCH", false))
    }

    // ---------------------------------------------------------------- HEAD parsing

    @Test
    fun parseHead() {
        assertEquals("main", BranchFilter.parseHead("ref: refs/heads/main\n"))
        assertEquals("feature/login-fix", BranchFilter.parseHead("ref: refs/heads/feature/login-fix"))
        assertEquals("main", BranchFilter.parseHead("  ref: refs/heads/main  \r\n"))
        // detached HEAD: a commit sha
        assertNull(BranchFilter.parseHead("3f702f1a0c5e1b2d4e6f8a9b0c1d2e3f4a5b6c7d\n"))
        assertNull(BranchFilter.parseHead("ref: refs/remotes/origin/main"))
        assertNull(BranchFilter.parseHead("ref: refs/heads/"))
        assertNull(BranchFilter.parseHead(""))
        assertNull(BranchFilter.parseHead(null))
    }

    // ---------------------------------------------------------------- branch matching

    private val repoAlpha = LocalRepo(listOf("git@gitlab.example.com:g/alpha.git"), "feature")
    private val repoBeta = LocalRepo(listOf("https://gitlab.example.com/G/Beta.git"), "main")
    private val repoOther = LocalRepo(listOf("git@github.com:g/alpha.git"), "feature")
    private val repoDetached = LocalRepo(listOf("git@gitlab.example.com:g/gamma.git"), null)

    @Test
    fun resolveDropsForeignRepos() {
        val states = BranchFilter.resolve(listOf(repoAlpha, repoBeta, repoOther, repoDetached), base)
        assertEquals(
            listOf(RepoState(setOf("g/alpha"), "feature"), RepoState(setOf("g/beta"), "main"), RepoState(setOf("g/gamma"), null)),
            states,
        )
        assertEquals(setOf("g/alpha", "g/beta", "g/gamma"), BranchFilter.projectPaths(states))
        assertEquals(listOf("feature", "main"), BranchFilter.currentBranches(states))
    }

    @Test
    fun branchMatchIsPerRepository() {
        val states = BranchFilter.resolve(listOf(repoAlpha, repoBeta, repoDetached), base)
        val alphaFeature = m(1, "g/alpha", "feature")
        val alphaMain = m(2, "g/alpha", "main") // "main" is checked out, but in the beta repo
        val betaMain = m(3, "g/beta", "main")
        val betaFeature = m(4, "g/beta", "feature")
        val gammaAny = m(5, "g/gamma", "feature") // detached HEAD never matches a branch
        val foreign = m(6, "x/y", "feature")
        val all = listOf(alphaFeature, alphaMain, betaMain, betaFeature, gammaAny, foreign)

        assertEquals(all, BranchFilter.filter(all, FilterMode.ALL, states))
        assertEquals(
            listOf(alphaFeature, alphaMain, betaMain, betaFeature, gammaAny),
            BranchFilter.filter(all, FilterMode.CURRENT_PROJECT, states),
        )
        assertEquals(listOf(alphaFeature, betaMain), BranchFilter.filter(all, FilterMode.CURRENT_BRANCH, states))
    }

    @Test
    fun branchMatchProjectCaseInsensitiveBranchExact() {
        val states = listOf(RepoState(setOf("g/alpha"), "Feature"))
        assertTrue(BranchFilter.matchesBranch(m(1, "G/Alpha", "Feature"), states))
        assertFalse(BranchFilter.matchesBranch(m(2, "g/alpha", "feature"), states))
        assertTrue(BranchFilter.matchesProject(m(3, "G/ALPHA", "x"), states))
    }

    @Test
    fun twoWorktreesOfSameProjectOnDifferentBranches() {
        val states = BranchFilter.resolve(
            listOf(
                LocalRepo(listOf("git@gitlab.example.com:g/alpha.git"), "feature"),
                LocalRepo(listOf("git@gitlab.example.com:g/alpha.git"), "hotfix"),
            ),
            base,
        )
        val mrs = listOf(m(1, "g/alpha", "feature"), m(2, "g/alpha", "hotfix"), m(3, "g/alpha", "main"))
        assertEquals(mrs.take(2), BranchFilter.filter(mrs, FilterMode.CURRENT_BRANCH, states))
    }

    @Test
    fun noReposMeansEmptyForFilteredModes() {
        val mrs = listOf(m(1, "g/alpha", "feature"))
        assertEquals(emptyList<MergeRequest>(), BranchFilter.filter(mrs, FilterMode.CURRENT_PROJECT, emptyList()))
        assertEquals(emptyList<MergeRequest>(), BranchFilter.filter(mrs, FilterMode.CURRENT_BRANCH, emptyList()))
    }

    // ---------------------------------------------------------------- duration

    @Test
    fun formatDuration() {
        assertEquals("—", PipelineTime.formatDuration(null))
        assertEquals("0s", PipelineTime.formatDuration(0))
        assertEquals("45s", PipelineTime.formatDuration(45))
        assertEquals("1m 0s", PipelineTime.formatDuration(60))
        assertEquals("4m 12s", PipelineTime.formatDuration(252))
        assertEquals("59m 59s", PipelineTime.formatDuration(3599))
        assertEquals("1h 00m", PipelineTime.formatDuration(3600))
        assertEquals("1h 03m", PipelineTime.formatDuration(3600 + 3 * 60 + 9))
        assertEquals("2d 5h", PipelineTime.formatDuration(2 * 86400 + 5 * 3600 + 120))
    }

    private val now = ts("2024-05-01T10:10:00Z")

    private fun timed(
        status: PipelineStatus,
        startedAt: String? = null,
        finishedAt: String? = null,
        duration: Long? = null,
    ) = pipeline(1, status, finishedAt = finishedAt?.let { ts(it) }).copy(startedAt = startedAt?.let { ts(it) }, duration = duration)

    @Test
    fun durationSeconds() {
        // running: elapsed since start, ignoring any partial duration
        val running = timed(PipelineStatus.RUNNING, startedAt = "2024-05-01T10:05:48Z", duration = 3)
        assertTrue(PipelineTime.isRunning(running))
        assertEquals(252L, PipelineTime.durationSeconds(running, now))
        assertEquals("4m 12s", PipelineTime.durationText(running, now))
        assertEquals("4m 13s", PipelineTime.durationText(running, now.plusSeconds(1)))
        // finished: GitLab duration wins
        val done = timed(PipelineStatus.SUCCESS, "2024-05-01T10:00:00Z", "2024-05-01T10:05:00Z", duration = 290)
        assertFalse(PipelineTime.isRunning(done))
        assertEquals(290L, PipelineTime.durationSeconds(done, now))
        // finished without duration: finishedAt - startedAt
        val noDuration = timed(PipelineStatus.FAILED, "2024-05-01T10:00:00Z", "2024-05-01T10:01:30Z")
        assertEquals(90L, PipelineTime.durationSeconds(noDuration, now))
        // not started
        val pending = timed(PipelineStatus.PENDING)
        assertNull(PipelineTime.durationSeconds(pending, now))
        assertEquals("—", PipelineTime.durationText(pending, now))
        assertEquals("—", PipelineTime.durationText(null, now))
        // clock skew: start in the future never gives a negative duration
        val skewed = timed(PipelineStatus.RUNNING, startedAt = "2024-05-01T10:11:00Z")
        assertEquals(0L, PipelineTime.durationSeconds(skewed, now))
    }

    // ---------------------------------------------------------------- sorting

    @Test
    fun sortByTimeColumnsEmptyLast() {
        val early = m(1, "g/p", "a", timed(PipelineStatus.SUCCESS, "2024-05-01T08:00:00Z", "2024-05-01T08:10:00Z", 600))
        val late = m(2, "g/p", "b", timed(PipelineStatus.SUCCESS, "2024-05-01T09:00:00Z", "2024-05-01T09:01:00Z", 60))
        val running = m(3, "g/p", "c", timed(PipelineStatus.RUNNING, "2024-05-01T10:00:00Z")) // 600s at now -> 10m
        val notStarted = m(4, "g/p", "d", timed(PipelineStatus.PENDING))
        val noPipeline = m(5, "g/p", "e")
        val all = listOf(noPipeline, notStarted, running, late, early)

        fun keys(spec: SortSpec) = MrSort.sort(all, spec, now).map { it.iid }

        assertEquals(listOf(1, 2, 3), keys(SortSpec(MrColumn.STARTED, true)).take(3))
        assertEquals(setOf(4, 5), keys(SortSpec(MrColumn.STARTED, true)).drop(3).toSet())
        assertEquals(listOf(3, 2, 1), keys(SortSpec(MrColumn.STARTED, false)).take(3))
        assertEquals(setOf(4, 5), keys(SortSpec(MrColumn.STARTED, false)).drop(3).toSet())

        // durations numeric: 60 < 600 (finished) ; running = 600 elapsed, tie broken by default order
        val byDuration = keys(SortSpec(MrColumn.DURATION, true))
        assertEquals(2, byDuration.first())
        assertEquals(setOf(1, 3), byDuration.subList(1, 3).toSet())
        assertEquals(setOf(4, 5), byDuration.drop(3).toSet())
        assertEquals(setOf(4, 5), keys(SortSpec(MrColumn.DURATION, false)).drop(3).toSet())

        // finished: running/pending/no pipeline have no finishedAt -> last
        assertEquals(listOf(1, 2), keys(SortSpec(MrColumn.FINISHED, true)).take(2))
        assertEquals(listOf(2, 1), keys(SortSpec(MrColumn.FINISHED, false)).take(2))
    }

    @Test
    fun timeColumnsStartNewestFirst() {
        assertEquals(SortSpec(MrColumn.STARTED, false), SortSpec.DEFAULT.toggle(MrColumn.STARTED))
        assertEquals(SortSpec(MrColumn.FINISHED, false), SortSpec.DEFAULT.toggle(MrColumn.FINISHED))
        assertEquals(SortSpec(MrColumn.DURATION, true), SortSpec.DEFAULT.toggle(MrColumn.DURATION))
    }

    // ---------------------------------------------------------------- column visibility migration

    @Test
    fun newColumnsVisibleForUsersWith11Settings() {
        val saved11 = listOf(MrColumn.STATUS, MrColumn.PROJECT, MrColumn.TITLE)
        assertEquals(
            setOf(MrColumn.STATUS, MrColumn.PROJECT, MrColumn.TITLE, MrColumn.STARTED, MrColumn.DURATION),
            MrColumn.effectiveVisible(saved11, null),
        )
        // once saved by 1.2 (all columns known), hiding STARTED sticks
        val saved12 = listOf(MrColumn.PROJECT, MrColumn.DURATION)
        assertEquals(
            setOf(MrColumn.STATUS, MrColumn.PROJECT, MrColumn.DURATION),
            MrColumn.effectiveVisible(saved12, MrColumn.values().toList()),
        )
        assertTrue(MrColumn.STARTED in MrColumn.DEFAULT_VISIBLE)
        assertTrue(MrColumn.DURATION in MrColumn.DEFAULT_VISIBLE)
        assertFalse(MrColumn.FINISHED in MrColumn.DEFAULT_VISIBLE)
        assertEquals(MrColumn.values().toList(), MrColumn.completeOrder(MrColumn.V1_1_COLUMNS))
    }

    // ---------------------------------------------------------------- ref display

    @Test
    fun refDisplay() {
        assertEquals("MR !881", RefDisplay.format("refs/merge-requests/881/head"))
        assertEquals("MR !881 (merged result)", RefDisplay.format("refs/merge-requests/881/merge"))
        assertEquals("MR !881 (merge train)", RefDisplay.format("refs/merge-requests/881/train"))
        assertEquals("refs/merge-requests/881/other", RefDisplay.format("refs/merge-requests/881/other"))
        assertEquals("feature/x", RefDisplay.format("refs/heads/feature/x"))
        assertEquals("v1.0", RefDisplay.format("refs/tags/v1.0"))
        assertEquals("main", RefDisplay.format("main"))
        assertEquals("", RefDisplay.format(null))
    }

    @Test
    fun searchTextIncludesDisplayedRef() {
        val p = pipeline(77, PipelineStatus.SUCCESS).copy(ref = "refs/merge-requests/881/merge")
        val text = MrTreeBuilder.searchText(PipelineRow(m(1, "g/p", "b"), p))
        assertTrue(text.contains("refs/merge-requests/881/merge"))
        assertTrue(text.contains("MR !881 (merged result)"))
    }

    // ---------------------------------------------------------------- parsing

    @Test
    fun graphQlPipelineTiming() {
        val o = JsonParser.parseString(
            """{"id":"gid://gitlab/Ci::Pipeline/9","iid":"3","status":"SUCCESS","path":"/g/p/-/pipelines/9",
               "updatedAt":"2024-05-01T10:05:00Z","startedAt":"2024-05-01T10:00:48Z",
               "finishedAt":"2024-05-01T10:05:00Z","duration":252}""",
        ).asJsonObject
        val p = Json.parseGraphQlPipeline(o, base)!!
        assertEquals(ts("2024-05-01T10:00:48Z"), p.startedAt)
        assertEquals(ts("2024-05-01T10:05:00Z"), p.finishedAt)
        assertEquals(252L, p.duration)
        assertEquals("4m 12s", PipelineTime.durationText(p))
    }

    @Test
    fun graphQlPipelineTimingMissingOrNull() {
        val o = JsonParser.parseString(
            """{"id":"gid://gitlab/Ci::Pipeline/9","status":"PENDING","startedAt":null,"duration":null}""",
        ).asJsonObject
        val p = Json.parseGraphQlPipeline(o, base)!!
        assertNull(p.startedAt)
        assertNull(p.duration)
        assertEquals("—", PipelineTime.durationText(p))
    }

    @Test
    fun restHeadPipelineTiming() {
        val body = """{"id":1,"iid":2,"head_pipeline":{"id":501,"iid":31,"status":"running",
            "started_at":"2024-05-01T10:00:00.000Z","finished_at":null,"duration":12.7,
            "web_url":"$base/g/p/-/pipelines/501"}}"""
        val p = Json.parseRestHeadPipeline(body)!!
        assertEquals(ts("2024-05-01T10:00:00Z"), p.startedAt)
        assertNull(p.finishedAt)
        assertEquals(12L, p.duration)
        assertTrue(PipelineTime.isRunning(p))
        assertEquals(600L, PipelineTime.durationSeconds(p, now))
    }

    @Test
    fun restPipelineListTiming() {
        val body = """[{"id":5,"iid":1,"status":"failed","ref":"refs/merge-requests/11/head","sha":"abc",
            "created_at":"2024-05-01T09:59:00Z","started_at":"2024-05-01T10:00:00Z",
            "finished_at":"2024-05-01T10:02:00Z","duration":"120"},
            {"id":6,"status":"success","duration":-5}]"""
        val list = Json.parseRestPipelineList(body)
        assertEquals(ts("2024-05-01T10:00:00Z"), list[0].startedAt)
        assertEquals(120L, list[0].duration)
        assertNull(list[1].startedAt)
        assertNull(list[1].duration)
    }

    @Test
    fun graphQlQueriesRequestTiming() {
        assertTrue(GitLabClient.graphQlQuery("authoredMergeRequests").contains("startedAt duration"))
        assertTrue(GitLabClient.RECENT_PIPELINES_QUERY.contains("startedAt duration"))
        assertTrue(GitLabClient.RECENT_PIPELINES_QUERY.contains("finishedAt"))
    }
}
