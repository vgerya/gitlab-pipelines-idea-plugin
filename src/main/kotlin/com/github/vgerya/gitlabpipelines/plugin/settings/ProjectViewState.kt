package com.github.vgerya.gitlabpipelines.plugin.settings

import com.github.vgerya.gitlabpipelines.core.FilterMode
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
        /** 1.1 setting, read only to migrate it to [filterMode]. */
        var onlyCurrentProject by property(false)

        /** [FilterMode] name; `null` = not chosen yet (fall back to [onlyCurrentProject]). */
        var filterMode by string(null)
    }

    var filterMode: FilterMode
        get() = FilterMode.resolve(state.filterMode, state.onlyCurrentProject)
        set(value) {
            state.filterMode = value.name
            state.onlyCurrentProject = false
        }

    companion object {
        fun getInstance(project: Project): ProjectViewState = project.getService(ProjectViewState::class.java)
    }
}
