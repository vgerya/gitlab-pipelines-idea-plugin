package com.github.vgerya.gitlabpipelines.core

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 1.2.0 QA: edge cases of the branch filter, pipeline timing, parsing and ref display. */
class V12QaTest {

    private val base = "https://gitlab.example.com"
    private val now = ts("2024-05-01T10:10:00Z")

    private fun m(id: Long, project: String, branch: String, p: Pipeline? = null) =
        mr(id, pipeline = p).copy(projectPath = project, sourceBranch = branch)

    private fun timed(status: PipelineStatus, startedAt: String? = null, finishedAt: String? = null, duration: Long? = null) =
        pipeline(1, status, finishedAt = finishedAt?.let { ts(it) }).copy(startedAt = startedAt?.let { ts(it) }, duration = duration)

    // ---------------------------------------------------------------- branch filter

    @Test
    fun repoWithForkAndUpstreamRemotesMatchesBothProjects() {
        val states = BranchFilter.resolve(
            listOf(
                LocalRepo(
                    listOf("git@gitlab.example.com:me/alpha.git", "https://gitlab.example.com/group/alpha.git"),
                    "feature",
                ),
            ),
            base,
        )
        assertEquals(listOf(RepoState(setOf("me/alpha", "group/alpha"), "feature")), states)
        assertTrue(BranchFilter.matchesBranch(m(1, "group/alpha", "feature"), states))
        assertTrue(BranchFilter.matchesBranch(m(2, "me/alpha", "feature"), states))
        assertFalse(BranchFilter.matchesBranch(m(3, "group/alpha", "main"), states))
    }

    @Test
    fun sameBranchNameInOtherProjectDoesNotMatch() {
        // repo A (alpha) on "main", repo B (beta) on "feature": an alpha MR from "feature" is not current
        val states = BranchFilter.resolve(
            listOf(
                LocalRepo(listOf("git@gitlab.example.com:g/alpha.git"), "main"),
                LocalRepo(listOf("git@gitlab.example.com:g/beta.git"), "feature"),
            ),
            base,
        )
        val mrs = listOf(m(1, "g/alpha", "feature"), m(2, "g/beta", "feature"), m(3, "g/alpha", "main"))
        assertEquals(listOf(mrs[1], mrs[2]), BranchFilter.filter(mrs, FilterMode.CURRENT_BRANCH, states))
    }

    @Test
    fun blankBranchIsTreatedAsDetached() {
        val states = BranchFilter.resolve(listOf(LocalRepo(listOf("git@gitlab.example.com:g/alpha.git"), "  ")), base)
        assertEquals(listOf(RepoState(setOf("g/alpha"), null)), states)
        assertFalse(BranchFilter.matchesBranch(m(1, "g/alpha", "  "), states))
        assertFalse(BranchFilter.matchesBranch(m(2, "g/alpha", ""), states))
        assertTrue(BranchFilter.currentBranches(states).isEmpty())
    }

    @Test
    fun projectMatchHonoursHostAndBasePath() {
        val sub = "https://example.com/gitlab"
        val repos = listOf(
            LocalRepo(listOf("https://example.com/gitlab/g/alpha.git"), "x"), // http with base sub-path
            LocalRepo(listOf("git@EXAMPLE.com:g/beta.git"), "x"), // ssh: no sub-path, host case-insensitive
            LocalRepo(listOf("ssh://git@example.com:2222/g/gamma.git"), "x"), // ssh port ignored
            LocalRepo(listOf("https://example.com/g/delta.git"), "x"), // http outside the base sub-path
            LocalRepo(listOf("git@example.org:g/eps.git"), "x"), // other host
        )
        val states = BranchFilter.resolve(repos, sub)
        assertEquals(setOf("g/alpha", "g/beta", "g/gamma"), BranchFilter.projectPaths(states))
    }

    @Test
    fun nestedGroupsAndBranchWithSlashes() {
        val states = BranchFilter.resolve(
            listOf(LocalRepo(listOf("git@gitlab.example.com:Top/Sub/Proj.git"), "feature/JIRA-1/x")),
            base,
        )
        assertTrue(BranchFilter.matchesBranch(m(1, "top/sub/proj", "feature/JIRA-1/x"), states))
        assertFalse(BranchFilter.matchesBranch(m(2, "top/sub/proj", "feature/jira-1/x"), states))
        assertFalse(BranchFilter.matchesProject(m(3, "top/sub", "feature/JIRA-1/x"), states))
    }

