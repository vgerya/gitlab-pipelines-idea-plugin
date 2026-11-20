package com.github.vgerya.gitlabpipelines.core

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

enum class FetchSource { GRAPHQL, REST }

data class FetchResult(val mrs: List<MergeRequest>, val source: FetchSource)

/**
 * GitLab API client: GraphQL first, REST v4 as a fallback.
 *
 * The token is sent only in headers (`Authorization: Bearer` for GraphQL, `PRIVATE-TOKEN` for
 * REST); it never appears in URLs or exception messages.
 */
class GitLabClient(baseUrl: String, token: String, private val transport: HttpTransport) {

    private val baseUrl: String = UrlUtil.normalizeBaseUrl(baseUrl)
        ?: throw GitLabException(ErrorKind.NOT_CONFIGURED, message = "GitLab URL is not configured or invalid")
    private val token: String = token.trim().also {
        if (it.isEmpty()) throw GitLabException(ErrorKind.NOT_CONFIGURED, message = "GitLab token is not configured")
    }

    // ------------------------------------------------------------------ public API

    fun getUser(): User = Json.parseUser(restGet("$baseUrl/api/v4/user").body)

    fun fetchMergeRequests(includeAssigned: Boolean, includeReview: Boolean): FetchResult {
        return try {
            FetchResult(fetchGraphQl(includeAssigned, includeReview), FetchSource.GRAPHQL)
        } catch (e: GitLabException) {
            if (e.kind in FALLBACK_KINDS) {
                FetchResult(fetchRest(includeAssigned, includeReview), FetchSource.REST)
            } else {
                throw e
            }
        }
    }

    // ------------------------------------------------------------------ GraphQL

    internal fun fetchGraphQl(includeAssigned: Boolean, includeReview: Boolean): List<MergeRequest> {
        val lists = buildList {
            add("authoredMergeRequests" to Role.AUTHOR)
            if (includeAssigned) add("assignedMergeRequests" to Role.ASSIGNEE)
            if (includeReview) add("reviewRequestedMergeRequests" to Role.REVIEWER)
        }
        val merged = LinkedHashMap<String, MergeRequest>()
        for ((field, role) in lists) {
            var cursor: String? = null
            var page = 0
            while (page < MAX_PAGES) {
                page++
                val body = graphQlBody(field, cursor)
                val response = send(
                    HttpRequestSpec(
                        method = "POST",
                        url = "$baseUrl/api/graphql",
                        headers = mapOf(
                            "Authorization" to "Bearer $token",
                            "Content-Type" to "application/json",
                            "Accept" to "application/json",
                        ),
                        body = body,
                    ),
                )
                val parsed = Json.parseGraphQlPage(response.body, field, baseUrl, role)
                parsed.mergeRequests.forEach { mergeInto(merged, it) }
                if (!parsed.hasNextPage || parsed.endCursor.isNullOrEmpty()) break
                cursor = parsed.endCursor
            }
        }
        return merged.values.toList()
    }

    private fun graphQlBody(field: String, cursor: String?): String {
        val variables = JsonObject()
        if (cursor == null) variables.add("after", JsonNull.INSTANCE) else variables.addProperty("after", cursor)
        val root = JsonObject()
        root.addProperty("query", graphQlQuery(field))
        root.add("variables", variables)
        return root.toString()
    }

    // ------------------------------------------------------------------ REST

    internal fun fetchRest(includeAssigned: Boolean, includeReview: Boolean): List<MergeRequest> {
        val user = getUser()
        val scopes = buildList {
            add("scope=created_by_me" to Role.AUTHOR)
            if (includeAssigned) add("scope=assigned_to_me" to Role.ASSIGNEE)
            if (includeReview) {
                add("scope=all&reviewer_username=${UrlUtil.encodeQueryParam(user.username)}" to Role.REVIEWER)
            }
        }
        val stubs = LinkedHashMap<String, RestMrStub>()
        for ((scope, role) in scopes) {
            var page = 1
            var count = 0
            while (count < MAX_PAGES) {
                count++
                val url = "$baseUrl/api/v4/merge_requests?$scope&state=opened&per_page=100&page=$page"
                val response = restGet(url)
                Json.parseRestMergeRequestList(response.body, baseUrl, role).forEach { stub ->
                    val existing = stubs[stub.mergeRequest.key]
                    stubs[stub.mergeRequest.key] = if (existing == null) {
                        stub
                    } else {
                        existing.copy(
                            mergeRequest = existing.mergeRequest.copy(
                                roles = existing.mergeRequest.roles + stub.mergeRequest.roles,
                            ),
                        )
                    }
                }
                val next = response.header("X-Next-Page")?.trim()?.toIntOrNull() ?: break
                if (next <= page) break
                page = next
            }
        }
        return loadDetails(stubs.values.toList())
    }

