package com.github.vgerya.gitlabpipelines.core

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** QA gaps of v1.1: remotes edge cases, group ordering, recent-pipelines request bounds. */
class V11QaTest {

    private val base = "https://gitlab.example.com"

    // ------------------------------------------------------------------ GitRemotes

    @Test
    fun remotesWithUnusualButValidForms() {
        // upper-case suffix, trailing slash after .git, deep namespaces, ports on http
        assertEquals("a/b/c/d/proj", GitRemotes.projectPath("https://gitlab.example.com:443/a/b/c/d/proj.GIT/", base))
        assertEquals("a/b/c/proj", GitRemotes.projectPath("git@gitlab.example.com:a/b/c/proj.git", base))
        // dots and dashes in names, .git only stripped as a suffix
        assertEquals("my.group/proj.git-tools", GitRemotes.projectPath("git@gitlab.example.com:my.group/proj.git-tools.git", base))
        // base URL with a sub-path and a port: ports ignored, sub-path stripped for http only
        val sub = "https://gitlab.example.com:8443/gitlab"
        assertEquals("g/p", GitRemotes.projectPath("https://gitlab.example.com/gitlab/g/p", sub))
        assertEquals("g/p", GitRemotes.projectPath("ssh://git@gitlab.example.com:2222/g/p.git", sub))
        // the sub-path alone is not a project
        assertNull(GitRemotes.projectPath("https://gitlab.example.com/gitlab/p.git", sub))
        // prefix must match a whole path segment
        assertNull(GitRemotes.projectPath("https://gitlab.example.com/gitlabx/g/p.git", sub))
        // other host that merely ends with the GitLab host
        assertNull(GitRemotes.projectPath("git@evilgitlab.example.com:g/p.git", base))
    }

    @Test
    fun gitConfigInlineCommentsAndOldStyleSections() {
        val config = """
            [remote "my.fork"]
                url = git@gitlab.example.com:me/fork.git # my fork
                pushurl = https://gitlab.example.com/me/fork.git ; push
            [remote.legacy]
                url = git@gitlab.example.com:g/legacy.git
            [Remote "origin"]
                url = "git@gitlab.example.com:g/quoted.git"
                url = git@gitlab.example.com:me/fork.git
        """.trimIndent()
        assertEquals(
            listOf(
                "git@gitlab.example.com:me/fork.git",
                "https://gitlab.example.com/me/fork.git",
                "git@gitlab.example.com:g/legacy.git",
                "git@gitlab.example.com:g/quoted.git",
            ),
            GitRemotes.remoteUrlsFromGitConfig(config),
        )
    }

    // ------------------------------------------------------------------ MrTree

    private fun m(id: Long, status: PipelineStatus?, project: String) =
        mr(id, pipeline = status?.let { pipeline(id, it, updatedAt = ts("2024-05-01T10:00:00Z")) })
            .copy(projectPath = project)

    @Test
    fun groupsWithoutPipelinesStayLastInBothStatusDirections() {
        val list = listOf(
            m(1, null, "g/none"),
            m(2, PipelineStatus.FAILED, "g/failed"),
            m(3, PipelineStatus.SUCCESS, "g/ok"),
            m(4, null, "g/mixed"),
            m(5, PipelineStatus.RUNNING, "g/mixed"),
        )
        fun names(asc: Boolean) =
            MrTreeBuilder.build(list, Grouping.PROJECT, SortSpec(MrColumn.STATUS, asc)).map { (it as GroupRow).name }
        assertEquals(listOf("g/failed", "g/mixed", "g/ok", "g/none"), names(true))
        assertEquals(listOf("g/ok", "g/mixed", "g/failed", "g/none"), names(false))
        val mixed = MrTreeBuilder.build(list, Grouping.PROJECT, SortSpec.DEFAULT).first { (it as GroupRow).name == "g/mixed" }
        assertEquals(Category.RUNNING, (mixed as GroupRow).category)
        // MR without pipeline is last inside its group
        assertEquals(listOf(5, 4), mixed.children.map { (it as MergeRequestRow).mr.iid })
    }