    @Test
    fun noConfiguredBaseUrlResolvesNothing() {
        assertTrue(BranchFilter.resolve(listOf(LocalRepo(listOf("git@gitlab.example.com:g/a.git"), "main")), null).isEmpty())
        assertTrue(BranchFilter.resolve(listOf(LocalRepo(emptyList(), "main")), base).isEmpty())
    }

    @Test
    fun parseHeadEdgeCases() {
        // only the first line counts; a symbolic ref to a non-branch is not a branch
        assertEquals("main", BranchFilter.parseHead("ref: refs/heads/main\nref: refs/heads/other\n"))
        assertNull(BranchFilter.parseHead("ref: refs/tags/v1"))
        assertNull(BranchFilter.parseHead("\nref: refs/heads/main"))
    }

    @Test
    fun legacyMigrationKeepsExplicitChoice() {
        // a 1.1 user with "only current project" who picks ALL in 1.2 keeps ALL
        assertEquals(FilterMode.ALL, FilterMode.resolve("ALL", true))
        assertEquals(FilterMode.CURRENT_PROJECT, FilterMode.resolve("", true))
        assertEquals(FilterMode.CURRENT_PROJECT, FilterMode.resolve("current_project", false))
    }

    // ---------------------------------------------------------------- duration

    @Test
    fun formatDurationBoundaries() {
        assertEquals("—", PipelineTime.formatDuration(-1))
        assertEquals("59s", PipelineTime.formatDuration(59))
        assertEquals("1m 1s", PipelineTime.formatDuration(61))
        assertEquals("23h 59m", PipelineTime.formatDuration(86_399))
        assertEquals("1d 0h", PipelineTime.formatDuration(86_400))
        assertEquals("1d 1h", PipelineTime.formatDuration(90_000))
        assertEquals("400d 0h", PipelineTime.formatDuration(400L * 86_400))
    }

    @Test
    fun runningStatesAndFallbacks() {
        // canceling counts as running (still executing)
        val canceling = timed(PipelineStatus.CANCELING, startedAt = "2024-05-01T10:09:00Z")
        assertTrue(PipelineTime.isRunning(canceling))
        assertEquals("1m 0s", PipelineTime.durationText(canceling, now))
        // running but not started yet (no startedAt): nothing to count from
        val runningNoStart = timed(PipelineStatus.RUNNING)
        assertFalse(PipelineTime.isRunning(runningNoStart))
        assertEquals("—", PipelineTime.durationText(runningNoStart, now))
        // running with finishedAt (stale status) is not live: uses the reported values
        val stale = timed(PipelineStatus.RUNNING, "2024-05-01T10:00:00Z", "2024-05-01T10:00:30Z")
        assertFalse(PipelineTime.isRunning(stale))
        assertEquals(30L, PipelineTime.durationSeconds(stale, now))
        // pending/created never live, even if startedAt is set (retry)
        assertFalse(PipelineTime.isRunning(timed(PipelineStatus.PENDING, startedAt = "2024-05-01T10:00:00Z")))
        // canceled before it started: finished but no start, no duration
        assertNull(PipelineTime.durationSeconds(timed(PipelineStatus.CANCELED, finishedAt = "2024-05-01T10:00:00Z"), now))
        // finished with finishedAt before startedAt (clock skew) clamps to 0
        assertEquals(0L, PipelineTime.durationSeconds(timed(PipelineStatus.SUCCESS, "2024-05-01T10:00:30Z", "2024-05-01T10:00:00Z"), now))
        // negative duration (never produced by the parser) clamps to 0
        assertEquals(0L, PipelineTime.durationSeconds(timed(PipelineStatus.SUCCESS, duration = -5), now))
    }

    @Test
    fun liveDurationAdvancesWithClock() {
        val running = timed(PipelineStatus.RUNNING, startedAt = "2024-05-01T10:09:01Z")
        assertEquals("59s", PipelineTime.durationText(running, now))
        assertEquals("1m 0s", PipelineTime.durationText(running, now.plusSeconds(1)))
        assertEquals("1h 00m", PipelineTime.durationText(running, now.plusSeconds(3541)))
    }

    // ---------------------------------------------------------------- parsing

    @Test
    fun secondsParsingRejectsGarbage() {
        fun dur(json: String) = Json.parseGraphQlPipeline(
            JsonParser.parseString("""{"id":"gid://gitlab/Ci::Pipeline/1","status":"SUCCESS","duration":$json}""").asJsonObject,
            base,
        )!!.duration
        assertEquals(0L, dur("0"))
        assertEquals(59L, dur("59.99"))
        assertNull(dur("\"NaN\""))
        assertNull(dur("\"Infinity\""))
        assertNull(dur("\"abc\""))
        assertNull(dur("true"))
        assertNull(dur("{}"))
        assertNull(dur("[]"))
        assertNull(dur("1e30"))
    }

