package com.github.vgerya.gitlabpipelines.core

import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.util.Collections

const val BASE = "https://gitlab.example.com"
const val TOKEN = "glpat-SECRET-token-123"

object Fixtures {
    fun load(name: String): String {
        val stream = Fixtures::class.java.getResourceAsStream("/fixtures/$name")
            ?: throw IllegalStateException("Fixture not found: $name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

fun response(status: Int, body: String = "", headers: Map<String, List<String>> = emptyMap()) =
    HttpResponseSpec(status, headers, body)

/**
 * In-memory GitLab used by the fake transport and by the HttpServer test. Fixture bodies may contain
 * [BASE]; it is replaced by [base] so the server test can run on a random port.
 */
class FakeGitLab(private val base: String = BASE) {

    enum class GraphQlMode { OK, ERRORS, NO_USER, HTTP_404, HTTP_500 }

    var graphQlMode = GraphQlMode.OK

    /** If set, every request returns this response (e.g. 401, 429). */
    var forced: HttpResponseSpec? = null

    private fun fixture(name: String) = Fixtures.load(name).replace(BASE, base)

    private fun json(name: String, headers: Map<String, List<String>> = emptyMap()) =
        response(200, fixture(name), headers + mapOf("Content-Type" to listOf("application/json")))

    fun handle(req: HttpRequestSpec): HttpResponseSpec {
        forced?.let { return it }
        val uri = URI(req.url)
        val path = uri.rawPath
        val query = uri.rawQuery.orEmpty()
        return when {
            req.method == "POST" && path.endsWith("/api/graphql") -> graphQl(req)
            req.method == "GET" && path.endsWith("/api/v4/user") -> json("rest_user.json")
            req.method == "GET" && path.endsWith("/api/v4/merge_requests") -> restList(query)
            req.method == "GET" && path.contains("/api/v4/projects/") -> {
                val iid = path.substringAfterLast("/merge_requests/")
                json("rest_mr_$iid.json")
            }
            else -> response(404, """{"message":"404 Not Found"}""")
        }
    }

    private fun graphQl(req: HttpRequestSpec): HttpResponseSpec {
        when (graphQlMode) {
            GraphQlMode.ERRORS -> return json("graphql_errors.json")
            GraphQlMode.NO_USER -> return json("graphql_no_user.json")
            GraphQlMode.HTTP_404 -> return response(404, "<html>not found</html>")
            GraphQlMode.HTTP_500 -> return response(500, "<html>oops</html>")
            GraphQlMode.OK -> Unit
        }
        val body = JsonParser.parseString(req.body ?: "{}").asJsonObject
        val q = body.get("query").asString
        val after = body.getAsJsonObject("variables")?.get("after")?.takeIf { !it.isJsonNull }?.asString
        return when {
            q.contains("authoredMergeRequests") && after == null -> json("graphql_page1.json")
            q.contains("authoredMergeRequests") && after == "cursor-1" -> json("graphql_page2.json")
            q.contains("assignedMergeRequests") -> json("graphql_assigned.json")
            q.contains("reviewRequestedMergeRequests") -> json("graphql_review.json")
            else -> response(400, """{"errors":[{"message":"bad"}]}""")
        }
    }

    private fun restList(query: String): HttpResponseSpec {
        val params = query.split('&').filter { it.isNotEmpty() }.associate {
            it.substringBefore('=') to it.substringAfter('=', "")
        }
        val page = params["page"] ?: "1"
        return when (params["scope"]) {
            "created_by_me" ->
                if (page == "1") {
                    json("rest_authored_page1.json", mapOf("X-Next-Page" to listOf("2")))
                } else {
                    json("rest_authored_page2.json", mapOf("X-Next-Page" to listOf("")))
                }
            "assigned_to_me" -> json("rest_assigned.json")
            "all" -> if (params["reviewer_username"] == "jdoe") json("rest_review.json") else response(200, "[]")
            else -> response(400, "{}")
        }
    }
}

/** Records every request; delegates to [handler]. */
class RecordingTransport(private val handler: (HttpRequestSpec) -> HttpResponseSpec) : HttpTransport {
    val requests: MutableList<HttpRequestSpec> = Collections.synchronizedList(ArrayList())

    override fun send(req: HttpRequestSpec): HttpResponseSpec {
        requests += req
        return handler(req)
    }

    fun snapshot(): List<HttpRequestSpec> = synchronized(requests) { requests.toList() }
}

class FailingTransport(private val message: String = "Connection refused") : HttpTransport {
    override fun send(req: HttpRequestSpec): HttpResponseSpec = throw IOException(message)
}

// -------------------------------------------------------------------- model builders

fun ts(s: String): Instant = Instant.parse(s)

fun pipeline(
    id: Long,
    status: PipelineStatus,
    finishedAt: Instant? = null,
    updatedAt: Instant? = finishedAt,
    webUrl: String? = "$BASE/g/p/-/pipelines/$id",
) = Pipeline(
    id = GlobalIds.pipeline(id),
    iid = id.toString(),
    status = status,
    rawStatus = status.name.lowercase(),
    label = status.name.lowercase(),
    webUrl = webUrl,
    updatedAt = updatedAt,
    finishedAt = finishedAt,
)

fun mr(
    id: Long,
    pipeline: Pipeline? = null,
    draft: Boolean = false,
    updatedAt: Instant? = null,
    roles: Set<Role> = setOf(Role.AUTHOR),
) = MergeRequest(
    key = GlobalIds.mergeRequest(id),
    iid = id.toInt(),
    title = "MR $id",
    webUrl = "$BASE/g/p/-/merge_requests/$id",
    draft = draft,
    sourceBranch = "branch-$id",
    projectPath = "g/p",
    updatedAt = updatedAt,
    roles = roles,
    pipeline = pipeline,
)

/** The five MRs described by the fixtures, as both API paths must produce them. */
fun expectedFixtureMergeRequests(base: String = BASE): List<MergeRequest> {
    fun p(id: Long, iid: Int, status: PipelineStatus, label: String, proj: String, upd: String, fin: String?) = Pipeline(
        id = "gid://gitlab/Ci::Pipeline/$id",
        iid = iid.toString(),
        status = status,
        rawStatus = status.name.lowercase(),
        label = label,
        webUrl = "$base/$proj/-/pipelines/$id",
        updatedAt = ts(upd),
        finishedAt = fin?.let { ts(it) },
    )
    fun m(id: Long, iid: Int, proj: String, title: String, branch: String, draft: Boolean, upd: String, roles: Set<Role>, pl: Pipeline?) =
        MergeRequest(
            key = "gid://gitlab/MergeRequest/$id",
            iid = iid,
            title = title,
            webUrl = "$base/$proj/-/merge_requests/$iid",
            draft = draft,
            sourceBranch = branch,
            projectPath = proj,
            updatedAt = ts(upd),
            roles = roles,
            pipeline = pl,
        )
    return listOf(
        m(9001, 11, "group/alpha", "Fix login", "fix-login", false, "2024-05-01T10:00:00Z", setOf(Role.AUTHOR),
            p(501, 31, PipelineStatus.FAILED, "failed", "group/alpha", "2024-05-01T10:05:00Z", "2024-05-01T10:05:00Z")),
        m(9002, 12, "group/sub/beta", "Add <b>feature</b> & more", "feature/x", false, "2024-05-02T09:00:00Z",
            setOf(Role.AUTHOR, Role.ASSIGNEE),
            p(502, 32, PipelineStatus.RUNNING, "running", "group/sub/beta", "2024-05-02T09:01:00Z", null)),
        m(9003, 13, "group/alpha", "WIP: refactor", "refactor", true, "2024-04-20T08:00:00Z", setOf(Role.AUTHOR), null),
        m(9004, 14, "group/sub/beta", "Bump deps", "deps", false, "2024-05-03T12:00:00Z", setOf(Role.ASSIGNEE),
            p(504, 34, PipelineStatus.SUCCESS, "passed", "group/sub/beta", "2024-05-03T12:10:00Z", "2024-05-03T12:10:00Z")),
        m(9005, 15, "group/alpha", "Review me", "review", false, "2024-05-04T07:00:00Z", setOf(Role.REVIEWER),
            p(505, 35, PipelineStatus.MANUAL, "manual action", "group/alpha", "2024-05-04T07:02:00Z", null)),
    )
}
