package com.github.vgerya.gitlabpipelines.plugin.ui

import com.github.vgerya.gitlabpipelines.core.ErrorKind
import com.github.vgerya.gitlabpipelines.core.GitRemotes
import com.github.vgerya.gitlabpipelines.core.GroupRow
import com.github.vgerya.gitlabpipelines.core.Grouping
import com.github.vgerya.gitlabpipelines.core.MergeRequest
import com.github.vgerya.gitlabpipelines.core.MergeRequestRow
import com.github.vgerya.gitlabpipelines.core.MrColumn
import com.github.vgerya.gitlabpipelines.core.MrRow
import com.github.vgerya.gitlabpipelines.core.MrTreeBuilder
import com.github.vgerya.gitlabpipelines.core.PipelineRow
import com.github.vgerya.gitlabpipelines.core.UrlUtil
import com.github.vgerya.gitlabpipelines.plugin.MyBundle
import com.github.vgerya.gitlabpipelines.plugin.remotes.ProjectRemotes
import com.github.vgerya.gitlabpipelines.plugin.service.PipelinesListener
import com.github.vgerya.gitlabpipelines.plugin.service.PipelinesService
import com.github.vgerya.gitlabpipelines.plugin.service.Snapshot
import com.github.vgerya.gitlabpipelines.plugin.settings.GitLabSettings
import com.github.vgerya.gitlabpipelines.plugin.settings.ProjectViewState
import com.github.vgerya.gitlabpipelines.plugin.settings.ViewSettings
import com.github.vgerya.gitlabpipelines.plugin.ui.actions.CopyMrLinkAction
import com.github.vgerya.gitlabpipelines.plugin.ui.actions.OpenMrAction
import com.github.vgerya.gitlabpipelines.plugin.ui.actions.OpenPipelineAction
import com.github.vgerya.gitlabpipelines.plugin.ui.actions.OpenSettingsAction
import com.github.vgerya.gitlabpipelines.plugin.ui.actions.RefreshAction
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeTableSpeedSearch
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dualView.TreeTableView
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.util.containers.Convertor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.ChangeEvent
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableColumn
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * Content of the «GitLab Pipelines» tool window: one tree table for all modes (flat = no group
 * nodes). The STATUS column is the tree column. All methods run on the EDT unless stated otherwise.
 */
class PipelinesPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val view = ViewSettings.getInstance()
    private val projectState = ProjectViewState.getInstance(project)

    private val root = DefaultMutableTreeNode()
    private val model = ListTreeTableModelOnColumns(root, MrColumns.createColumnInfos())
    private val table = TreeTableView(model)

    private val updatedLabel = JBLabel()
    private val errorLabel = JBLabel(AllIcons.General.Error)
    private val errorSettingsLink = ActionLink(MyBundle.message("toolwindow.openSettings")) { openSettings() }
    private val errorPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2)))

    private var snapshot: Snapshot = PipelinesService.getInstance().snapshot

    /** Lower-cased GitLab project paths of the IDE project's git remotes; `null` = not computed yet. */
    private var remotePaths: Set<String>? = null

    /** Node ids the user expanded / collapsed; groups are expanded by default, MR rows collapsed. */
    private val expandedIds = HashSet<String>()
    private val collapsedIds = HashSet<String>()

    private var rebuilding = false
    private var layingOutColumns = false

    @Volatile
    private var disposed = false

    init {
        setToolbar(createToolbar())

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
            PipelinesListener { s ->
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed) {
                        render(s)
                        updateRemotes()
                    }
                }
            },
        )
        render(snapshot)
        updateRemotes()
        PipelinesService.getInstance().ensureStarted()
    }

    // ------------------------------------------------------------------ toolbar & actions

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()
        ActionManager.getInstance().getAction(RefreshAction.ID)?.let { group.add(it) }
        group.add(Separator.getInstance())
        group.add(OnlyCurrentProjectAction())
        group.add(createGroupingGroup())
        group.add(ShowAllPipelinesAction())
        group.add(createColumnsGroup())
        group.add(Separator.getInstance())
        group.add(OpenSettingsAction())
        val toolbar = ActionManager.getInstance().createActionToolbar("GitLabPipelines", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun createGroupingGroup(): DefaultActionGroup {
        val group = DefaultActionGroup(MyBundle.message("action.grouping"), true)
        group.templatePresentation.icon = AllIcons.Actions.GroupBy
        group.templatePresentation.description = MyBundle.message("action.grouping")
        Grouping.values().forEach { group.add(GroupingAction(it)) }
        return group
    }

    private fun createColumnsGroup(): DefaultActionGroup {
        val group = DefaultActionGroup(MyBundle.message("action.columns"), true)
        group.templatePresentation.icon = AllIcons.Actions.Show
        group.templatePresentation.description = MyBundle.message("action.columns")
        MrColumn.values().forEach { group.add(ColumnToggleAction(it)) }
        return group
    }

    private abstract inner class ViewToggle(text: String, icon: Icon? = null) :
        ToggleAction(text, text, icon), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class OnlyCurrentProjectAction :
        ViewToggle(MyBundle.message("action.onlyCurrentProject"), AllIcons.Actions.ProjectDirectory) {
        override fun isSelected(e: AnActionEvent): Boolean = projectState.onlyCurrentProject

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            setOnlyCurrentProject(state)
        }
    }

    private inner class ShowAllPipelinesAction :
        ViewToggle(MyBundle.message("action.showAllPipelines"), AllIcons.Vcs.History) {
        override fun isSelected(e: AnActionEvent): Boolean = view.showAllPipelines

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            view.showAllPipelines = state
            rebuild()
            // recent pipelines are loaded by the poll only while the option is on
            if (state) PipelinesService.getInstance().refreshNow()
        }
    }

    private inner class GroupingAction(private val grouping: Grouping) : ViewToggle(groupingText(grouping)) {
        override fun isSelected(e: AnActionEvent): Boolean = view.grouping == grouping

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state && view.grouping != grouping) {
                view.grouping = grouping
                rebuild()
            }
        }
    }

    private inner class ColumnToggleAction(private val column: MrColumn) : ViewToggle(MrColumns.title(column)) {
        override fun update(e: AnActionEvent) {
            super.update(e)
            // the status column holds the tree (expand handles), it cannot be hidden
            e.presentation.isEnabled = column != MrColumn.STATUS
        }

        override fun isSelected(e: AnActionEvent): Boolean = column in view.visibleColumns

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (column == MrColumn.STATUS) return
            view.visibleColumns = if (state) view.visibleColumns + column else view.visibleColumns - column
            applyColumnLayout()
        }
    }

    private fun groupingText(g: Grouping): String = when (g) {
        Grouping.NONE -> MyBundle.message("grouping.none")
        Grouping.PROJECT -> MyBundle.message("grouping.project")
        Grouping.BRANCH -> MyBundle.message("grouping.branch")
        Grouping.PROJECT_BRANCH -> MyBundle.message("grouping.projectBranch")
    }

    private fun setOnlyCurrentProject(state: Boolean) {
        projectState.onlyCurrentProject = state
        rebuild()
        updateRemotes()
    }

    // ------------------------------------------------------------------ table setup

    private fun setupTable() {
        table.autoCreateColumnsFromModel = false
        table.setTreeCellRenderer(StatusTreeRenderer())
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.setMinRowHeight(JBUI.scale(22))
        table.tableHeader.reorderingAllowed = true
        // double click opens the MR / pipeline; group nodes are toggled explicitly (see below)
        table.tree.toggleClickCount = 0
        table.tree.isRootVisible = false

        applyColumnLayout()
        table.columnModel.addColumnModelListener(ColumnPersistence())

        table.tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = remember(event.path, expanded = true)
            override fun treeCollapsed(event: TreeExpansionEvent) = remember(event.path, expanded = false)
        })

        TreeTableSpeedSearch.installOn(
            table,
            Convertor<TreePath, String> { path -> MrColumns.rowOf(path.lastPathComponent)?.let { MrTreeBuilder.searchText(it) }.orEmpty() },
        )

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = activateSelected()
        }.installOn(table)

        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "gitlabOpen")
        table.actionMap.put("gitlabOpen", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                activateSelected()
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
        popupGroup.add(OpenMrAction(::selectedMergeRequest))
        popupGroup.add(OpenPipelineAction(::selectedPipelineUrl))
        popupGroup.add(CopyMrLinkAction(::selectedMergeRequest))
        PopupHandler.installPopupMenu(table, popupGroup, "GitLabPipelinesPopup")

        // header: click sorts, right click chooses the columns
        val header = table.tableHeader
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount != 1) return
                if (header.resizingColumn != null || header.cursor.type == Cursor.E_RESIZE_CURSOR) return
                val viewIndex = header.columnAtPoint(e.point)
                if (viewIndex < 0) return
                val column = table.columnModel.getColumn(viewIndex).identifier as? MrColumn ?: return
                view.sort = view.sort.toggle(column)
                updateHeaders()
                rebuild()
            }
        })
        PopupHandler.installPopupMenu(header, createColumnsGroup(), "GitLabPipelinesHeaderPopup")
    }

    private fun remember(path: TreePath?, expanded: Boolean) {
        if (rebuilding) return
        val id = MrColumns.rowOf(path?.lastPathComponent)?.id ?: return
        if (expanded) {
            expandedIds += id
            collapsedIds -= id
        } else {
            collapsedIds += id
            expandedIds -= id
        }
    }

    /** Re-creates the table columns from the persisted visibility, order and widths. */
    private fun applyColumnLayout() {
        layingOutColumns = true
        try {
            val cm = table.columnModel
            while (cm.columnCount > 0) table.removeColumn(cm.getColumn(0))
            val visible = view.visibleColumns
            val widths = view.columnWidths
            for (column in view.columnOrder) {
                if (column !in visible) continue
                val tc = TableColumn(column.ordinal)
                tc.identifier = column
                tc.headerValue = headerText(column)
                tc.preferredWidth = widths[column] ?: JBUI.scale(MrColumns.defaultWidth(column))
                tc.minWidth = JBUI.scale(if (column == MrColumn.STATUS) 60 else 30)
                table.addColumn(tc)
            }
        } finally {
            layingOutColumns = false
        }
        table.tableHeader.revalidate()
        table.tableHeader.repaint()
    }

    private fun headerText(column: MrColumn): String {
        val sort = view.sort
        val arrow = if (sort.column == column) (if (sort.ascending) " ▲" else " ▼") else ""
        return MrColumns.title(column) + arrow
    }

    private fun updateHeaders() {
        val cm = table.columnModel
        for (i in 0 until cm.columnCount) {
            val tc = cm.getColumn(i)
            (tc.identifier as? MrColumn)?.let { tc.headerValue = headerText(it) }
        }
        table.tableHeader.repaint()
    }

    /** Persists column order (drag in the header) and widths. */
    private inner class ColumnPersistence : TableColumnModelListener {
        override fun columnMoved(e: TableColumnModelEvent) {
            if (layingOutColumns || e.fromIndex == e.toIndex) return
            val shown = visibleColumnOrder()
            val hidden = view.columnOrder.filter { it !in shown }
            view.columnOrder = shown + hidden
        }

        override fun columnMarginChanged(e: ChangeEvent) {
            if (layingOutColumns) return
            val cm = table.columnModel
            val widths = view.columnWidths.toMutableMap()
            for (i in 0 until cm.columnCount) {
                val tc = cm.getColumn(i)
                (tc.identifier as? MrColumn)?.let { widths[it] = tc.width }
            }
            view.columnWidths = widths
        }

        override fun columnAdded(e: TableColumnModelEvent) = Unit
        override fun columnRemoved(e: TableColumnModelEvent) = Unit
        override fun columnSelectionChanged(e: ListSelectionEvent) = Unit
    }

    private fun visibleColumnOrder(): List<MrColumn> {
        val cm = table.columnModel
        return (0 until cm.columnCount).mapNotNull { cm.getColumn(it).identifier as? MrColumn }
    }

    // ------------------------------------------------------------------ selection

    private fun selectedRow(): MrRow? {
        val row = table.selectedRow
        if (row < 0) return null
        return MrColumns.rowOf(table.tree.getPathForRow(row)?.lastPathComponent)
    }

    private fun selectedMergeRequest(): MergeRequest? = when (val r = selectedRow()) {
        is MergeRequestRow -> r.mr
        is PipelineRow -> r.mr
        else -> null
    }

    private fun selectedPipelineUrl(): String? = when (val r = selectedRow()) {
        is MergeRequestRow -> r.mr.pipeline?.webUrl
        is PipelineRow -> r.pipeline.webUrl
        else -> null
    }

    /** Double click / Enter: MR row → MR, pipeline row → pipeline, group → expand/collapse. */
    private fun activateSelected(): Boolean {
        val row = table.selectedRow
        if (row < 0) return false
        val path = table.tree.getPathForRow(row) ?: return false
        when (val r = MrColumns.rowOf(path.lastPathComponent)) {
            is MergeRequestRow -> BrowserUtil.browse(r.mr.webUrl)
            is PipelineRow -> BrowserUtil.browse(r.pipeline.webUrl ?: r.mr.webUrl)
            is GroupRow -> {
                if (table.tree.isExpanded(path)) table.tree.collapsePath(path) else table.tree.expandPath(path)
                table.setRowSelectionInterval(row, row)
            }
            null -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ data

    private fun render(s: Snapshot) {
        snapshot = s
        updatedLabel.text = when {
            s.loading -> MyBundle.message("toolwindow.updating")
            s.lastSuccess != null ->
                MyBundle.message("toolwindow.updated", TIME_FORMAT.format(s.lastSuccess.atZone(ZoneId.systemDefault())))
            else -> MyBundle.message("toolwindow.neverUpdated")
        }

        val error = s.error
        if (error != null) {
            errorLabel.text = StatusIcons.errorText(error)
            errorSettingsLink.isVisible = StatusIcons.needsSettings(error)
            errorPanel.isVisible = true
        } else {
            errorPanel.isVisible = false
        }
        rebuild()
    }

    /** Recomputes the remote project paths in the background (only while the filter is on). */
    private fun updateRemotes() {
        if (!projectState.onlyCurrentProject) return
        val baseUrl = GitLabSettings.getInstance().baseUrl
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed || project.isDisposed) return@executeOnPooledThread
            val paths = GitRemotes.projectPaths(ProjectRemotes.collect(project), baseUrl)
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && !project.isDisposed && paths != remotePaths) {
                    remotePaths = paths
                    rebuild()
                }
            }
        }
    }

    private fun visibleMergeRequests(): List<MergeRequest> {
        val paths = remotePaths
        // until the remotes are known, show everything rather than an empty list
        if (!projectState.onlyCurrentProject || paths == null) return snapshot.mrs
        return GitRemotes.filterByProjects(snapshot.mrs, paths)
    }

    private fun rebuild() {
        val selectedId = selectedRow()?.id
        val grouping = view.grouping
        val showAll = view.showAllPipelines
        val mrs = visibleMergeRequests()
        val rows = MrTreeBuilder.build(mrs, grouping, view.sort, if (showAll) snapshot.pipelines else null)

        rebuilding = true
        try {
            root.removeAllChildren()
            addNodes(root, rows)
            model.reload()
            table.tree.showsRootHandles = grouping != Grouping.NONE || showAll
            expandNodes(root, parentExpanded = true)
            (table.model as? AbstractTableModel)?.fireTableDataChanged()
        } finally {
            rebuilding = false
        }

        if (selectedId != null) {
            findNode(root, selectedId)?.let { node ->
                val row = table.tree.getRowForPath(TreePath(node.path))
                if (row >= 0) table.setRowSelectionInterval(row, row)
            }
        }
        updateEmptyText(mrs)
        table.revalidate()
        table.repaint()
    }

    private fun addNodes(parent: DefaultMutableTreeNode, rows: List<MrRow>) {
        for (row in rows) {
            val node = RowNode(row)
            parent.add(node)
            addNodes(node, row.children)
        }
    }

    private fun expandNodes(node: DefaultMutableTreeNode, parentExpanded: Boolean) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            if (child.childCount == 0) continue
            val row = child.userObject as? MrRow ?: continue
            val expand = when (row) {
                is GroupRow -> row.id !in collapsedIds
                else -> row.id in expandedIds
            }
            if (parentExpanded && expand) table.tree.expandPath(TreePath(child.path))
            expandNodes(child, parentExpanded && expand)
        }
    }

    private fun findNode(node: DefaultMutableTreeNode, id: String): DefaultMutableTreeNode? {
        if ((node.userObject as? MrRow)?.id == id) return node
        for (i in 0 until node.childCount) {
            findNode(node.getChildAt(i) as DefaultMutableTreeNode, id)?.let { return it }
        }
        return null
    }

    private fun updateEmptyText(shown: List<MergeRequest>) {
        val s = snapshot
        val error = s.error
        val emptyText = table.emptyText
        emptyText.clear()
        when {
            error?.kind == ErrorKind.NOT_CONFIGURED -> {
                emptyText.appendText(MyBundle.message("toolwindow.empty.notConfigured"))
                emptyText.appendSecondaryText(
                    MyBundle.message("toolwindow.openSettings"),
                    SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                ) { openSettings() }
            }
            s.loading && s.lastSuccess == null -> emptyText.appendText(MyBundle.message("toolwindow.updating"))
            shown.isEmpty() && s.mrs.isNotEmpty() && projectState.onlyCurrentProject -> {
                val message = if (remotePaths.isNullOrEmpty()) {
                    MyBundle.message("toolwindow.empty.noRemotes", gitLabHost())
                } else {
                    MyBundle.message("toolwindow.empty.filtered")
                }
                emptyText.appendText(message)
                emptyText.appendSecondaryText(
                    MyBundle.message("toolwindow.empty.showAllProjects"),
                    SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                ) { setOnlyCurrentProject(false) }
            }
            else -> emptyText.appendText(MyBundle.message("toolwindow.empty.noMrs"))
        }
    }

    private fun gitLabHost(): String {
        val base = UrlUtil.normalizeBaseUrl(GitLabSettings.getInstance().baseUrl) ?: return "GitLab"
        return try {
            URI(base).host ?: base
        } catch (e: Exception) {
            base
        }
    }

    private fun openSettings() {
        OpenSettingsAction.show(project)
    }

    override fun dispose() {
        disposed = true
    }

    companion object {
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
