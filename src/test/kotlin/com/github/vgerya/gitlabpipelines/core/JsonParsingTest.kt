package com.github.vgerya.gitlabpipelines.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonParsingTest {

    private val expected = expectedFixtureMergeRequests().associateBy { it.iid }

    @Test
    fun graphQlFirstPage() {
        val page = Json.parseGraphQlPage(Fixtures.load("graphql_page1.json"), "authoredMergeRequests", BASE, Role.AUTHOR)
        assertEquals("jdoe", page.username)
        assertTrue(page.hasNextPage)
        assertEquals("cursor-1", page.endCursor)
        assertEquals(2, page.mergeRequests.size)
        assertEquals(expected.getValue(11), page.mergeRequests[0])
        assertEquals(expected.getValue(12).copy(roles = setOf(Role.AUTHOR)), page.mergeRequests[1])
    }

    @Test
    fun graphQlNullHeadPipelineAndDraft() {
        val page = Json.parseGraphQlPage(Fixtures.load("graphql_page2.json"), "authoredMergeRequests", BASE, Role.AUTHOR)
        assertFalse(page.hasNextPage)
        val mr = page.mergeRequests.single()
        assertNull(mr.pipeline)
        assertTrue(mr.draft)
        assertEquals(Category.NONE, mr.category)
        assertEquals(expected.getValue(13), mr)
    }

    @Test
    fun graphQlPipelineUrlUnderSubpath() {
        val body = """
            {"data":{"currentUser":{"username":"u","authoredMergeRequests":{
              "pageInfo":{"hasNextPage":false,"endCursor":null},
              "nodes":[{"id":"gid://gitlab/MergeRequest/1","iid":"7","title":"t","webUrl":"https://h/gitlab/g/p/-/merge_requests/7",
                "sourceBranch":"b","project":{"fullPath":"g/p"},
                "headPipeline":{"id":"gid://gitlab/Ci::Pipeline/5","iid":"2","status":"SUCCESS","path":"/gitlab/g/p/-/pipelines/5",
                   "detailedStatus":{"text":"Passed","label":"passed"}}}]}}}}
        """.trimIndent()
        val mr = Json.parseGraphQlPage(body, "authoredMergeRequests", "https://h/gitlab", Role.AUTHOR).mergeRequests.single()
        assertEquals("https://h/gitlab/g/p/-/pipelines/5", mr.pipeline?.webUrl)
        // missing optional fields
        assertFalse(mr.draft)
        assertNull(mr.updatedAt)
        assertNull(mr.pipeline?.updatedAt)
        assertNull(mr.pipeline?.finishedAt)
        assertEquals("success", mr.pipeline?.rawStatus)
        assertEquals(PipelineStatus.SUCCESS, mr.pipeline?.status)
    }

    @Test
    fun graphQlUnknownStatusKeepsRaw() {
        val body = """
            {"data":{"currentUser":{"authoredMergeRequests":{"pageInfo":{"hasNextPage":false},
              "nodes":[{"id":"gid://gitlab/MergeRequest/1","iid":"1","headPipeline":{"id":"gid://gitlab/Ci::Pipeline/1","status":"BRAND_NEW"}}]}}}}
        """.trimIndent()
        val p = Json.parseGraphQlPage(body, "authoredMergeRequests", BASE, Role.AUTHOR).mergeRequests.single().pipeline!!
        assertEquals(PipelineStatus.UNKNOWN, p.status)
        assertEquals("brand_new", p.rawStatus)
        assertNull(p.webUrl)
        assertNull(p.label)
    }

    @Test
    fun graphQlErrors() {
        val e = assertThrows(GitLabException::class.java) {
            Json.parseGraphQlPage(Fixtures.load("graphql_errors.json"), "authoredMergeRequests", BASE, Role.AUTHOR)
        }
        assertEquals(ErrorKind.GRAPHQL, e.kind)
    }

    @Test
    fun graphQlNoCurrentUserIsAuth() {
        val e = assertThrows(GitLabException::class.java) {
            Json.parseGraphQlPage(Fixtures.load("graphql_no_user.json"), "authoredMergeRequests", BASE, Role.AUTHOR)
        }
        assertEquals(ErrorKind.AUTH, e.kind)
    }

    @Test
    fun graphQlBadShapes() {
        for (body in listOf("<html>", "", "[]", """{"data":{}}""", """{"data":{"currentUser":{"username":"x"}}}""")) {
            val e = assertThrows(GitLabException::class.java) {
                Json.parseGraphQlPage(body, "authoredMergeRequests", BASE, Role.AUTHOR)
            }
            assertEquals(ErrorKind.BAD_RESPONSE, e.kind, "body: $body")
        }
    }

    @Test
    fun restUser() {
        assertEquals(User(42, "jdoe", "John Doe"), Json.parseUser(Fixtures.load("rest_user.json")))
    }

    @Test
    fun restListAndDetail() {
        val stubs = Json.parseRestMergeRequestList(Fixtures.load("rest_authored_page1.json"), BASE, Role.AUTHOR)
        assertEquals(listOf(11, 12), stubs.map { it.iid })
        assertEquals(listOf(7L, 8L), stubs.map { it.projectId })
        val first = stubs[0].mergeRequest.copy(pipeline = Json.parseRestHeadPipeline(Fixtures.load("rest_mr_11.json")))
        assertEquals(expected.getValue(11), first)
    }

    @Test
    fun restLegacyWorkInProgressAndNullPipeline() {
        val stub = Json.parseRestMergeRequestList(Fixtures.load("rest_authored_page2.json"), BASE, Role.AUTHOR).single()
        assertTrue(stub.mergeRequest.draft)
        assertNull(Json.parseRestHeadPipeline(Fixtures.load("rest_mr_13.json")))
    }

    @Test
    fun restProjectPathFallsBackToWebUrl() {
        val stubs = Json.parseRestMergeRequestList(Fixtures.load("rest_assigned.json"), BASE, Role.ASSIGNEE)
        val mr14 = stubs.single { it.iid == 14 }.mergeRequest
        assertEquals("group/sub/beta", mr14.projectPath)
    }

    @Test
    fun restMissingOptionalFields() {
        val stub = Json.parseRestMergeRequestList("""[{"id":1,"iid":2,"project_id":3}]""", BASE, Role.AUTHOR).single()
        assertEquals("gid://gitlab/MergeRequest/1", stub.mergeRequest.key)
        assertFalse(stub.mergeRequest.draft)
        assertEquals("", stub.mergeRequest.title)
        assertNull(stub.mergeRequest.updatedAt)
        val p = Json.parseRestHeadPipeline("""{"head_pipeline":{"id":9,"status":"canceled"}}""")!!
        assertEquals("gid://gitlab/Ci::Pipeline/9", p.id)
        assertEquals(PipelineStatus.CANCELED, p.status)
        assertNull(p.finishedAt)
        assertNull(p.webUrl)
    }

    @Test
    fun timestamps() {
        assertEquals(ts("2024-05-01T10:00:00Z"), Json.parseInstant("2024-05-01T10:00:00.000Z"))
        assertEquals(ts("2024-05-01T07:00:00Z"), Json.parseInstant("2024-05-01T10:00:00+03:00"))
        assertNull(Json.parseInstant(null))
        assertNull(Json.parseInstant(""))
        assertNull(Json.parseInstant("yesterday"))
    }
}
