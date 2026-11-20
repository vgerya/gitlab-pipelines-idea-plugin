package com.github.vgerya.gitlabpipelines.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections

/** End-to-end over real HTTP: com.sun.net.httpserver + [JdkHttpTransport]. */
class GitLabClientHttpServerTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var gitlab: FakeGitLab
    private val seenRequests: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val seenTokens: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val transport = JdkHttpTransport(
        JdkHttpTransport.defaultClient(Duration.ofSeconds(5)),
        Duration.ofSeconds(5),
    )

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        base = "http://127.0.0.1:${server.address.port}"
        gitlab = FakeGitLab(base)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val headers = exchange.requestHeaders.entries.associate { (k, v) -> k to v.joinToString(",") }
            seenRequests += "${exchange.requestMethod} ${exchange.requestURI}"
            exchange.requestHeaders.getFirst("PRIVATE-TOKEN")?.let { seenTokens += it }
            exchange.requestHeaders.getFirst("Authorization")?.let { seenTokens += it.removePrefix("Bearer ") }
            val req = HttpRequestSpec(
                method = exchange.requestMethod,
                url = base + exchange.requestURI.toString(),
                headers = headers,
                body = body.ifEmpty { null },
            )
            val resp = gitlab.handle(req)
            resp.headers.forEach { (k, values) -> values.forEach { v -> exchange.responseHeaders.add(k, v) } }
            val bytes = resp.body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(resp.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        } finally {
            exchange.close()
        }
    }

    private fun client() = GitLabClient(base, TOKEN, transport)

    @Test
    fun happyGraphQl() {
        val result = client().fetchMergeRequests(includeAssigned = true, includeReview = true)
        assertEquals(FetchSource.GRAPHQL, result.source)
        assertEquals(expectedFixtureMergeRequests(base), result.mrs)
        assertTrue(seenTokens.isNotEmpty())
        assertTrue(seenTokens.all { it == TOKEN })
        assertFalse(seenRequests.any { it.contains(TOKEN) })
    }

    @Test
    fun getUser() {
        assertEquals(User(42, "jdoe", "John Doe"), client().getUser())
    }

    @Test
    fun graphQlErrorFallsBackToRest() {
        gitlab.graphQlMode = FakeGitLab.GraphQlMode.ERRORS
        val result = client().fetchMergeRequests(includeAssigned = true, includeReview = true)
        assertEquals(FetchSource.REST, result.source)
        assertEquals(expectedFixtureMergeRequests(base), result.mrs)
        assertTrue(seenRequests.contains("GET /api/v4/merge_requests?scope=created_by_me&state=opened&per_page=100&page=2"))
        assertEquals(5, seenRequests.count { it.startsWith("GET /api/v4/projects/") })
    }

    @Test
    fun unauthorized() {
        gitlab.forced = response(401, """{"message":"401 Unauthorized"}""")
        val e = assertThrows(GitLabException::class.java) { client().fetchMergeRequests(false, false) }
        assertEquals(ErrorKind.AUTH, e.kind)
        assertEquals(401, e.httpStatus)
    }

    @Test
    fun rateLimited() {
        gitlab.forced = response(429, "Retry later", mapOf("Retry-After" to listOf("30")))
        val e = assertThrows(GitLabException::class.java) { client().fetchMergeRequests(false, false) }
        assertEquals(ErrorKind.RATE_LIMIT, e.kind)
        assertEquals(30L, e.retryAfterSec)
    }

    @Test
    fun connectionRefusedIsNetwork() {
        server.stop(0)
        val e = assertThrows(GitLabException::class.java) { client().fetchMergeRequests(false, false) }
        assertEquals(ErrorKind.NETWORK, e.kind)
        assertFalse(e.message.orEmpty().contains(TOKEN))
    }
}
