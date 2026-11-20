package com.github.vgerya.gitlabpipelines.plugin.ui.actions

import com.github.vgerya.gitlabpipelines.core.MergeRequest
import com.github.vgerya.gitlabpipelines.plugin.MyBundle
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import java.awt.datatransfer.StringSelection

/** Opens the selected MR in the browser. [selection] is read on the EDT. */
class OpenMrAction(private val selection: () -> MergeRequest?) :
    DumbAwareAction(MyBundle.message("action.openMr"), null, AllIcons.General.Web) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = selection() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        selection()?.let { BrowserUtil.browse(it.webUrl) }
    }
}

/** Copies the selected MR URL to the clipboard. */
class CopyMrLinkAction(private val selection: () -> MergeRequest?) :
    DumbAwareAction(MyBundle.message("action.copyMrLink"), null, AllIcons.Actions.Copy) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = selection() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        selection()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.webUrl)) }
    }
}
