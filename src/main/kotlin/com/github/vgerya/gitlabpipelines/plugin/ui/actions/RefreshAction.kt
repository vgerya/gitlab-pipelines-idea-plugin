package com.github.vgerya.gitlabpipelines.plugin.ui.actions

import com.github.vgerya.gitlabpipelines.plugin.service.PipelinesService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** Registered in plugin.xml as `GitLabPipelines.Refresh`; text from the resource bundle. */
class RefreshAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !PipelinesService.getInstance().snapshot.loading
    }

    override fun actionPerformed(e: AnActionEvent) {
        PipelinesService.getInstance().refreshNow()
    }

    companion object {
        const val ID = "GitLabPipelines.Refresh"
    }
}
