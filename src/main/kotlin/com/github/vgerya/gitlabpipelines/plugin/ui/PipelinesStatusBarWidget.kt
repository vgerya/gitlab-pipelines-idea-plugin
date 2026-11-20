package com.github.vgerya.gitlabpipelines.plugin.ui

import com.github.vgerya.gitlabpipelines.core.Summary
import com.github.vgerya.gitlabpipelines.core.WidgetKind
import com.github.vgerya.gitlabpipelines.plugin.MyBundle
import com.github.vgerya.gitlabpipelines.plugin.service.PipelinesListener
import com.github.vgerya.gitlabpipelines.plugin.service.PipelinesService
import com.github.vgerya.gitlabpipelines.plugin.service.Snapshot
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/** Status bar widget: icon + number, updated from [PipelinesListener.TOPIC]. */
class PipelinesStatusBarWidget(private val project: Project) : CustomStatusBarWidget {

    private val label = JBLabel()

    init {
        label.border = JBUI.Borders.empty(0, 4)
        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (project.isDisposed) return
                ToolWindowManager.getInstance(project)
                    .getToolWindow(PipelinesToolWindowFactory.TOOL_WINDOW_ID)
                    ?.activate(null)
            }
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            PipelinesListener.TOPIC,
            PipelinesListener { snapshot ->
                ApplicationManager.getApplication().invokeLater { render(snapshot) }
            },
        )
        // Newer platforms may create status bar widgets off the EDT: touch Swing only on the EDT.
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            render(PipelinesService.getInstance().snapshot)
        } else {
            app.invokeLater { render(PipelinesService.getInstance().snapshot) }
        }
    }

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        PipelinesService.getInstance().ensureStarted()
    }

    override fun getComponent(): JComponent = label

    override fun dispose() = Unit

    private fun render(snapshot: Snapshot) {
        val counts = snapshot.counts
        val error = snapshot.error
        val state = Summary.widgetState(counts, error?.kind)
        when (state.kind) {
            WidgetKind.FAILED -> {
                label.icon = AllIcons.RunConfigurations.TestFailed
                label.text = state.number.toString()
            }
            WidgetKind.RUNNING -> {
                label.icon = AllIcons.RunConfigurations.TestState.Run
                label.text = state.number.toString()
            }
            WidgetKind.OK -> {
                label.icon = AllIcons.RunConfigurations.TestPassed
                label.text = ""
            }
            WidgetKind.ERROR -> {
                label.icon = AllIcons.General.Error
                label.text = ""
            }
            WidgetKind.EMPTY -> {
                label.icon = StatusIcons.TOOL_WINDOW
                label.text = ""
            }
        }
        label.toolTipText = if (state.kind == WidgetKind.ERROR && error != null) {
            StatusIcons.errorText(error)
        } else {
            val base = MyBundle.message(
                "widget.tooltip",
                counts.failed.toString(),
                (counts.running + counts.pending).toString(),
                counts.success.toString(),
                counts.total.toString(),
            )
            if (error != null) base + " — " + StatusIcons.errorText(error) else base
        }
        label.revalidate()
        label.repaint()
    }

    companion object {
        const val ID = "GitLabPipelinesWidget"
    }
}
