package com.github.vgerya.gitlabpipelines.plugin.ui

import com.github.vgerya.gitlabpipelines.plugin.MyBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class PipelinesStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = PipelinesStatusBarWidget.ID

    override fun getDisplayName(): String = MyBundle.message("widget.name")

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = PipelinesStatusBarWidget(project)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
