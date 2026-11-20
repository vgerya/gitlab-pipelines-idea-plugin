package com.github.vgerya.gitlabpipelines.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UrlUtilTest {

    @Test
    fun normalizeStripsTrailingSlash() {
        assertEquals("https://gitlab.com", UrlUtil.normalizeBaseUrl("https://gitlab.com/"))
        assertEquals("https://gitlab.com", UrlUtil.normalizeBaseUrl("  https://gitlab.com///  "))
    }

    @Test
    fun normalizeKeepsSubpathAndPort() {
        assertEquals("https://git.company.ru:8443/gitlab", UrlUtil.normalizeBaseUrl("https://git.company.ru:8443/gitlab/"))
        assertEquals("http://localhost:8080", UrlUtil.normalizeBaseUrl("http://localhost:8080"))
    }

    @Test
    fun normalizeLowercasesSchemeAndHostAndDropsQuery() {
        assertEquals("https://gitlab.example.com/sub", UrlUtil.normalizeBaseUrl("HTTPS://GitLab.Example.com/sub/?x=1#frag"))
    }

    @Test
    fun normalizeRejectsInvalid() {
        assertNull(UrlUtil.normalizeBaseUrl(null))
        assertNull(UrlUtil.normalizeBaseUrl(""))
        assertNull(UrlUtil.normalizeBaseUrl("   "))
        assertNull(UrlUtil.normalizeBaseUrl("ftp://gitlab.com"))
        assertNull(UrlUtil.normalizeBaseUrl("gitlab.com"))
        assertNull(UrlUtil.normalizeBaseUrl("https://"))
        assertNull(UrlUtil.normalizeBaseUrl("https://user:pass@gitlab.com"))
        assertNull(UrlUtil.normalizeBaseUrl("ht tp://bad url"))
    }

    @Test
    fun joinUrlWithoutSubpath() {
        assertEquals("https://h.com/g/p/-/pipelines/1", UrlUtil.joinUrl("https://h.com", "/g/p/-/pipelines/1"))
        assertEquals("https://h.com/g/p/-/pipelines/1", UrlUtil.joinUrl("https://h.com/", "g/p/-/pipelines/1"))
    }

    @Test
    fun joinUrlWithSubpathAlreadyInPath() {
        assertEquals(
            "https://h.com:8443/gitlab/g/p/-/pipelines/1",
            UrlUtil.joinUrl("https://h.com:8443/gitlab", "/gitlab/g/p/-/pipelines/1"),
        )
    }

    @Test
    fun joinUrlWithSubpathNotInPath() {
        assertEquals("https://h.com/gitlab/g/p/-/pipelines/1", UrlUtil.joinUrl("https://h.com/gitlab", "/g/p/-/pipelines/1"))
        // a group whose name merely starts with the base path is not the base path
        assertEquals("https://h.com/gitlab/gitlabx/p", UrlUtil.joinUrl("https://h.com/gitlab", "/gitlabx/p"))
    }

    @Test
    fun joinUrlKeepsAbsoluteUrls() {
        assertEquals("https://other/x", UrlUtil.joinUrl("https://h.com", "https://other/x"))
    }

    @Test
    fun projectPathFromReferences() {
        assertEquals("group/sub/proj", UrlUtil.projectPathFromRest("group/sub/proj!42", null, BASE))
    }

    @Test
    fun projectPathFromWebUrl() {
        assertEquals(
            "group/proj",
            UrlUtil.projectPathFromRest(null, "https://h.com/gitlab/group/proj/-/merge_requests/3", "https://h.com/gitlab"),
        )
        assertEquals(
            "gitlab/group/proj",
            UrlUtil.projectPathFromRest(null, "https://h.com/gitlab/group/proj/-/merge_requests/3", null),
        )
        assertEquals(
            "a/b",
            UrlUtil.projectPathFromRest("", "https://gitlab.example.com/a/b/-/merge_requests/1", BASE),
        )
    }

    @Test
    fun projectPathUnknown() {
        assertNull(UrlUtil.projectPathFromRest(null, null, BASE))
        assertNull(UrlUtil.projectPathFromRest(null, "https://h.com/whatever", BASE))
    }
}
