package com.github.vgerya.gitlabpipelines.plugin.ui.actions

import com.github.vgerya.gitlabpipelines.plugin.MyBundle
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * Opens the pipeline of the selected row (a pipeline row, or the head pipeline of an MR row);
 * disabled when there is no pipeline. [pipelineUrl] is read on the EDT.
 */
class OpenPipelineAction(private val pipelineUrl: () -> String?) :
    DumbAwareAction(MyBundle.message("action.openPipeline")) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = pipelineUrl() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        pipelineUrl()?.let { BrowserUtil.browse(it) }
    }
}