    @Test
    fun invalidStartedAtIsNull() {
        val p = Json.parseGraphQlPipeline(
            JsonParser.parseString("""{"id":"gid://gitlab/Ci::Pipeline/1","status":"RUNNING","startedAt":"yesterday"}""").asJsonObject,
            base,
        )!!
        assertNull(p.startedAt)
        assertFalse(PipelineTime.isRunning(p))
    }

    @Test
    fun graphQlRecentPipelinesCarryTiming() {
        val body = """{"data":{"project":{"mergeRequests":{"nodes":[{"id":"gid://gitlab/MergeRequest/1","iid":"1",
            "pipelines":{"nodes":[{"id":"gid://gitlab/Ci::Pipeline/7","iid":"2","status":"RUNNING",
            "ref":"refs/merge-requests/1/merge","sha":"abc","createdAt":"2024-05-01T10:00:00Z",
            "startedAt":"2024-05-01T10:01:00Z","finishedAt":null,"duration":null}]}}]}}}}"""
        val p = Json.parseGraphQlMrPipelines(body, base).getValue("gid://gitlab/MergeRequest/1").single()
        assertEquals(ts("2024-05-01T10:01:00Z"), p.startedAt)
        assertNull(p.duration)
        assertEquals("refs/merge-requests/1/merge", p.ref)
        assertEquals("9m 0s", PipelineTime.durationText(p, now))
        assertEquals("MR !1 (merged result)", RefDisplay.format(p.ref))
    }

    @Test
    fun restPipelineListWithoutTimingFields() {
        // the MR pipelines REST endpoint returns basic pipeline objects (no started_at / duration)
        val list = Json.parseRestPipelineList(
            """[{"id":5,"iid":1,"status":"success","ref":"main","sha":"abc","created_at":"2024-05-01T09:59:00Z",
                "updated_at":"2024-05-01T10:02:00Z","web_url":"$base/g/p/-/pipelines/5"}]""",
        )
        assertNull(list.single().startedAt)
        assertNull(list.single().duration)
        assertEquals("—", PipelineTime.durationText(list.single(), now))
    }

    // ---------------------------------------------------------------- ref display

    @Test
    fun refDisplayEdgeCases() {
        assertEquals("MR !1", RefDisplay.format("  refs/merge-requests/1/head  "))
        assertEquals("refs/merge-requests/abc/head", RefDisplay.format("refs/merge-requests/abc/head"))
        assertEquals("refs/merge-requests/1/head/x", RefDisplay.format("refs/merge-requests/1/head/x"))
        assertEquals("refs/merge-requests//head", RefDisplay.format("refs/merge-requests//head"))
        assertEquals("refs/MERGE-REQUESTS/1/head", RefDisplay.format("refs/MERGE-REQUESTS/1/head"))
        assertEquals("refs/pipelines/42", RefDisplay.format("refs/pipelines/42"))
        assertEquals("merge-requests/1/head", RefDisplay.format("merge-requests/1/head"))
        assertEquals("", RefDisplay.format("   "))
    }

    // ---------------------------------------------------------------- column migration

    @Test
    fun columnMigrationEdgeCases() {
        // 1.1 settings that had ROLE and PIPELINE on keep them, and gain STARTED + DURATION once
        val saved11 = listOf(MrColumn.STATUS, MrColumn.ROLE, MrColumn.PIPELINE)
        assertEquals(
            setOf(MrColumn.STATUS, MrColumn.ROLE, MrColumn.PIPELINE, MrColumn.STARTED, MrColumn.DURATION),
            MrColumn.effectiveVisible(saved11, emptyList()),
        )
        // FINISHED stays opt-in for migrated users
        assertFalse(MrColumn.FINISHED in MrColumn.effectiveVisible(saved11, null))
        // STATUS is always visible even if the saved list lost it
        assertTrue(MrColumn.STATUS in MrColumn.effectiveVisible(emptyList(), MrColumn.values().toList()))
        // unknown names in a saved list are ignored
        assertEquals(listOf(MrColumn.PROJECT, MrColumn.DURATION), MrColumn.parseList("PROJECT,bogus,duration,PROJECT"))
    }
}
