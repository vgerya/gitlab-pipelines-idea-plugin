package com.github.vgerya.gitlabpipelines.plugin.remotes

import com.github.vgerya.gitlabpipelines.core.GitRemotes
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File

/**
 * Supplies git remote URLs of an IDE project. The Git4Idea-backed implementation is registered
 * only when the bundled Git plugin is enabled (optional dependency, `gitlab-pipelines-git.xml`).
 */
interface ProjectRemotesProvider {
    fun remoteUrls(project: Project): List<String>

    companion object {
        val EP_NAME: ExtensionPointName<ProjectRemotesProvider> =
            ExtensionPointName.create("com.github.vgerya.gitlabpipelines.remotesProvider")
    }
}

/** Collects remote URLs: registered providers first, `.git/config` files of the project as a fallback. */
object ProjectRemotes {

    private val LOG = Logger.getInstance(ProjectRemotes::class.java)

    /** May do file IO: call from a background thread. */
    fun collect(project: Project): List<String> {
        if (project.isDisposed) return emptyList()
        val fromProviders = LinkedHashSet<String>()
        for (provider in ProjectRemotesProvider.EP_NAME.extensionList) {
            try {
                fromProviders += provider.remoteUrls(project)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.info("Remotes provider ${provider.javaClass.name} failed: ${e.javaClass.simpleName}")
            }
        }
        if (fromProviders.isNotEmpty()) return fromProviders.toList()
        return fromGitConfig(project)
    }

    private fun fromGitConfig(project: Project): List<String> {
        val roots = LinkedHashSet<File>()
        project.basePath?.let { roots += File(it) }
        try {
            val contentRoots = ReadAction.compute<Array<com.intellij.openapi.vfs.VirtualFile>, RuntimeException> {
                if (project.isDisposed) emptyArray() else ProjectRootManager.getInstance(project).contentRoots
            }
            contentRoots.filter { it.isInLocalFileSystem }.forEach { roots += File(it.path) }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.info("Cannot read content roots: ${e.javaClass.simpleName}")
        }
        val result = LinkedHashSet<String>()
        for (root in roots) {
            val config = gitConfigFile(root) ?: continue
            try {
                result += GitRemotes.remoteUrlsFromGitConfig(config.readText())
            } catch (e: Exception) {
                LOG.info("Cannot read ${config.name}: ${e.javaClass.simpleName}")
            }
        }
        return result.toList()
    }

    /** `.git/config`, or for worktrees/submodules (`.git` is a file with `gitdir: …`) the referenced config. */
    private fun gitConfigFile(root: File): File? {
        val dotGit = File(root, ".git")
        val gitDir = when {
            dotGit.isDirectory -> dotGit
            dotGit.isFile -> {
                val line = dotGit.readLines().firstOrNull { it.startsWith("gitdir:") } ?: return null
                val path = line.substringAfter("gitdir:").trim()
                val dir = File(path).let { if (it.isAbsolute) it else File(root, path) }
                val commonDir = File(dir, "commondir")
                if (commonDir.isFile) {
                    val common = commonDir.readText().trim()
                    File(common).let { if (it.isAbsolute) it else File(dir, common) }
                } else {
                    dir
                }
            }
            else -> return null
        }
        return File(gitDir, "config").takeIf { it.isFile }
    }
}
