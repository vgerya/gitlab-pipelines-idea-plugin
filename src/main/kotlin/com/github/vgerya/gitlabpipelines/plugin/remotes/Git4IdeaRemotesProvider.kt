package com.github.vgerya.gitlabpipelines.plugin.remotes

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/** Registered in `gitlab-pipelines-git.xml`, i.e. only when the bundled Git plugin (Git4Idea) is enabled. */
class Git4IdeaRemotesProvider : ProjectRemotesProvider {
    override fun remoteUrls(project: Project): List<String> {
        if (project.isDisposed) return emptyList()
        return GitRepositoryManager.getInstance(project).repositories
            .flatMap { repo -> repo.remotes.flatMap { remote -> remote.urls + remote.pushUrls } }
            .distinct()
    }
}
