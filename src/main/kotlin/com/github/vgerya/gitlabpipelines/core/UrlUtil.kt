package com.github.vgerya.gitlabpipelines.core

import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

object UrlUtil {

    /**
     * Validates and normalises a GitLab base URL: `http(s)://host[:port][/path]`.
     * Trailing slashes, query and fragment are dropped, the sub-path is kept, scheme and host are
     * lower-cased. Returns `null` if the value is not a valid http(s) URL.
     */
    fun normalizeBaseUrl(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val uri = try {
            URI(s)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()
        if (host.isNullOrEmpty()) return null
        if (uri.rawUserInfo != null) return null
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val path = (uri.rawPath ?: "").trimEnd('/')
        if (path.isNotEmpty() && !path.startsWith("/")) return null
        return "$scheme://$host$port$path"
    }

    /** `scheme://host[:port]` of a (normalised) base URL. */
    fun origin(baseUrl: String): String {
        val uri = URI(baseUrl)
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port"
    }

    /** Path part of a (normalised) base URL, e.g. `/gitlab`, or empty string. */
    fun basePath(baseUrl: String): String = (URI(baseUrl).rawPath ?: "").trimEnd('/')

    /**
     * Joins a base URL and a path returned by GitLab. GitLab returns paths that already contain the
     * relative URL root of a sub-path installation (`/gitlab/group/p/-/pipelines/1`); in that case
     * the path is joined with the origin only. Absolute http(s) URLs are returned unchanged.
     */
    fun joinUrl(baseUrl: String, path: String): String {
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            return path
        }
        val base = baseUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        val bp = basePath(base)
        if (bp.isNotEmpty() && (p == bp || p.startsWith("$bp/"))) {
            return origin(base) + p
        }
        return base + p
    }

    /**
     * Project full path for a REST merge request: `references.full` minus trailing `!{iid}`,
     * fallback: `web_url` part before `/-/merge_requests/`, relative to [baseUrl].
     */
    fun projectPathFromRest(referencesFull: String?, webUrl: String?, baseUrl: String?): String? {
        if (!referencesFull.isNullOrBlank()) {
            val idx = referencesFull.lastIndexOf('!')
            val path = if (idx > 0) referencesFull.substring(0, idx) else referencesFull
            if (path.isNotBlank()) return path
        }
        if (webUrl.isNullOrBlank()) return null
        val marker = "/-/merge_requests/"
        val idx = webUrl.indexOf(marker)
        if (idx < 0) return null
        val prefix = webUrl.substring(0, idx)
        if (baseUrl != null) {
            val base = baseUrl.trimEnd('/')
            if (prefix.startsWith("$base/", ignoreCase = true)) {
                return prefix.substring(base.length + 1).ifBlank { null }
            }
        }
        val path = try {
            URI(prefix).rawPath
        } catch (e: URISyntaxException) {
            null
        } ?: return null
        return path.trim('/').ifBlank { null }
    }

    fun encodeQueryParam(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
