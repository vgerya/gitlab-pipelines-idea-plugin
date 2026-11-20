package com.github.vgerya.gitlabpipelines.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** One page of a GraphQL MR list. */
data class GraphQlPage(
    val username: String?,
    val mergeRequests: List<MergeRequest>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)

/** A merge request from the REST list endpoint, before its detail (head pipeline) is loaded. */
data class RestMrStub(
    val id: Long,
    val iid: Int,
    val projectId: Long,
    val mergeRequest: MergeRequest,
)

/** Gson-based parsing of GitLab responses into the core model. */
object Json {

    // ---------------------------------------------------------------- generic helpers

    fun parseObject(body: String): JsonObject {
        val element = parse(body)
        if (!element.isJsonObject) throw badResponse("Expected JSON object")
        return element.asJsonObject
    }

    fun parseArray(body: String): JsonArray {
        val element = parse(body)
        if (!element.isJsonArray) throw badResponse("Expected JSON array")
        return element.asJsonArray
    }

    private fun parse(body: String): JsonElement {
        try {
            return JsonParser.parseString(body)
        } catch (e: JsonParseException) {
            throw GitLabException(ErrorKind.BAD_RESPONSE, message = "Response is not valid JSON", cause = e)
        } catch (e: IllegalStateException) {
            throw GitLabException(ErrorKind.BAD_RESPONSE, message = "Response is not valid JSON", cause = e)
        }
    }

    fun JsonObject.member(name: String): JsonElement? {
        val e = get(name) ?: return null
        return if (e.isJsonNull) null else e
    }

    fun JsonObject.obj(name: String): JsonObject? = member(name)?.takeIf { it.isJsonObject }?.asJsonObject

    fun JsonObject.arr(name: String): JsonArray? = member(name)?.takeIf { it.isJsonArray }?.asJsonArray

