package com.github.vgerya.gitlabpipelines.plugin.ui

import com.github.vgerya.gitlabpipelines.core.BranchFilter
import com.github.vgerya.gitlabpipelines.core.ErrorKind
import com.github.vgerya.gitlabpipelines.core.FilterMode
import com.github.vgerya.gitlabpipelines.core.GroupRow
import com.github.vgerya.gitlabpipelines.core.Grouping
import com.github.vgerya.gitlabpipelines.core.MergeRequest
import com.github.vgerya.gitlabpipelines.core.MergeRequestRow
import com.github.vgerya.gitlabpipelines.core.MrColumn
import com.github.vgerya.gitlabpipelines.core.MrRow
import com.github.vgerya.gitlabpipelines.core.MrTreeBuilder
import com.github.vgerya.gitlabpipelines.core.PipelineRow
import com.github.vgerya.gitlabpipelines.core.PipelineTime
import com.github.vgerya.gitlabpipelines.core.RepoState
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
import java.awt.event.HierarchyEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.time.format.DateTimeFormatter
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
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
 * Content of the "GitLab Pipelines" tool window: one tree table for all modes (flat = no group
 * nodes). The STATUS column is the tree column. All methods run on the EDT unless stated otherwise.
 */
class PipelinesPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val view = ViewSettings.getInstance()
    private val projectState = ProjectViewState.getInstance(project)

    private val root = DefaultMutableTreeNode()
    private val model = ListTreeTableModelOnColumns(root, MrColumns.createColumnInfos())
    private val table = object : TreeTableView(model) {
        override fun getToolTipText(event: MouseEvent): String? = cellTooltip(event) ?: super.getToolTipText(event)
    }

    private val updatedLabel = JBLabel()
    private val errorLabel = JBLabel(AllIcons.General.Error)
    private val errorSettingsLink = ActionLink(MyBundle.message("toolwindow.openSettings")) { openSettings() }
    private val errorPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2)))

    private var snapshot: Snapshot = PipelinesService.getInstance().snapshot

    /** Repositories of the IDE project that point to the configured GitLab; `null` = not computed yet. */
    private var repoStates: List<RepoState>? = null
    private val repoUpdateRunning = AtomicBoolean(false)
    private var repoUpdatePending = false

    /**
     * 1 s tick, running only while the panel is showing (see [updateTicker]): repaints live durations of running pipelines and,
     * every [REPO_CHECK_TICKS] ticks, re-reads remotes and current branches (cheap: in-memory Git4Idea
     * state or a small `.git/HEAD` read) so that a `git checkout` shows up within a few seconds.
     */
    private val ticker = Timer(1000) { onTick() }
    private var tick = 0

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
        ProjectRemotes.subscribe(project, this) {
            ApplicationManager.getApplication().invokeLater { if (!disposed) updateRemotes() }
        }
        render(snapshot)
        updateRemotes()
        // tick only while the tool window content is actually showing (hidden / minimized: stopped)
        addHierarchyListener { e ->
            if ((e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L) updateTicker()
        }
        updateTicker()
        PipelinesService.getInstance().ensureStarted()
    }

    private fun updateTicker() {
        if (!disposed && isShowing) {
            if (!ticker.isRunning) {
                ticker.start()
                // catch up at once on what changed while hidden (branch checkout, elapsed durations)
                updateRemotes()
                table.repaint()
            }
        } else {
            ticker.stop()
        }
    }

    private fun onTick() {
        if (disposed || !table.isShowing) return
        tick++
        if (tick % REPO_CHECK_TICKS == 0) updateRemotes()
        if (MrColumn.DURATION in view.visibleColumns && snapshot.hasRunningPipeline()) table.repaint()
    }

    private fun Snapshot.hasRunningPipeline(): Boolean =
        mrs.any { mr -> mr.pipeline?.let { PipelineTime.isRunning(it) } == true } ||
            (view.showAllPipelines && pipelines.values.any { list -> list.any { PipelineTime.isRunning(it) } })

    // ------------------------------------------------------------------ toolbar & actions

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()
        ActionManager.getInstance().getAction(RefreshAction.ID)?.let { group.add(it) }
        group.add(Separator.getInstance())
        group.add(createFilterGroup())
        group.add(createGroupingGroup())
        group.add(ShowAllPipelinesAction())
        group.add(createColumnsGroup())
        group.add(Separator.getInstance())
        group.add(OpenSettingsAction())
        val toolbar = ActionManager.getInstance().createActionToolbar("GitLabPipelines", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    /*
     * Toolbar icons must stay distinct in the new UI: there AllIcons.Actions.GroupBy is mapped to the
     * same "eye" as AllIcons.Actions.Show, so grouping uses GroupByPackage and columns DataTables.
     */

    private fun popupGroup(textKey: String, descriptionKey: String, icon: Icon): DefaultActionGroup {
        val group = DefaultActionGroup(MyBundle.message(textKey), true)
        group.templatePresentation.icon = icon
        group.templatePresentation.description = MyBundle.message(descriptionKey)
        return group
    }

    private fun createFilterGroup(): DefaultActionGroup {
        val group = popupGroup("action.filter", "action.filter.description", AllIcons.General.Filter)
        FilterMode.values().forEach { group.add(FilterModeAction(it)) }
        return group
    }

    private fun createGroupingGroup(): DefaultActionGroup {
        val group = popupGroup("action.grouping", "action.grouping.description", AllIcons.Actions.GroupByPackage)
        Grouping.values().forEach { group.add(GroupingAction(it)) }
        return group
    }

    private fun createColumnsGroup(): DefaultActionGroup {
        val group = popupGroup("action.columns", "action.columns.description", AllIcons.Nodes.DataTables)
        MrColumn.values().forEach { group.add(ColumnToggleAction(it)) }
        return group
    }

    private abstract inner class ViewToggle(text: String, description: String = text, icon: Icon? = null) :
        ToggleAction(text, description, icon), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class FilterModeAction(private val mode: FilterMode) : ViewToggle(filterText(mode)) {
        override fun isSelected(e: AnActionEvent): Boolean = projectState.filterMode == mode

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) setFilterMode(mode)
        }
    }

    private inner class ShowAllPipelinesAction : ViewToggle(
        MyBundle.message("action.showAllPipelines"),
        MyBundle.message("action.showAllPipelines.description"),
        AllIcons.Vcs.History,
    ) {
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

    private fun filterText(mode: FilterMode): String = when (mode) {
        FilterMode.ALL -> MyBundle.message("filter.all")
        FilterMode.CURRENT_PROJECT -> MyBundle.message("filter.currentProject")
        FilterMode.CURRENT_BRANCH -> MyBundle.message("filter.currentBranch")
    }

    private fun setFilterMode(mode: FilterMode) {
        if (projectState.filterMode == mode) return
        projectState.filterMode = mode
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

    /**
     * Recomputes the project's repositories (remotes + current branches) in the background; only while
     * a filter is active. At most one computation runs at a time; a request during it re-runs it once.
     */
    private fun updateRemotes() {
        if (disposed || projectState.filterMode == FilterMode.ALL) return
        if (!repoUpdateRunning.compareAndSet(false, true)) {
            repoUpdatePending = true
            return
        }
        repoUpdatePending = false
        val baseUrl = GitLabSettings.getInstance().baseUrl
        ApplicationManager.getApplication().executeOnPooledThread {
            var states: List<RepoState>? = null
            try {
                if (!disposed && !project.isDisposed) {
                    states = BranchFilter.resolve(ProjectRemotes.repositories(project), baseUrl)
                }
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    repoUpdateRunning.set(false)
                    if (disposed || project.isDisposed) return@invokeLater
                    if (states != null && states != repoStates) {
                        repoStates = states
                        rebuild()
                    }
                    if (repoUpdatePending) updateRemotes()
                }
            }
        }
    }

    private fun visibleMergeRequests(): List<MergeRequest> {
        val repos = repoStates
        val mode = projectState.filterMode
        // until the repositories are known, show everything rather than an empty list
        if (mode == FilterMode.ALL || repos == null) return snapshot.mrs
        return BranchFilter.filter(snapshot.mrs, mode, repos)
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
            shown.isEmpty() && s.mrs.isNotEmpty() && projectState.filterMode != FilterMode.ALL -> {
                val repos = repoStates.orEmpty()
                val branches = BranchFilter.currentBranches(repos)
                val message = when {
                    repos.isEmpty() -> MyBundle.message("toolwindow.empty.noRemotes", gitLabHost())
                    projectState.filterMode == FilterMode.CURRENT_PROJECT -> MyBundle.message("toolwindow.empty.filtered")
                    branches.isEmpty() -> MyBundle.message("toolwindow.empty.noBranch")
                    else -> MyBundle.message("toolwindow.empty.noBranchMrs", branches.joinToString(", "))
                }
                emptyText.appendText(message)
                emptyText.appendSecondaryText(
                    MyBundle.message("toolwindow.empty.showAll"),
                    SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                ) { setFilterMode(FilterMode.ALL) }
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

    /** Tooltip of the cell under the mouse for the text columns; `null` = default behaviour. */
    private fun cellTooltip(event: MouseEvent): String? {
        val viewRow = table.rowAtPoint(event.point)
        val viewColumn = table.columnAtPoint(event.point)
        if (viewRow < 0 || viewColumn < 0) return null
        val column = table.columnModel.getColumn(viewColumn).identifier as? MrColumn ?: return null
        if (column == MrColumn.STATUS) return null
        val row = MrColumns.rowOf(table.tree.getPathForRow(viewRow)?.lastPathComponent) ?: return null
        return MrColumns.cellTooltip(row, column)
    }

    override fun dispose() {
        disposed = true
        ticker.stop()
    }

    companion object {
        private const val REPO_CHECK_TICKS = 4

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
