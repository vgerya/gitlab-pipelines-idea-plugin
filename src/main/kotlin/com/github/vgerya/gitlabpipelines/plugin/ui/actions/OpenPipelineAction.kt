package com.github.vgerya.gitlabpipelines.plugin.ui.actions

import com.github.vgerya.gitlabpipelines.core.MergeRequest
import com.github.vgerya.gitlabpipelines.plugin.MyBundle
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** Opens the head pipeline of the selected MR; disabled when there is no pipeline. */
class OpenPipelineAction(private val selection: () -> MergeRequest?) :
    DumbAwareAction(MyBundle.message("action.openPipeline")) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = selection()?.pipeline?.webUrl != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        selection()?.pipeline?.webUrl?.let { BrowserUtil.browse(it) }
    }
}
