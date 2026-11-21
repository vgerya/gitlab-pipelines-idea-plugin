package com.github.vgerya.gitlabpipelines.plugin.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/** Per-project view options (stored in the workspace file, not shared via VCS). */
@Service(Service.Level.PROJECT)
@State(name = "GitLabMrPipelinesProjectView", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectViewState : SimplePersistentStateComponent<ProjectViewState.ProjectState>(ProjectState()) {

    class ProjectState : BaseState() {
        var onlyCurrentProject by property(false)
    }

    var onlyCurrentProject: Boolean
        get() = state.onlyCurrentProject
        set(value) {
            state.onlyCurrentProject = value
        }

    companion object {
        fun getInstance(project: Project): ProjectViewState = project.getService(ProjectViewState::class.java)
    }
}
