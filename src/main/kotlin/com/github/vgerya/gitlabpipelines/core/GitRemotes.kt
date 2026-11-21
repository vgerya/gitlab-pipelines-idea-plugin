package com.github.vgerya.gitlabpipelines.core

import java.net.URI
import java.net.URISyntaxException

/**
 * Maps git remote URLs of a local repository to GitLab project full paths (pure logic).
 *
 * Supported forms: `git@host:group/sub/proj.git`, `host:group/proj`, `ssh://git@host:2222/group/proj.git`,
 * `git+ssh://…`, `git://…`, `http(s)://[user@]host[:port][/base]/group/proj[.git][/]`.
 */
object GitRemotes {

    /** Host (lower-case) and path (no leading/trailing `/`, no `.git`) of a remote URL. */
    data class Parsed(val scheme: String, val host: String, val path: String)

    private val SCP_LIKE = Regex("^(?:[^@/\\s]+@)?([^:/\\s]+):(?!//)(.+)$")
    private val SSH_SCHEMES = setOf("ssh", "git+ssh", "ssh+git", "git")
    private val HTTP_SCHEMES = setOf("http", "https")
    private val INLINE_COMMENT = Regex("\\s[#;]")

    fun parse(remoteUrl: String?): Parsed? {
        val s = remoteUrl?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.contains("://")) {
            val uri = try {
                URI(s)
            } catch (e: URISyntaxException) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme !in SSH_SCHEMES && scheme !in HTTP_SCHEMES) return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val path = cleanPath(uri.path ?: return null) ?: return null
            return Parsed(scheme, host, path)
        }
        val m = SCP_LIKE.matchEntire(s) ?: return null
        val host = m.groupValues[1].lowercase()
        // "C:\repo" or "C:/repo" is a local Windows path, not a host
        if (host.length == 1) return null
        val path = cleanPath(m.groupValues[2]) ?: return null
        return Parsed("ssh", host, path)
    }

    private fun cleanPath(raw: String): String? {
        var p = raw.trim().trim('/')
        if (p.endsWith(".git", ignoreCase = true)) p = p.substring(0, p.length - 4)
        p = p.trim('/')
        if (p.isEmpty() || !p.contains('/')) return null
        return p
    }

    /**
     * GitLab project full path for [remoteUrl] if it points to the GitLab at [baseUrl] (host compared
     * case-insensitively, ports ignored since SSH and HTTP ports differ). For http(s) remotes the
     * base URL sub-path (`/gitlab`) is stripped. Returns `null` for other hosts.
     */
    fun projectPath(remoteUrl: String?, baseUrl: String?): String? {
        val base = UrlUtil.normalizeBaseUrl(baseUrl) ?: return null
        val parsed = parse(remoteUrl) ?: return null
        val baseUri = URI(base)
        if (!parsed.host.equals(baseUri.host, ignoreCase = true)) return null
        val basePath = UrlUtil.basePath(base).trim('/')
        if (parsed.scheme in HTTP_SCHEMES && basePath.isNotEmpty()) {
            val prefix = "$basePath/"
            if (!parsed.path.startsWith(prefix, ignoreCase = true)) return null
            return parsed.path.substring(prefix.length).takeIf { it.contains('/') }
        }
        return parsed.path
    }

    /** Lower-cased project paths of all [remoteUrls] that belong to [baseUrl]. */
    fun projectPaths(remoteUrls: Collection<String>, baseUrl: String?): Set<String> =
        remoteUrls.mapNotNullTo(LinkedHashSet()) { projectPath(it, baseUrl)?.lowercase() }

    /** MRs whose project path is in [projectPaths] (compared case-insensitively). */
    fun filterByProjects(mrs: List<MergeRequest>, projectPaths: Set<String>): List<MergeRequest> {
        val wanted = projectPaths.mapTo(HashSet()) { it.lowercase() }
        return mrs.filter { it.projectPath.lowercase() in wanted }
    }

    /** `url` and `pushurl` values of all `[remote "…"]` sections of a `.git/config` file. */
    fun remoteUrlsFromGitConfig(text: String): List<String> {
        val result = ArrayList<String>()
        var inRemote = false
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[")) {
                val header = line.substringAfter('[').substringBefore(']').trim()
                val section = header.substringBefore(' ').substringBefore('.').trim()
                inRemote = section.equals("remote", ignoreCase = true)
                continue
            }
            if (!inRemote) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (!key.equals("url", ignoreCase = true) && !key.equals("pushurl", ignoreCase = true)) continue
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1)
            } else {
                // unquoted values may carry an inline comment: `url = git@host:g/p.git # fork`
                val comment = INLINE_COMMENT.find(value)
                if (comment != null) value = value.substring(0, comment.range.first).trim()
            }
            if (value.isNotEmpty() && value !in result) result += value
        }
        return result
    }
}
