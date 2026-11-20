package com.github.vgerya.gitlabpipelines.plugin.ui.actions

import com.github.vgerya.gitlabpipelines.plugin.MyBundle
import com.github.vgerya.gitlabpipelines.plugin.settings.GitLabConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

class OpenSettingsAction : DumbAwareAction(
    MyBundle.message("action.settings"),
    MyBundle.message("action.settings"),
    AllIcons.General.Settings,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        show(e.project)
    }

    companion object {
        fun show(project: Project?) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, GitLabConfigurable::class.java)
        }
    }
}
