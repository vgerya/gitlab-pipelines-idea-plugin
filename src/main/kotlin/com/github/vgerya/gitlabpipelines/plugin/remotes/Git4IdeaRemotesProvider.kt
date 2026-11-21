package com.github.vgerya.gitlabpipelines.plugin.remotes

import com.intellij.openapi.project.Project
import git4idea.repo.GitRemote

/**
 * Registered in `gitlab-pipelines-git.xml`, i.e. only when the bundled Git plugin (Git4Idea) is enabled.
 *
 * `GitRepositoryManager` / `GitRepository` extend `com.intellij.dvcs.*` types that are not on the
 * plugin's compile classpath, so they are reached reflectively through their public API
 * (`GitRepositoryManager.getInstance(Project)`, `getRepositories()`, `GitRepository.getRemotes()`).
 * [GitRemote] has no such supertypes and is used directly.
 */
class Git4IdeaRemotesProvider : ProjectRemotesProvider {

    override fun remoteUrls(project: Project): List<String> {
        if (project.isDisposed) return emptyList()
        val loader = Git4IdeaRemotesProvider::class.java.classLoader
        val managerClass = Class.forName("git4idea.repo.GitRepositoryManager", true, loader)
        val repositoryClass = Class.forName("git4idea.repo.GitRepository", true, loader)
        val manager = managerClass.getMethod("getInstance", Project::class.java).invoke(null, project) ?: return emptyList()
        val repositories = managerClass.getMethod("getRepositories").invoke(manager) as? Collection<*> ?: return emptyList()
        val getRemotes = repositoryClass.getMethod("getRemotes")
        val result = LinkedHashSet<String>()
        for (repository in repositories) {
            if (repository == null) continue
            val remotes = getRemotes.invoke(repository) as? Collection<*> ?: continue
            for (remote in remotes) {
                if (remote is GitRemote) {
                    result += remote.urls
                    result += remote.pushUrls
                }
            }
        }
        return result.toList()
    }
}