    @Test
    fun buildIsDeterministicForEqualKeys() {
        val list = (1L..6L).map { m(it, PipelineStatus.SUCCESS, "g/p") }
        val expected = MrTreeBuilder.build(list, Grouping.NONE, SortSpec(MrColumn.PROJECT, true)).map { it.id }
        val reversed = MrTreeBuilder.build(list.reversed(), Grouping.NONE, SortSpec(MrColumn.PROJECT, true)).map { it.id }
        assertEquals(expected, reversed)
        // row ids are unique across the whole tree (selection / expansion restore relies on it)
        val tree = MrTreeBuilder.build(
            list + m(7, null, "g/q"),
            Grouping.PROJECT_BRANCH,
            SortSpec.DEFAULT,
            mapOf(list[0].key to listOf(pipeline(70, PipelineStatus.FAILED), pipeline(71, PipelineStatus.SUCCESS))),
        )
        val ids = MrTreeBuilder.flatten(tree).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    // ------------------------------------------------------------------ recent pipelines

    @Test
    fun recentPipelinesAreBoundedAndChunked() {
        val transport = RecordingTransport { response(200, """{"data":{"project":{"mergeRequests":{"nodes":[]}}}}""") }
        val many = (1L..70L).map { mr(it).copy(projectPath = if (it % 2 == 0L) "g/even" else "g/odd") }
        GitLabClient(BASE, TOKEN, transport).fetchRecentPipelines(many)
        val requests = transport.snapshot()
        val iids = requests.flatMap { r ->
            val vars = JsonParser.parseString(r.body).asJsonObject.getAsJsonObject("variables")
            assertTrue(vars.getAsJsonArray("iids").size() <= GitLabClient.PIPELINES_IIDS_PER_QUERY)
            vars.getAsJsonArray("iids").map { it.asString.toInt() }
        }
        assertEquals(GitLabClient.RECENT_PIPELINES_MAX_MRS, iids.size)
        assertEquals((1..GitLabClient.RECENT_PIPELINES_MAX_MRS).toSet(), iids.toSet())
        // 25 per project → 2 queries each
        assertEquals(4, requests.size)
    }

    @Test
    fun recentPipelinesQueryShape() {
        val q = GitLabClient.RECENT_PIPELINES_QUERY
        assertTrue(q.contains("\$iids: [String!]"), q)
        assertTrue(q.contains("mergeRequests(iids: \$iids, first: ${GitLabClient.PIPELINES_IIDS_PER_QUERY})"), q)
        assertTrue(q.contains("project(fullPath: \$fullPath)"), q)
        for (field in listOf("ref", "sha", "createdAt", "detailedStatus")) assertTrue(q.contains(field), field)
    }

    @Test
    fun restFallbackEncodesNestedProjectPathAndToleratesForbidden() {
        val transport = RecordingTransport { req ->
            when {
                req.url.endsWith("/api/graphql") -> response(500)
                req.url.contains("/merge_requests/1/") -> response(403)
                else -> response(200, "[]")
            }
        }
        val list = listOf(
            mr(1).copy(projectPath = "secret/proj"),
            mr(2).copy(projectPath = "top/sub.group/my-proj"),
        )
        val result = GitLabClient(BASE, TOKEN, transport).fetchRecentPipelines(list)
        assertEquals(emptyList<Pipeline>(), result[list[0].key])
        assertEquals(emptyList<Pipeline>(), result[list[1].key])
        val urls = transport.snapshot().filter { it.method == "GET" }.map { it.url }
        assertTrue(urls.contains("$BASE/api/v4/projects/top%2Fsub.group%2Fmy-proj/merge_requests/2/pipelines?per_page=10"), urls.toString())
    }
}
