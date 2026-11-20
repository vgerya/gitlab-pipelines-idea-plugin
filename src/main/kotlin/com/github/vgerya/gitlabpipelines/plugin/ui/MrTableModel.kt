package com.github.vgerya.gitlabpipelines.plugin.ui

import com.github.vgerya.gitlabpipelines.core.MergeRequest
import com.github.vgerya.gitlabpipelines.core.Role
import com.github.vgerya.gitlabpipelines.plugin.MyBundle
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.Component
import javax.swing.SwingConstants
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/** Rows are expected to be pre-sorted with `Summary.sort`; columns are not sortable. */
class MrTableModel : ListTableModel<MergeRequest>(
    StatusColumn(),
    TextColumn(MyBundle.message("column.project")) { it.projectPath },
    TextColumn(MyBundle.message("column.mr"), preferredWidth = 60) { "!${it.iid}" },
    TextColumn(MyBundle.message("column.title")) { titleWithTags(it) },
    TextColumn(MyBundle.message("column.branch")) { it.sourceBranch },
    TextColumn(MyBundle.message("column.updated"), preferredWidth = 110) {
        StatusIcons.relativeTime(it.pipeline?.updatedAt ?: it.updatedAt)
    },
) {

    fun mergeRequestAt(row: Int): MergeRequest? = if (row in 0 until rowCount) getItem(row) else null

    companion object {
        fun titleWithTags(mr: MergeRequest): String {
            val sb = StringBuilder(mr.title)
            if (mr.draft) sb.append(" [").append(MyBundle.message("tag.draft")).append(']')
            if (Role.AUTHOR !in mr.roles) {
                if (Role.REVIEWER in mr.roles) sb.append(" [").append(MyBundle.message("tag.reviewer")).append(']')
                if (Role.ASSIGNEE in mr.roles) sb.append(" [").append(MyBundle.message("tag.assignee")).append(']')
            }
            return sb.toString()
        }
    }

    private class TextColumn(
        name: String,
        private val preferredWidth: Int = -1,
        private val extractor: (MergeRequest) -> String,
    ) : ColumnInfo<MergeRequest, String>(name) {
        override fun valueOf(item: MergeRequest): String = extractor(item)

        override fun getWidth(table: JTable): Int = if (preferredWidth > 0) JBUI.scale(preferredWidth) else -1
    }

    private class StatusColumn : ColumnInfo<MergeRequest, MergeRequest>(MyBundle.message("column.status")) {
        private val renderer = StatusRenderer()

        override fun valueOf(item: MergeRequest): MergeRequest = item

        override fun getRenderer(item: MergeRequest): TableCellRenderer = renderer

        override fun getWidth(table: JTable): Int = JBUI.scale(56)
    }

    private class StatusRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, null, isSelected, hasFocus, row, column)
            val mr = value as? MergeRequest
            val pipeline = mr?.pipeline
            icon = StatusIcons.icon(pipeline?.status)
            text = ""
            horizontalAlignment = SwingConstants.CENTER
            toolTipText = StatusIcons.label(pipeline?.status, pipeline?.rawStatus)
            return this
        }
    }
}
