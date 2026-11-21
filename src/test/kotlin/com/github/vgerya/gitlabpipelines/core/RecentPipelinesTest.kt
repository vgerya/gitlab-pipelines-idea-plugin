package com.github.vgerya.gitlabpipelines.core

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecentPipelinesTest {

    private val expected11 = listOf(
        Pipeline(
            id = "gid://gitlab/Ci::Pipeline/501", iid = "31", status = PipelineStatus.FAILED, rawStatus = "failed",
            label = "failed", webUrl = "$BASE/group/alpha/-/pipelines/501",
            updatedAt = ts("2024-05-01T10:05:00Z"), finishedAt = ts("2024-05-01T10:05:00Z"),
            ref = "fix-login", sha = "abc123", createdAt = ts("2024-05-01T10:00:00Z"),
        ),
        Pipeline(
            id = "gid://gitlab/Ci::Pipeline/480", iid = "30", status = PipelineStatus.SUCCESS, rawStatus = "success",
            label = "passed", webUrl = "$BASE/group/alpha/-/pipelines/480",
            updatedAt = ts("2024-04-30T09:10:00Z"), finishedAt = ts("2024-04-30T09:10:00Z"),
            ref = "refs/merge-requests/11/head", sha = "def456", createdAt = ts("2024-04-30T09:00:00Z"),
        ),
    )

    @Test
    fun parseGraphQl() {
        val map = Json.parseGraphQlMrPipelines(Fixtures.load("graphql_mr_pipelines.json"), BASE)
        assertEquals(expected11, map["gid://gitlab/MergeRequest/9001"])
        assertEquals(emptyList<Pipeline>(), map["gid://gitlab/MergeRequest/9003"])
        assertEquals(2, map.size)
    }

    @Test
    fun parseGraphQlProjectNullAndErrors() {
        assertEquals(emptyMap<String, List<Pipeline>>(), Json.parseGraphQlMrPipelines("""{"data":{"project":null}}""", BASE))
        val e = assertThrows(GitLabException::class.java) {
            Json.parseGraphQlMrPipelines("""{"errors":[{"message":"Field 'sha' doesn't exist"}]}""", BASE)
        }
        assertEquals(ErrorKind.GRAPHQL, e.kind)
        val bad = assertThrows(GitLabException::class.java) { Json.parseGraphQlMrPipelines("""{"data":{"project":{}}}""", BASE) }
        assertEquals(ErrorKind.BAD_RESPONSE, bad.kind)
    }

    @Test
    fun parseRest() {
        // REST list items are basic pipeline objects: no detailed_status, no finished_at
        val list = Json.parseRestPipelineList(Fixtures.load("rest_mr_pipelines_11.json"))
        assertEquals(expected11.map { it.copy(label = null, finishedAt = null) }, list)
        assertEquals(emptyList<Pipeline>(), Json.parseRestPipelineList("[]"))
    }

    private val mrs = expectedFixtureMergeRequests().filter { it.iid == 11 || it.iid == 13 }

    @Test
    fun graphQlRequestBatchesPerProject() {
        val transport = RecordingTransport { req ->
            if (req.url.endsWith("/api/graphql")) response(200, Fixtures.load("graphql_mr_pipelines.json")) else response(404)
        }
        val client = GitLabClient(BASE, TOKEN, transport)
        val result = client.fetchRecentPipelines(mrs)
        assertEquals(expected11, result["gid://gitlab/MergeRequest/9001"])
        val requests = transport.snapshot()
        assertEquals(1, requests.size)
        val body = JsonParser.parseString(requests[0].body).asJsonObject
        assertTrue(body.get("query").asString.contains("pipelines(first: \$first)"))
        val vars = body.getAsJsonObject("variables")
        assertEquals("group/alpha", vars.get("fullPath").asString)
        assertEquals(listOf("11", "13"), vars.getAsJsonArray("iids").map { it.asString })
        assertEquals(10, vars.get("first").asInt)
        assertEquals("Bearer $TOKEN", requests[0].headers["Authorization"])
    }

    @Test
    fun graphQlErrorFallsBackToRest() {
        val transport = RecordingTransport { req ->
            when {
                req.url.endsWith("/api/graphql") -> response(200, """{"errors":[{"message":"nope"}]}""")
                req.url.contains("/merge_requests/11/pipelines") -> response(200, Fixtures.load("rest_mr_pipelines_11.json"))
                req.url.contains("/merge_requests/13/pipelines") -> response(404, """{"message":"404"}""")
                else -> response(500)
            }
        }
        val result = GitLabClient(BASE, TOKEN, transport).fetchRecentPipelines(mrs)
        assertEquals(expected11.map { it.copy(label = null, finishedAt = null) }, result["gid://gitlab/MergeRequest/9001"])
        assertEquals(emptyList<Pipeline>(), result["gid://gitlab/MergeRequest/9003"])
        val rest = transport.snapshot().filter { it.method == "GET" }
        assertEquals(2, rest.size)
        assertTrue(rest.any { it.url == "$BASE/api/v4/projects/group%2Falpha/merge_requests/11/pipelines?per_page=10" }, rest.toString())
        assertTrue(rest.all { it.headers["PRIVATE-TOKEN"] == TOKEN })
        assertFalse(transport.snapshot().any { it.url.contains(TOKEN) })
    }

    @Test
    fun authErrorPropagatesWithoutFallback() {
        val transport = RecordingTransport { response(401) }
        val e = assertThrows(GitLabException::class.java) { GitLabClient(BASE, TOKEN, transport).fetchRecentPipelines(mrs) }
        assertEquals(ErrorKind.AUTH, e.kind)
        assertEquals(1, transport.snapshot().size)
    }

    @Test
    fun emptyInputMakesNoRequests() {
        val transport = RecordingTransport { response(500) }
        assertEquals(emptyMap<String, List<Pipeline>>(), GitLabClient(BASE, TOKEN, transport).fetchRecentPipelines(emptyList()))
        assertEquals(0, transport.snapshot().size)
    }

    @Test
    fun defaultMergeRequestQueryDoesNotAskForPipelineLists() {
        assertFalse(GitLabClient.graphQlQuery("authoredMergeRequests").contains("pipelines("))
    }
}
