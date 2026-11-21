package com.github.vgerya.gitlabpipelines.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitRemotesTest {

    private val base = "https://gitlab.example.com"

    @Test
    fun scpLikeSsh() {
        assertEquals("group/sub/proj", GitRemotes.projectPath("git@gitlab.example.com:group/sub/proj.git", base))
        assertEquals("group/proj", GitRemotes.projectPath("gitlab.example.com:group/proj", base))
        assertEquals("group/proj", GitRemotes.projectPath("git@gitlab.example.com:/group/proj.git/", base))
    }

    @Test
    fun sshUrlWithPort() {
        assertEquals("group/proj", GitRemotes.projectPath("ssh://git@gitlab.example.com:2222/group/proj.git", base))
        assertEquals("group/proj", GitRemotes.projectPath("git+ssh://git@gitlab.example.com/group/proj", base))
    }

    @Test
    fun httpsWithAndWithoutGitSuffix() {
        assertEquals("group/proj", GitRemotes.projectPath("https://gitlab.example.com/group/proj.git", base))
        assertEquals("group/proj", GitRemotes.projectPath("https://gitlab.example.com/group/proj", base))
        assertEquals("group/proj", GitRemotes.projectPath("https://gitlab.example.com/group/proj/", base))
        assertEquals("group/proj", GitRemotes.projectPath("https://user:pw@gitlab.example.com:8443/group/proj.git", base))
    }

    @Test
    fun basePathIsStrippedForHttp() {
        val sub = "https://host.example.com/gitlab/"
        assertEquals("group/proj", GitRemotes.projectPath("https://host.example.com/gitlab/group/proj.git", sub))
        assertEquals("group/proj", GitRemotes.projectPath("https://HOST.example.com/GitLab/group/proj", sub))
        // outside the installation sub-path
        assertNull(GitRemotes.projectPath("https://host.example.com/group/proj.git", sub))
        // ssh paths never contain the relative URL root
        assertEquals("group/proj", GitRemotes.projectPath("git@host.example.com:group/proj.git", sub))
    }

    @Test
    fun hostIsComparedCaseInsensitively() {
        assertEquals("Group/Proj", GitRemotes.projectPath("git@GitLab.Example.COM:Group/Proj.git", base))
        assertEquals("group/proj", GitRemotes.projectPath("https://gitlab.example.com/group/proj", "HTTPS://GITLAB.EXAMPLE.COM/"))
    }

    @Test
    fun otherHostsAndGarbageGiveNull() {
        assertNull(GitRemotes.projectPath("git@github.com:group/proj.git", base))
        assertNull(GitRemotes.projectPath("https://gitlab.other.com/group/proj.git", base))
        assertNull(GitRemotes.projectPath("C:\\repos\\proj", base))
        assertNull(GitRemotes.projectPath("/home/me/repo.git", base))
        assertNull(GitRemotes.projectPath("", base))
        assertNull(GitRemotes.projectPath(null, base))
        assertNull(GitRemotes.projectPath("https://gitlab.example.com/proj.git", base)) // no namespace
        assertNull(GitRemotes.projectPath("git@gitlab.example.com:group/proj.git", ""))
        assertNull(GitRemotes.projectPath("file:///srv/git/group/proj.git", base))
    }

    @Test
    fun projectPathsAreLowerCasedAndFilterMatches() {
        val paths = GitRemotes.projectPaths(
            listOf("git@gitlab.example.com:Group/Alpha.git", "git@github.com:x/y.git", "https://gitlab.example.com/group/beta"),
            base,
        )
        assertEquals(setOf("group/alpha", "group/beta"), paths)
        val a = mr(1).copy(projectPath = "group/alpha")
        val b = mr(2).copy(projectPath = "group/gamma")
        val c = mr(3).copy(projectPath = "GROUP/BETA")
        assertEquals(listOf(a, c), GitRemotes.filterByProjects(listOf(a, b, c), paths))
        assertEquals(emptyList<MergeRequest>(), GitRemotes.filterByProjects(listOf(a, b, c), emptySet()))
    }

    @Test
    fun gitConfigParsing() {
        val config = """
            [core]
            	repositoryformatversion = 0
            	url = not-a-remote
            [remote "origin"]
            	url = git@gitlab.example.com:group/alpha.git
            	fetch = +refs/heads/*:refs/remotes/origin/*
            # comment
            [remote "upstream"]
            	URL = "https://gitlab.example.com/group/upstream.git"
            	pushurl = ssh://git@gitlab.example.com:2222/group/push.git
            [branch "main"]
            	remote = origin
            	url = ignored
        """.trimIndent()
        assertEquals(
            listOf(
                "git@gitlab.example.com:group/alpha.git",
                "https://gitlab.example.com/group/upstream.git",
                "ssh://git@gitlab.example.com:2222/group/push.git",
            ),
            GitRemotes.remoteUrlsFromGitConfig(config),
        )
        assertEquals(emptyList<String>(), GitRemotes.remoteUrlsFromGitConfig(""))
    }
}