    private fun loadDetails(stubs: List<RestMrStub>): List<MergeRequest> {
        if (stubs.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(MAX_PARALLEL_DETAILS, stubs.size), DaemonThreadFactory)
        try {
            val futures: List<Future<MergeRequest>> = stubs.map { stub ->
                pool.submit<MergeRequest> { loadDetail(stub) }
            }
            return futures.map { f ->
                try {
                    f.get()
                } catch (e: ExecutionException) {
                    val cause = e.cause
                    if (cause is GitLabException) throw cause
                    throw GitLabException(ErrorKind.BAD_RESPONSE, message = "Failed to load merge request", cause = cause)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw GitLabException(ErrorKind.NETWORK, message = "Interrupted", cause = e)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun loadDetail(stub: RestMrStub): MergeRequest {
        val url = "$baseUrl/api/v4/projects/${stub.projectId}/merge_requests/${stub.iid}"
        val pipeline = try {
            Json.parseRestHeadPipeline(restGet(url).body)
        } catch (e: GitLabException) {
            // MR disappeared between the list and the detail call (merged/deleted): no pipeline.
            if (e.kind == ErrorKind.BAD_RESPONSE && e.httpStatus == 404) null else throw e
        }
        return stub.mergeRequest.copy(pipeline = pipeline)
    }

    // ------------------------------------------------------------------ HTTP

    private fun restGet(url: String): HttpResponseSpec = send(
        HttpRequestSpec(
            method = "GET",
            url = url,
            headers = mapOf("PRIVATE-TOKEN" to token, "Accept" to "application/json"),
        ),
    )

    private fun send(req: HttpRequestSpec): HttpResponseSpec {
        val response = try {
            transport.send(req)
        } catch (e: IOException) {
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            throw GitLabException(ErrorKind.NETWORK, message = sanitize(reason), cause = e)
        }
        checkStatus(response)
        return response
    }

    private fun checkStatus(response: HttpResponseSpec) {
        val status = response.status
        when {
            status in 200..299 -> return
            status == 401 -> throw GitLabException(ErrorKind.AUTH, httpStatus = status)
            status == 403 -> throw GitLabException(ErrorKind.FORBIDDEN, httpStatus = status)
            status == 429 -> {
                val retry = response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                    ?: DEFAULT_RETRY_AFTER_SEC
                throw GitLabException(ErrorKind.RATE_LIMIT, httpStatus = status, retryAfterSec = retry)
            }
            status >= 500 -> throw GitLabException(ErrorKind.SERVER, httpStatus = status)
            else -> throw GitLabException(ErrorKind.BAD_RESPONSE, httpStatus = status)
        }
    }

    /** Defensive: make sure the token never leaks into a message. */
    private fun sanitize(message: String): String = message.replace(token, "***")

    companion object {
        const val MAX_PAGES = 5
        const val MAX_PARALLEL_DETAILS = 4
        const val DEFAULT_RETRY_AFTER_SEC = 60L

        private val FALLBACK_KINDS = setOf(ErrorKind.GRAPHQL, ErrorKind.BAD_RESPONSE, ErrorKind.SERVER)

        private fun mergeInto(target: MutableMap<String, MergeRequest>, mr: MergeRequest) {
            val existing = target[mr.key]
            target[mr.key] = if (existing == null) mr else existing.copy(roles = existing.roles + mr.roles)
        }

        fun graphQlQuery(field: String): String = """
            query(${'$'}after: String) {
              currentUser {
                username
                $field(state: opened, first: 100, after: ${'$'}after) {
                  pageInfo { hasNextPage endCursor }
                  nodes {
                    id iid title webUrl draft sourceBranch updatedAt
                    project { fullPath }
                    headPipeline {
                      id iid status path updatedAt finishedAt
                      detailedStatus { text label group icon detailsPath }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        private object DaemonThreadFactory : ThreadFactory {
            private val counter = AtomicInteger()
            override fun newThread(r: Runnable): Thread =
                Thread(r, "GitLabClient-rest-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}
