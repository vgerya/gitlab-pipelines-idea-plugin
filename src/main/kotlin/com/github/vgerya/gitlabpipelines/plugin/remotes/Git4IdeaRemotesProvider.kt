package com.github.vgerya.gitlabpipelines.plugin.remotes

import com.github.vgerya.gitlabpipelines.core.LocalRepo
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import git4idea.repo.GitRemote
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Registered in `gitlab-pipelines-git.xml`, i.e. only when the bundled Git plugin (Git4Idea) is enabled.
 *
 * `GitRepositoryManager` / `GitRepository` extend `com.intellij.dvcs.*` types that are not on the
 * plugin's compile classpath, so they are reached reflectively through their public API
 * (`GitRepositoryManager.getInstance(Project)`, `getRepositories()`, `GitRepository.getRemotes()`,
 * `getCurrentBranchName()`, the `GitRepository.GIT_REPO_CHANGE` topic). [GitRemote] has no such
 * supertypes and is used directly.
 */
class Git4IdeaRemotesProvider : ProjectRemotesProvider {

    private val loader: ClassLoader get() = Git4IdeaRemotesProvider::class.java.classLoader

    override fun repositories(project: Project): List<LocalRepo> {
        if (project.isDisposed) return emptyList()
        val managerClass = Class.forName("git4idea.repo.GitRepositoryManager", true, loader)
        val repositoryClass = Class.forName("git4idea.repo.GitRepository", true, loader)
        val manager = managerClass.getMethod("getInstance", Project::class.java).invoke(null, project) ?: return emptyList()
        val repositories = managerClass.getMethod("getRepositories").invoke(manager) as? Collection<*> ?: return emptyList()
        val getRemotes = repositoryClass.getMethod("getRemotes")
        val getCurrentBranchName = repositoryClass.getMethod("getCurrentBranchName")
        val result = ArrayList<LocalRepo>()
        for (repository in repositories) {
            if (repository == null) continue
            val urls = LinkedHashSet<String>()
            val remotes = getRemotes.invoke(repository) as? Collection<*>
            remotes?.forEach { remote ->
                if (remote is GitRemote) {
                    urls += remote.urls
                    urls += remote.pushUrls
                }
            }
            // null for a detached HEAD (or during a rebase)
            val branch = getCurrentBranchName.invoke(repository) as? String
            result += LocalRepo(urls.toList(), branch)
        }
        return result
    }

    override fun subscribe(project: Project, parent: Disposable, onChange: () -> Unit) {
        if (project.isDisposed) return
        val repositoryClass = Class.forName("git4idea.repo.GitRepository", true, loader)
        val listenerClass = Class.forName("git4idea.repo.GitRepositoryChangeListener", true, loader)
        val topic = repositoryClass.getField("GIT_REPO_CHANGE").get(null) as? Topic<*> ?: return
        val handler = InvocationHandler { proxy: Any, method: Method, args: Array<out Any?>? ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "GitLabPipelinesRepoChangeListener"
                else -> {
                    if (method.name == "repositoryChanged") onChange()
                    null
                }
            }
        }
        val listener = Proxy.newProxyInstance(loader, arrayOf(listenerClass), handler)
        @Suppress("UNCHECKED_CAST")
        project.messageBus.connect(parent).subscribe(topic as Topic<Any>, listener)
    }
}
