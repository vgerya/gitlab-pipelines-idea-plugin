package com.github.vgerya.gitlabpipelines.core

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Additional QA coverage: sub-path installations, edge cases of diff / error mapping. */
class QaGapsTest {

    private val subBase = "https://git.corp/gitlab"

    /**
     * GitLab under a relative URL root: GraphQL `path` already contains `/gitlab`, REST `web_url`s are
     * absolute. Both paths must hit `/gitlab/api/...` and produce identical pipeline URLs.
     */
    @Test
    fun subpathInstallationGraphQlAndRestAgree() {
        val fake = FakeGitLab(subBase)
        val graphT = RecordingTransport { req ->
            val r = fake.handle(req)
            if (req.url.endsWith("/api/graphql")) r.copy(body = r.body.replace("\"path\": \"/", "\"path\": \"/gitlab/")) else r
        }
        val viaGraphQl = GitLabClient("$subBase//", TOKEN, graphT).fetchMergeRequests(true, true)
        assertEquals(FetchSource.GRAPHQL, viaGraphQl.source)
        assertTrue(graphT.snapshot().all { it.url == "$subBase/api/graphql" })

        val restFake = FakeGitLab(subBase).apply { graphQlMode = FakeGitLab.GraphQlMode.ERRORS }
        val restT = RecordingTransport { restFake.handle(it) }
        val viaRest = GitLabClient("$subBase/", TOKEN, restT).fetchMergeRequests(true, true)
        assertEquals(FetchSource.REST, viaRest.source)
        assertTrue(restT.snapshot().all { it.url.startsWith("$subBase/api/") }, restT.snapshot().map { it.url }.toString())

        assertEquals(expectedFixtureMergeRequests(subBase), viaGraphQl.mrs)
        assertEquals(viaGraphQl.mrs, viaRest.mrs)
        assertEquals("$subBase/group/alpha/-/pipelines/501", viaRest.mrs.first { it.iid == 11 }.pipeline!!.webUrl)
    }

    @Test
    fun subpathGraphQlPathWithoutPrefixIsJoinedWithBase() {
        val fake = FakeGitLab(subBase)
        val result = GitLabClient(subBase, TOKEN, RecordingTransport { fake.handle(it) }).fetchMergeRequests(false, false)
        assertEquals("$subBase/group/alpha/-/pipelines/501", result.mrs.first { it.iid == 11 }.pipeline!!.webUrl)
    }

    @Test
    fun graphQlQueryHasExpectedFieldsAndVariable() {
        for (field in listOf("authoredMergeRequests", "assignedMergeRequests", "reviewRequestedMergeRequests")) {
            val q = GitLabClient.graphQlQuery(field)
            assertTrue(q.startsWith("query(\$after: String)"))
            assertTrue(q.contains("$field(state: opened, first: 100, after: \$after)"))
            for (f in listOf("pageInfo { hasNextPage endCursor }", "project { fullPath }", "headPipeline",
                "id iid status path updatedAt finishedAt", "id iid title webUrl draft sourceBranch updatedAt")) {
                assertTrue(q.contains(f), "query contains $f")
            }
        }
    }

    @Test
    fun graphQlCursorIsPassedOnlyAsVariable() {
        val gitlab = FakeGitLab()
        val t = RecordingTransport { gitlab.handle(it) }
        GitLabClient(BASE, TOKEN, t).fetchMergeRequests(false, false)
        val second = t.snapshot()[1]
        val body = JsonParser.parseString(second.body).asJsonObject
        assertFalse(body.get("query").asString.contains("cursor-1"))
        assertEquals("cursor-1", body.getAsJsonObject("variables").get("after").asString)
    }

    @Test
    fun nonNumericRetryAfterDefaultsTo60() {
        val t = RecordingTransport { response(429, "", mapOf("Retry-After" to listOf("Wed, 21 Oct 2015 07:28:00 GMT"))) }
        val e = assertThrows(GitLabException::class.java) { GitLabClient(BASE, TOKEN, t).fetchMergeRequests(false, false) }
        assertEquals(ErrorKind.RATE_LIMIT, e.kind)
        assertEquals(60L, e.retryAfterSec)
        assertEquals(1, t.snapshot().size) // no REST fallback on rate limit
    }

