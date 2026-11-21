package com.github.vgerya.gitlabpipelines.plugin.ui

import com.github.vgerya.gitlabpipelines.core.Category
import com.github.vgerya.gitlabpipelines.core.GlobalIds
import com.github.vgerya.gitlabpipelines.core.GroupRow
import com.github.vgerya.gitlabpipelines.core.MergeRequest
import com.github.vgerya.gitlabpipelines.core.MergeRequestRow
import com.github.vgerya.gitlabpipelines.core.MrColumn
import com.github.vgerya.gitlabpipelines.core.MrRow
import com.github.vgerya.gitlabpipelines.core.MrSort
import com.github.vgerya.gitlabpipelines.core.Pipeline
import com.github.vgerya.gitlabpipelines.core.PipelineRow
import com.github.vgerya.gitlabpipelines.core.Role
import com.github.vgerya.gitlabpipelines.plugin.MyBundle
import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.EmptyIcon
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** Column definitions of the tool window tree table; model index == [MrColumn.ordinal]. */
object MrColumns {

    fun title(column: MrColumn): String = when (column) {
        MrColumn.STATUS -> MyBundle.message("column.status")
        MrColumn.PROJECT -> MyBundle.message("column.project")
        MrColumn.MR -> MyBundle.message("column.mr")
        MrColumn.TITLE -> MyBundle.message("column.title")
        MrColumn.BRANCH -> MyBundle.message("column.branch")
        MrColumn.ROLE -> MyBundle.message("column.role")
        MrColumn.UPDATED -> MyBundle.message("column.updated")
        MrColumn.PIPELINE -> MyBundle.message("column.pipeline")
    }

    /** Default width in unscaled pixels. */
    fun defaultWidth(column: MrColumn): Int = when (column) {
        MrColumn.STATUS -> 170
        MrColumn.PROJECT -> 160
        MrColumn.MR -> 60
        MrColumn.TITLE -> 320
        MrColumn.BRANCH -> 160
        MrColumn.ROLE -> 110
        MrColumn.UPDATED -> 110
        MrColumn.PIPELINE -> 80
    }

    /** STATUS is the tree column (renders icon + label via [StatusTreeRenderer]); the others are text. */
    fun createColumnInfos(): Array<ColumnInfo<*, *>> = MrColumn.values().map<MrColumn, ColumnInfo<*, *>> { column ->
        if (column == MrColumn.STATUS) {
            TreeColumnInfo(title(column))
        } else {
            TextColumn(column)
        }
    }.toTypedArray()

    fun rowOf(node: Any?): MrRow? = (node as? DefaultMutableTreeNode)?.userObject as? MrRow

    private class TextColumn(private val column: MrColumn) : ColumnInfo<Any?, String>(title(column)) {
        override fun valueOf(item: Any?): String = rowOf(item)?.let { cellText(it, column) }.orEmpty()
    }

    fun cellText(row: MrRow, column: MrColumn): String = when (row) {
        is GroupRow -> ""
        is MergeRequestRow -> mrText(row.mr, column)
        is PipelineRow -> pipelineText(row.pipeline, column)
    }

    private fun mrText(mr: MergeRequest, column: MrColumn): String = when (column) {
        MrColumn.STATUS -> StatusIcons.label(mr.pipeline?.status, mr.pipeline?.rawStatus)
        MrColumn.PROJECT -> mr.projectPath
        MrColumn.MR -> "!${mr.iid}"
        MrColumn.TITLE -> titleWithTags(mr)
        MrColumn.BRANCH -> mr.sourceBranch
        MrColumn.ROLE -> roles(mr)
        MrColumn.UPDATED -> StatusIcons.relativeTime(MrSort.effectiveUpdatedAt(mr))
        MrColumn.PIPELINE -> mr.pipeline?.let { pipelineNumber(it) }.orEmpty()
    }

    private fun pipelineText(p: Pipeline, column: MrColumn): String = when (column) {
        MrColumn.STATUS -> StatusIcons.label(p.status, p.rawStatus)
        MrColumn.TITLE -> p.sha?.take(8).orEmpty()
        MrColumn.BRANCH -> p.ref.orEmpty()
        MrColumn.UPDATED -> StatusIcons.relativeTime(p.updatedAt ?: p.createdAt)
        MrColumn.PIPELINE -> pipelineNumber(p)
        MrColumn.PROJECT, MrColumn.MR, MrColumn.ROLE -> ""
    }

    fun pipelineNumber(p: Pipeline): String = "#" + (p.iid ?: GlobalIds.numericId(p.id)?.toString() ?: "?")

    private fun roles(mr: MergeRequest): String = Role.values().filter { it in mr.roles }.joinToString(", ") {
        when (it) {
            Role.AUTHOR -> MyBundle.message("role.author")
            Role.ASSIGNEE -> MyBundle.message("role.assignee")
            Role.REVIEWER -> MyBundle.message("role.reviewer")
        }
    }

    fun titleWithTags(mr: MergeRequest): String {
        val sb = StringBuilder(mr.title)
        if (mr.draft) sb.append(" [").append(MyBundle.message("tag.draft")).append(']')
        if (Role.AUTHOR !in mr.roles) {
            if (Role.REVIEWER in mr.roles) sb.append(" [").append(MyBundle.message("tag.reviewer")).append(']')
            if (Role.ASSIGNEE in mr.roles) sb.append(" [").append(MyBundle.message("tag.assignee")).append(']')
        }
        return sb.toString()
    }

    fun categoryIcon(category: Category): Icon = when (category) {
        Category.FAILED -> AllIcons.RunConfigurations.TestFailed
        Category.RUNNING -> AllIcons.RunConfigurations.TestState.Run
        Category.PENDING -> AllIcons.RunConfigurations.TestNotRan
        Category.SUCCESS -> AllIcons.RunConfigurations.TestPassed
        Category.OTHER -> AllIcons.RunConfigurations.TestIgnored
        Category.NONE -> EmptyIcon.ICON_16
    }
}

/**
 * Tree node of the table. `toString` is what Swing falls back to (copy via Ctrl+C, accessibility,
 * default renderers), so it must never be the data class `toString` of the row.
 */
class RowNode(private val row: MrRow) : DefaultMutableTreeNode(row, row.children.isNotEmpty()) {
    override fun toString(): String = when (row) {
        is GroupRow -> row.name
        else -> MrColumns.cellText(row, MrColumn.STATUS)
    }
}

/** Renders the tree (STATUS) column: status icon + Russian label; group rows: worst status icon + name + count. */
class StatusTreeRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (val r = MrColumns.rowOf(value)) {
            is GroupRow -> {
                icon = MrColumns.categoryIcon(r.category)
                append(r.name.ifEmpty { "—" }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${r.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is MergeRequestRow -> {
                val p = r.mr.pipeline
                icon = StatusIcons.icon(p?.status)
                val label = StatusIcons.label(p?.status, p?.rawStatus)
                append(label, if (p == null) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                toolTipText = label
            }
            is PipelineRow -> {
                icon = StatusIcons.icon(r.pipeline.status)
                append(StatusIcons.label(r.pipeline.status, r.pipeline.rawStatus))
                append("  " + MrColumns.pipelineNumber(r.pipeline), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            null -> Unit
        }
    }
}
