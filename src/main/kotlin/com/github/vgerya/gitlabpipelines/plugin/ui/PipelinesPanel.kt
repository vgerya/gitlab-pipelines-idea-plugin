package com.github.vgerya.gitlabpipelines.plugin.ui

import com.github.vgerya.gitlabpipelines.core.ErrorKind
import com.github.vgerya.gitlabpipelines.core.MergeRequest
import com.github.vgerya.gitlabpipelines.plugin.MyBundle
import com.github.vgerya.gitlabpipelines.plugin.service.PipelinesListener
import com.github.vgerya.gitlabpipelines.plugin.service.PipelinesService
import com.github.vgerya.gitlabpipelines.plugin.service.Snapshot
import com.github.vgerya.gitlabpipelines.plugin.ui.actions.CopyMrLinkAction
import com.github.vgerya.gitlabpipelines.plugin.ui.actions.OpenMrAction
import com.github.vgerya.gitlabpipelines.plugin.ui.actions.OpenPipelineAction
import com.github.vgerya.gitlabpipelines.plugin.ui.actions.OpenSettingsAction
import com.github.vgerya.gitlabpipelines.plugin.ui.actions.RefreshAction
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/** Content of the «GitLab Pipelines» tool window. */
class PipelinesPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val model = MrTableModel()
    private val table = JBTable(model)
    private val updatedLabel = JBLabel()
    private val errorLabel = JBLabel(AllIcons.General.Error)
    private val errorSettingsLink = ActionLink(MyBundle.message("toolwindow.openSettings")) { openSettings() }
    private val errorPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2)))

    init {
        val refresh = ActionManager.getInstance().getAction(RefreshAction.ID)
        val toolbarGroup = DefaultActionGroup()
        if (refresh != null) toolbarGroup.add(refresh)
        toolbarGroup.add(OpenSettingsAction())
        val toolbar = ActionManager.getInstance().createActionToolbar("GitLabPipelines", toolbarGroup, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        errorPanel.add(errorLabel)
        errorPanel.add(errorSettingsLink)
        errorPanel.isVisible = false

        val header = JPanel()
        header.layout = BoxLayout(header, BoxLayout.Y_AXIS)
        updatedLabel.border = JBUI.Borders.empty(4, 8)
        updatedLabel.alignmentX = 0f
        errorPanel.alignmentX = 0f
        header.add(updatedLabel)
        header.add(errorPanel)

        setupTable()

        val content = JPanel(BorderLayout())
        content.add(header, BorderLayout.NORTH)
        content.add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER)
        setContent(content)

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            PipelinesListener.TOPIC,
            PipelinesListener { snapshot ->
                ApplicationManager.getApplication().invokeLater { render(snapshot) }
            },
        )
        render(PipelinesService.getInstance().snapshot)
        PipelinesService.getInstance().ensureStarted()
    }

    private fun setupTable() {
        table.setShowGrid(false)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.tableHeader.reorderingAllowed = false
        table.rowHeight = maxOf(table.rowHeight, JBUI.scale(22))
        model.columnInfos.forEachIndexed { index, info ->
            val width = info.getWidth(table)
            if (width > 0) {
                val column = table.columnModel.getColumn(index)
                column.preferredWidth = width
                column.maxWidth = width * 3
            }
        }

        TableSpeedSearch.installOn(table)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val mr = selected() ?: return false
                BrowserUtil.browse(mr.webUrl)
                return true
            }
        }.installOn(table)

        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "gitlabOpenMr")
        table.actionMap.put("gitlabOpenMr", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                selected()?.let { BrowserUtil.browse(it.webUrl) }
            }
        })

        // select the row under the cursor before showing the context menu
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    val row = table.rowAtPoint(e.point)
                    if (row >= 0) table.setRowSelectionInterval(row, row)
                }
            }
        })
        val popupGroup = DefaultActionGroup()
        popupGroup.add(OpenMrAction(::selected))
        popupGroup.add(OpenPipelineAction(::selected))
        popupGroup.add(CopyMrLinkAction(::selected))
        PopupHandler.installPopupMenu(table, popupGroup, "GitLabPipelinesPopup")
    }

    private fun selected(): MergeRequest? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        return model.mergeRequestAt(table.convertRowIndexToModel(viewRow))
    }

    private fun render(snapshot: Snapshot) {
        val selectedKey = selected()?.key
        model.items = snapshot.mrs
        if (selectedKey != null) {
            val row = snapshot.mrs.indexOfFirst { it.key == selectedKey }
            if (row >= 0) {
                val viewRow = table.convertRowIndexToView(row)
                table.setRowSelectionInterval(viewRow, viewRow)
            }
        }

        updatedLabel.text = when {
            snapshot.loading -> MyBundle.message("toolwindow.updating")
            snapshot.lastSuccess != null ->
                MyBundle.message("toolwindow.updated", TIME_FORMAT.format(snapshot.lastSuccess.atZone(ZoneId.systemDefault())))
            else -> MyBundle.message("toolwindow.neverUpdated")
        }

        val error = snapshot.error
        if (error != null) {
            errorLabel.text = StatusIcons.errorText(error)
            errorSettingsLink.isVisible = StatusIcons.needsSettings(error)
            errorPanel.isVisible = true
        } else {
            errorPanel.isVisible = false
        }

        val emptyText = table.emptyText
        emptyText.clear()
        if (error?.kind == ErrorKind.NOT_CONFIGURED) {
            emptyText.appendText(MyBundle.message("toolwindow.empty.notConfigured"))
            emptyText.appendSecondaryText(
                MyBundle.message("toolwindow.openSettings"),
                SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
            ) { openSettings() }
        } else if (snapshot.loading && snapshot.lastSuccess == null) {
            emptyText.appendText(MyBundle.message("toolwindow.updating"))
        } else {
            emptyText.appendText(MyBundle.message("toolwindow.empty.noMrs"))
        }

        revalidate()
        repaint()
    }

    private fun openSettings() {
        OpenSettingsAction.show(project)
    }

    override fun dispose() = Unit

    companion object {
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