    @Test
    fun forbiddenAndNetworkDoNotFallBack() {
        val t403 = RecordingTransport { response(403) }
        assertEquals(ErrorKind.FORBIDDEN,
            assertThrows(GitLabException::class.java) { GitLabClient(BASE, TOKEN, t403).fetchMergeRequests(false, false) }.kind)
        assertEquals(1, t403.snapshot().size)
        val e = assertThrows(GitLabException::class.java) {
            GitLabClient(BASE, TOKEN, FailingTransport("boom $TOKEN")).fetchMergeRequests(false, false)
        }
        assertEquals(ErrorKind.NETWORK, e.kind)
        assertFalse(e.message!!.contains(TOKEN))
    }

    // ---------------------------------------------------------------- StatusDiff edge cases

    @Test
    fun statusChangeToDisabledTerminalIsSilent() {
        val before = mr(1, pipeline(10, PipelineStatus.RUNNING))
        val after = mr(1, pipeline(10, PipelineStatus.CANCELED, ts("2024-05-01T10:01:00Z")))
        val prefs = NotifyPrefs(failed = true, success = true, canceled = false)
        val r = StatusDiff.diff(mapOf(before.key to Seen(before.pipeline!!.id, PipelineStatus.RUNNING)), listOf(after), prefs, null)
        assertTrue(r.events.isEmpty())
        assertEquals(Seen(after.pipeline!!.id, PipelineStatus.CANCELED), r.next[after.key])
    }

    @Test
    fun pipelineDisappearingIsSilentAndRecorded() {
        val before = mr(1, pipeline(10, PipelineStatus.RUNNING))
        val after = mr(1, null)
        val r = StatusDiff.diff(mapOf(before.key to Seen(before.pipeline!!.id, PipelineStatus.RUNNING)), listOf(after), NotifyPrefs.ALL, null)
        assertTrue(r.events.isEmpty())
        assertEquals(Seen(null, null), r.next[after.key])
    }

    @Test
    fun failedThenRetriedSamePipelineFailsAgainEmits() {
        // failed → running (retry of jobs, same pipeline id) → failed: second failure must notify.
        val id = GlobalIds.pipeline(10)
        val running = mr(1, pipeline(10, PipelineStatus.RUNNING))
        val r1 = StatusDiff.diff(mapOf(running.key to Seen(id, PipelineStatus.FAILED)), listOf(running), NotifyPrefs.ALL, null)
        assertTrue(r1.events.isEmpty())
        val failed = mr(1, pipeline(10, PipelineStatus.FAILED, ts("2024-05-01T10:01:00Z")))
        val r2 = StatusDiff.diff(r1.next, listOf(failed), NotifyPrefs.ALL, null)
        assertEquals(1, r2.events.size)
    }

    @Test
    fun newMrAfterBaselineWithoutFinishedAtIsSilent() {
        val m = mr(5, pipeline(50, PipelineStatus.SUCCESS, finishedAt = null))
        val r = StatusDiff.diff(emptyMap(), listOf(m), NotifyPrefs.ALL, ts("2024-05-01T10:00:00Z"))
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun unknownStatusIsNotTerminalAndKeepsRaw() {
        assertEquals(PipelineStatus.UNKNOWN, PipelineStatus.parse("brand_new_status"))
        assertFalse(PipelineStatus.UNKNOWN.terminal)
        assertEquals(Category.OTHER, PipelineStatus.UNKNOWN.category)
        assertNull(UrlUtil.normalizeBaseUrl("ftp://git.corp"))
        assertEquals("https://git.corp/gitlab", UrlUtil.normalizeBaseUrl(" https://git.corp/gitlab/// "))
    }
}