    fun JsonObject.str(name: String): String? =
        member(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString

    fun JsonObject.bool(name: String): Boolean? {
        val p = member(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            p.isBoolean -> p.asBoolean
            p.isString -> p.asString.toBooleanStrictOrNull()
            else -> null
        }
    }

    fun JsonObject.long(name: String): Long? {
        val p = member(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            p.isNumber -> try {
                p.asLong
            } catch (e: NumberFormatException) {
                null
            }
            p.isString -> p.asString.trim().toLongOrNull()
            else -> null
        }
    }

    /** Null-safe ISO-8601 timestamp parsing (`Z` or offset); invalid values give `null`. */
    fun parseInstant(s: String?): Instant? {
        if (s.isNullOrBlank()) return null
        return try {
            Instant.parse(s)
        } catch (e: DateTimeParseException) {
            try {
                OffsetDateTime.parse(s).toInstant()
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }

    private fun badResponse(msg: String) = GitLabException(ErrorKind.BAD_RESPONSE, message = msg)

    // ---------------------------------------------------------------- user

    fun parseUser(body: String): User {
        val o = parseObject(body)
        val id = o.long("id") ?: throw badResponse("User without id")
        val username = o.str("username") ?: throw badResponse("User without username")
        return User(id, username, o.str("name") ?: username)
    }

    // ---------------------------------------------------------------- GraphQL

    /**
     * Parses a GraphQL response for one list field (`authoredMergeRequests`, ...).
     * `errors[]` → [ErrorKind.GRAPHQL]; `currentUser: null` → [ErrorKind.AUTH];
     * unexpected shape → [ErrorKind.BAD_RESPONSE].
     */
    fun parseGraphQlPage(body: String, listField: String, baseUrl: String, role: Role): GraphQlPage {
        val root = parseObject(body)
        val errors = root.arr("errors")
        if (errors != null && errors.size() > 0) {
            val first = errors[0].takeIf { it.isJsonObject }?.asJsonObject?.str("message")
            throw GitLabException(ErrorKind.GRAPHQL, message = "GraphQL error: ${first ?: "unknown"}")
        }
        val data = root.obj("data") ?: throw badResponse("GraphQL response without data")
        if (!data.has("currentUser")) throw badResponse("GraphQL response without currentUser")
        val user = data.obj("currentUser")
            ?: throw GitLabException(ErrorKind.AUTH, message = "GraphQL currentUser is null (not authenticated)")
        val list = user.obj(listField) ?: throw badResponse("GraphQL response without $listField")
        val pageInfo = list.obj("pageInfo")
        val nodes = list.arr("nodes") ?: throw badResponse("GraphQL response without nodes")
        val mrs = nodes.mapNotNull { n ->
            if (n.isJsonObject) parseGraphQlMergeRequest(n.asJsonObject, baseUrl, role) else null
        }
        return GraphQlPage(
            username = user.str("username"),
            mergeRequests = mrs,
            hasNextPage = pageInfo?.bool("hasNextPage") ?: false,
            endCursor = pageInfo?.str("endCursor"),
        )
    }

    fun parseGraphQlMergeRequest(n: JsonObject, baseUrl: String, role: Role): MergeRequest? {
        val id = n.str("id") ?: return null
        val iid = n.str("iid")?.trim()?.toIntOrNull() ?: return null
        val pipeline = n.obj("headPipeline")?.let { parseGraphQlPipeline(it, baseUrl) }
        return MergeRequest(
            key = id,
            iid = iid,
            title = n.str("title").orEmpty(),
            webUrl = n.str("webUrl").orEmpty(),
            draft = n.bool("draft") ?: false,
            sourceBranch = n.str("sourceBranch").orEmpty(),
            projectPath = n.obj("project")?.str("fullPath").orEmpty(),
            updatedAt = parseInstant(n.str("updatedAt")),
            roles = setOf(role),
            pipeline = pipeline,
        )
    }

    fun parseGraphQlPipeline(p: JsonObject, baseUrl: String): Pipeline? {
        val id = p.str("id") ?: return null
        val raw = p.str("status").orEmpty().lowercase()
        val detailed = p.obj("detailedStatus")
        return Pipeline(
            id = id,
            iid = p.str("iid"),
            status = PipelineStatus.parse(raw),
            rawStatus = raw,
            label = detailed?.str("label") ?: detailed?.str("text"),
            webUrl = p.str("path")?.takeIf { it.isNotBlank() }?.let { UrlUtil.joinUrl(baseUrl, it) },
            updatedAt = parseInstant(p.str("updatedAt")),
            finishedAt = parseInstant(p.str("finishedAt")),
        )
    }

    // ---------------------------------------------------------------- REST

    fun parseRestMergeRequestList(body: String, baseUrl: String, role: Role): List<RestMrStub> {
        val arr = parseArray(body)
        return arr.mapNotNull { e ->
            if (e.isJsonObject) parseRestMergeRequestStub(e.asJsonObject, baseUrl, role) else null
        }
    }

    fun parseRestMergeRequestStub(o: JsonObject, baseUrl: String, role: Role): RestMrStub? {
        val id = o.long("id") ?: return null
        val iid = o.long("iid")?.toInt() ?: return null
        val projectId = o.long("project_id") ?: return null
        val webUrl = o.str("web_url").orEmpty()
        val projectPath = UrlUtil.projectPathFromRest(o.obj("references")?.str("full"), webUrl, baseUrl).orEmpty()
        val mr = MergeRequest(
            key = GlobalIds.mergeRequest(id),
            iid = iid,
            title = o.str("title").orEmpty(),
            webUrl = webUrl,
            draft = o.bool("draft") ?: o.bool("work_in_progress") ?: false,
            sourceBranch = o.str("source_branch").orEmpty(),
            projectPath = projectPath,
            updatedAt = parseInstant(o.str("updated_at")),
            roles = setOf(role),
            pipeline = null,
        )
        return RestMrStub(id, iid, projectId, mr)
    }

    /** Parses `GET /projects/:id/merge_requests/:iid` and returns its head pipeline (or `null`). */
    fun parseRestHeadPipeline(body: String): Pipeline? {
        val o = parseObject(body)
        val p = if (o.has("head_pipeline")) o.obj("head_pipeline") else o.obj("pipeline")
        return p?.let { parseRestPipeline(it) }
    }

    fun parseRestPipeline(p: JsonObject): Pipeline? {
        val id = p.long("id") ?: return null
        val raw = p.str("status").orEmpty().lowercase()
        val detailed = p.obj("detailed_status")
        return Pipeline(
            id = GlobalIds.pipeline(id),
            iid = p.str("iid"),
            status = PipelineStatus.parse(raw),
            rawStatus = raw,
            label = detailed?.str("label") ?: detailed?.str("text"),
            webUrl = p.str("web_url")?.takeIf { it.isNotBlank() },
            updatedAt = parseInstant(p.str("updated_at")),
            finishedAt = parseInstant(p.str("finished_at")),
        )
    }
}
