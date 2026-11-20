package com.github.vgerya.gitlabpipelines.core

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitLabClientTest {

    private val gitlab = FakeGitLab()
    private val transport = RecordingTransport { gitlab.handle(it) }
    private val client = GitLabClient("$BASE/", TOKEN, transport)

    private fun assertTokenNeverInUrlsOrMessages(e: Throwable? = null) {
        for (r in transport.snapshot()) {
            assertFalse(r.url.contains(TOKEN), "token in URL ${r.url}")
            assertFalse(r.toString().contains(TOKEN), "token in request toString")
            assertTrue(r.url.startsWith("$BASE/"), "only the configured host is contacted: ${r.url}")
        }
        if (e != null) {
            assertFalse((e.message ?: "").contains(TOKEN))
            assertFalse(e.toString().contains(TOKEN))
        }
    }

    // ------------------------------------------------------------------ auth headers

    @Test
    fun getUserUsesPrivateTokenHeader() {
        assertEquals(User(42, "jdoe", "John Doe"), client.getUser())
        val req = transport.snapshot().single()
        assertEquals("GET", req.method)
        assertEquals("$BASE/api/v4/user", req.url)
        assertEquals(TOKEN, req.headers["PRIVATE-TOKEN"])
        assertNull(req.headers["Authorization"])
        assertTokenNeverInUrlsOrMessages()
    }

    @Test
    fun graphQlUsesBearerHeaderAndJsonBody() {
        client.fetchMergeRequests(includeAssigned = false, includeReview = false)
        val req = transport.snapshot().first()
        assertEquals("POST", req.method)
        assertEquals("$BASE/api/graphql", req.url)
        assertEquals("Bearer $TOKEN", req.headers["Authorization"])
        assertEquals("application/json", req.headers["Content-Type"])
        assertNull(req.headers["PRIVATE-TOKEN"])
        val body = JsonParser.parseString(req.body).asJsonObject
        assertTrue(body.get("query").asString.contains("authoredMergeRequests(state: opened, first: 100, after: \$after)"))
        assertTrue(body.getAsJsonObject("variables").get("after").isJsonNull)
        assertFalse(req.body!!.contains(TOKEN))
        assertTokenNeverInUrlsOrMessages()
    }

    // ------------------------------------------------------------------ GraphQL

    @Test
    fun graphQlPaginatesOverTwoPages() {
        val result = client.fetchMergeRequests(includeAssigned = false, includeReview = false)
        assertEquals(FetchSource.GRAPHQL, result.source)
        assertEquals(listOf(11, 12, 13), result.mrs.map { it.iid })
        val requests = transport.snapshot()
        assertEquals(2, requests.size)
        val secondVars = JsonParser.parseString(requests[1].body).asJsonObject.getAsJsonObject("variables")
        assertEquals("cursor-1", secondVars.get("after").asString)
    }

    @Test
    fun graphQlStopsAfterFivePages() {
        val endless = RecordingTransport {
            response(200, Fixtures.load("graphql_page1.json"))
        }
        val result = GitLabClient(BASE, TOKEN, endless).fetchMergeRequests(includeAssigned = false, includeReview = false)
        assertEquals(GitLabClient.MAX_PAGES, endless.snapshot().size)
        assertEquals(2, result.mrs.size) // same nodes every page → deduplicated
    }

    @Test
    fun authoredAssignedReviewDedupedWithRoles() {
        val result = client.fetchMergeRequests(includeAssigned = true, includeReview = true)
        assertEquals(FetchSource.GRAPHQL, result.source)
        assertEquals(expectedFixtureMergeRequests(), result.mrs)
        val mr12 = result.mrs.single { it.iid == 12 }
        assertEquals(setOf(Role.AUTHOR, Role.ASSIGNEE), mr12.roles)
        assertEquals(4, transport.snapshot().size) // authored×2, assigned, review
    }

    @Test
    fun currentUserNullIsAuthWithoutFallback() {
        gitlab.graphQlMode = FakeGitLab.GraphQlMode.NO_USER
        val e = assertThrows(GitLabException::class.java) { client.fetchMergeRequests(false, false) }
        assertEquals(ErrorKind.AUTH, e.kind)
        assertEquals(1, transport.snapshot().size)
    }

    // ------------------------------------------------------------------ HTTP status mapping

    @Test
    fun http401IsAuthAndDoesNotFallBack() {
        gitlab.forced = response(401, """{"message":"401 Unauthorized"}""")
        val e = assertThrows(GitLabException::class.java) { client.fetchMergeRequests(false, false) }
        assertEquals(ErrorKind.AUTH, e.kind)
        assertEquals(401, e.httpStatus)
        assertEquals(1, transport.snapshot().size)
        assertTokenNeverInUrlsOrMessages(e)
    }

    @Test
    fun http401OnGetUser() {
        gitlab.forced = response(401)
        val e = assertThrows(GitLabException::class.java) { client.getUser() }
        assertEquals(ErrorKind.AUTH, e.kind)
    }

    @Test
    fun http403IsForbidden() {
        gitlab.forced = response(403)
        val e = assertThrows(GitLabException::class.java) { client.fetchMergeRequests(false, false) }
        assertEquals(ErrorKind.FORBIDDEN, e.kind)
        assertEquals(403, e.httpStatus)
        assertEquals(1, transport.snapshot().size)
    }

    @Test
    fun http429IsRateLimitWithRetryAfter() {
        gitlab.forced = response(429, "", mapOf("retry-after" to listOf("120")))
        val e = assertThrows(GitLabException::class.java) { client.fetchMergeRequests(false, false) }
        assertEquals(ErrorKind.RATE_LIMIT, e.kind)
        assertEquals(120L, e.retryAfterSec)
        assertEquals(1, transport.snapshot().size)
    }

    @Test
    fun http429DefaultRetryAfter() {
        gitlab.forced = response(429)
        val e = assertThrows(GitLabException::class.java) { client.getUser() }
        assertEquals(GitLabClient.DEFAULT_RETRY_AFTER_SEC, e.retryAfterSec)
    }

    @Test
    fun http500EverywhereIsServer() {
        gitlab.forced = response(503, "<html>maintenance</html>")
        val e = assertThrows(GitLabException::class.java) { client.fetchMergeRequests(false, false) }
        assertEquals(ErrorKind.SERVER, e.kind)
        assertEquals(503, e.httpStatus)
        // GraphQL failed with 5xx → REST tried (GET /user) → also 5xx → propagated
        assertEquals(2, transport.snapshot().size)
    }

    @Test
    fun ioExceptionIsNetwork() {
        val c = GitLabClient(BASE, TOKEN, FailingTransport("Connection refused"))
        val e = assertThrows(GitLabException::class.java) { c.fetchMergeRequests(true, true) }
        assertEquals(ErrorKind.NETWORK, e.kind)
        assertEquals("Connection refused", e.message)
    }

    @Test
    fun ioExceptionMessageIsSanitized() {
        val c = GitLabClient(BASE, TOKEN, FailingTransport("weird proxy echoed $TOKEN"))
        val e = assertThrows(GitLabException::class.java) { c.getUser() }
        assertFalse(e.message!!.contains(TOKEN))
    }

    @Test
    fun notConfigured() {
        assertEquals(
            ErrorKind.NOT_CONFIGURED,
            assertThrows(GitLabException::class.java) { GitLabClient("", TOKEN, transport) }.kind,
        )
        assertEquals(
            ErrorKind.NOT_CONFIGURED,
            assertThrows(GitLabException::class.java) { GitLabClient("ftp://x", TOKEN, transport) }.kind,
        )
        assertEquals(
            ErrorKind.NOT_CONFIGURED,
            assertThrows(GitLabException::class.java) { GitLabClient(BASE, "  ", transport) }.kind,
        )
    }

    @Test
    fun redirectIsBadResponse() {
        gitlab.forced = response(302, "", mapOf("Location" to listOf("https://evil.example/login")))
        val e = assertThrows(GitLabException::class.java) { client.getUser() }
        assertEquals(ErrorKind.BAD_RESPONSE, e.kind)
        assertEquals(302, e.httpStatus)
    }

    // ------------------------------------------------------------------ REST fallback

    @Test
    fun graphQlErrorsFallBackToRestWithPaginationAndDetails() {
        gitlab.graphQlMode = FakeGitLab.GraphQlMode.ERRORS
        val result = client.fetchMergeRequests(includeAssigned = true, includeReview = true)
        assertEquals(FetchSource.REST, result.source)
        val urls = transport.snapshot().map { it.url }
        assertEquals("$BASE/api/graphql", urls[0])
        assertEquals("$BASE/api/v4/user", urls[1])
        assertTrue(urls.contains("$BASE/api/v4/merge_requests?scope=created_by_me&state=opened&per_page=100&page=1"))
        assertTrue(urls.contains("$BASE/api/v4/merge_requests?scope=created_by_me&state=opened&per_page=100&page=2"))
        assertTrue(urls.contains("$BASE/api/v4/merge_requests?scope=assigned_to_me&state=opened&per_page=100&page=1"))
        assertTrue(urls.contains("$BASE/api/v4/merge_requests?scope=all&reviewer_username=jdoe&state=opened&per_page=100&page=1"))
        for ((pid, iid) in listOf(7 to 11, 8 to 12, 7 to 13, 8 to 14, 7 to 15)) {
            assertEquals(1, urls.count { it == "$BASE/api/v4/projects/$pid/merge_requests/$iid" }, "detail $iid once")
        }
        assertEquals(1 + 1 + 2 + 1 + 1 + 5, urls.size)
        transport.snapshot().drop(1).forEach {
            assertEquals(TOKEN, it.headers["PRIVATE-TOKEN"])
            assertEquals("GET", it.method)
        }
        assertTokenNeverInUrlsOrMessages()
    }

    @Test
    fun graphQlAndRestProduceEqualResults() {
        val viaGraphQl = client.fetchMergeRequests(includeAssigned = true, includeReview = true)
        val restGitLab = FakeGitLab().apply { graphQlMode = FakeGitLab.GraphQlMode.ERRORS }
        val viaRest = GitLabClient(BASE, TOKEN, RecordingTransport { restGitLab.handle(it) })
            .fetchMergeRequests(includeAssigned = true, includeReview = true)
        assertEquals(FetchSource.GRAPHQL, viaGraphQl.source)
        assertEquals(FetchSource.REST, viaRest.source)
        assertEquals(viaGraphQl.mrs, viaRest.mrs)
        assertEquals(expectedFixtureMergeRequests(), viaRest.mrs)
    }

    @Test
    fun graphQl404And500FallBackToRest() {
        for (mode in listOf(FakeGitLab.GraphQlMode.HTTP_404, FakeGitLab.GraphQlMode.HTTP_500)) {
            val g = FakeGitLab().apply { graphQlMode = mode }
            val result = GitLabClient(BASE, TOKEN, RecordingTransport { g.handle(it) }).fetchMergeRequests(false, false)
            assertEquals(FetchSource.REST, result.source, "mode $mode")
            assertEquals(listOf(11, 12, 13), result.mrs.map { it.iid })
        }
    }

    @Test
    fun restWithoutOptionalListsSkipsThem() {
        gitlab.graphQlMode = FakeGitLab.GraphQlMode.ERRORS
        val result = client.fetchMergeRequests(includeAssigned = false, includeReview = false)
        assertEquals(listOf(11, 12, 13), result.mrs.map { it.iid })
        assertTrue(result.mrs.all { it.roles == setOf(Role.AUTHOR) })
        val urls = transport.snapshot().map { it.url }
        assertFalse(urls.any { it.contains("assigned_to_me") || it.contains("reviewer_username") })
    }

    @Test
    fun restStopsAfterFivePages() {
        val t = RecordingTransport { req ->
            when {
                req.url.endsWith("/api/graphql") -> response(200, Fixtures.load("graphql_errors.json"))
                req.url.endsWith("/api/v4/user") -> response(200, Fixtures.load("rest_user.json"))
                req.url.contains("/api/v4/merge_requests?") -> {
                    val page = req.url.substringAfter("&page=").toInt()
                    response(200, "[]", mapOf("X-Next-Page" to listOf((page + 1).toString())))
                }
                else -> response(404)
            }
        }
        val result = GitLabClient(BASE, TOKEN, t).fetchMergeRequests(false, false)
        assertTrue(result.mrs.isEmpty())
        assertEquals(GitLabClient.MAX_PAGES, t.snapshot().count { it.url.contains("/merge_requests?") })
    }

    @Test
    fun restDetail404MeansNoPipeline() {
        val t = RecordingTransport { req ->
            if (req.url.contains("/projects/")) response(404) else gitlab.handle(req)
        }
        gitlab.graphQlMode = FakeGitLab.GraphQlMode.ERRORS
        val result = GitLabClient(BASE, TOKEN, t).fetchMergeRequests(false, false)
        assertEquals(3, result.mrs.size)
        assertTrue(result.mrs.all { it.pipeline == null })
    }

    @Test
    fun restDetail401Propagates() {
        val t = RecordingTransport { req ->
            if (req.url.contains("/projects/")) response(401) else gitlab.handle(req)
        }
        gitlab.graphQlMode = FakeGitLab.GraphQlMode.ERRORS
        val e = assertThrows(GitLabException::class.java) { GitLabClient(BASE, TOKEN, t).fetchMergeRequests(false, false) }
        assertEquals(ErrorKind.AUTH, e.kind)
    }
}
