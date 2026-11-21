package com.github.vgerya.gitlabpipelines.plugin.remotes

import com.github.vgerya.gitlabpipelines.core.BranchFilter
import com.github.vgerya.gitlabpipelines.core.GitRemotes
import com.github.vgerya.gitlabpipelines.core.LocalRepo
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * Supplies the git repositories (remote URLs + current branch) of an IDE project. The Git4Idea-backed
 * implementation is registered only when the bundled Git plugin is enabled (optional dependency,
 * `gitlab-pipelines-git.xml`).
 */
interface ProjectRemotesProvider {
    /** May be called from a background thread. */
    fun repositories(project: Project): List<LocalRepo>

    /**
     * Calls [onChange] (on any thread) whenever a repository of [project] changes (branch checkout,
     * remotes edited) until [parent] is disposed. Default: no notifications.
     */
    fun subscribe(project: Project, parent: Disposable, onChange: () -> Unit) = Unit

    companion object {
        val EP_NAME: ExtensionPointName<ProjectRemotesProvider> =
            ExtensionPointName.create("com.github.vgerya.gitlabpipelines.remotesProvider")
    }
}

/**
 * Collects the project's repositories: registered providers first, the `.git` directories of the
 * project base path and content roots as a fallback.
 */
object ProjectRemotes {

    private val LOG = Logger.getInstance(ProjectRemotes::class.java)

    /** May do file IO: call from a background thread. */
    fun repositories(project: Project): List<LocalRepo> {
        if (project.isDisposed) return emptyList()
        val fromProviders = ArrayList<LocalRepo>()
        forEachProvider { fromProviders += it.repositories(project) }
        if (fromProviders.any { it.remoteUrls.isNotEmpty() }) return fromProviders
        return fromGitDirs(project)
    }

    /** Subscribes to repository changes of all providers (see [ProjectRemotesProvider.subscribe]). */
    fun subscribe(project: Project, parent: Disposable, onChange: () -> Unit) {
        forEachProvider { it.subscribe(project, parent, onChange) }
    }

    private inline fun forEachProvider(action: (ProjectRemotesProvider) -> Unit) {
        for (provider in ProjectRemotesProvider.EP_NAME.extensionList) {
            try {
                action(provider)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Throwable) {
                // reflective Git4Idea access: API changes surface as NoSuchMethodException / LinkageError
                val target = (e as? InvocationTargetException)?.targetException
                if (target is ProcessCanceledException) throw target
                if (e is VirtualMachineError) throw e
                LOG.info("Remotes provider ${provider.javaClass.name} failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun fromGitDirs(project: Project): List<LocalRepo> {
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
        val result = ArrayList<LocalRepo>()
        val seen = HashSet<File>()
        for (root in roots) {
            try {
                val dirs = gitDirs(root) ?: continue
                if (!seen.add(dirs.gitDir.absoluteFile)) continue
                val config = File(dirs.commonDir, "config")
                val urls = if (config.isFile) GitRemotes.remoteUrlsFromGitConfig(config.readText()) else emptyList()
                val head = File(dirs.gitDir, "HEAD")
                val branch = if (head.isFile) BranchFilter.parseHead(head.readText()) else null
                result += LocalRepo(urls, branch)
            } catch (e: Exception) {
                // e.g. unreadable `.git` file of a worktree: must not escape to the pooled thread
                LOG.info("Cannot read git metadata under ${root.name}: ${e.javaClass.simpleName}")
            }
        }
        return result
    }

    /** Per-worktree git dir (holds `HEAD`) and common dir (holds `config`); equal for a plain clone. */
    private class GitDirs(val gitDir: File, val commonDir: File)

    /** `.git`, or for worktrees/submodules (`.git` is a file with `gitdir: …`) the referenced directories. */
    private fun gitDirs(root: File): GitDirs? {
        val dotGit = File(root, ".git")
        return when {
            dotGit.isDirectory -> GitDirs(dotGit, dotGit)
            dotGit.isFile -> {
                val line = dotGit.readLines().firstOrNull { it.startsWith("gitdir:") } ?: return null
                val path = line.substringAfter("gitdir:").trim()
                val dir = File(path).let { if (it.isAbsolute) it else File(root, path) }
                val commonDir = File(dir, "commondir")
                val common = if (commonDir.isFile) {
                    val c = commonDir.readText().trim()
                    File(c).let { if (it.isAbsolute) it else File(dir, c) }
                } else {
                    dir
                }
                GitDirs(dir, common)
            }
            else -> null
        }
    }
}
