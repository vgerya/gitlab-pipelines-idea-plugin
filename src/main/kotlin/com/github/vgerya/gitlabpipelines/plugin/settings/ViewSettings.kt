package com.github.vgerya.gitlabpipelines.plugin.settings

import com.github.vgerya.gitlabpipelines.core.Grouping
import com.github.vgerya.gitlabpipelines.core.MrColumn
import com.github.vgerya.gitlabpipelines.core.SortSpec
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** Application-level view options of the tool window: columns, sort, grouping, recent pipelines. */
@Service(Service.Level.APP)
@State(name = "GitLabMrPipelinesView", storages = [Storage("gitlabMrPipelinesView.xml")])
class ViewSettings : SimplePersistentStateComponent<ViewSettings.ViewState>(ViewState()) {

    class ViewState : BaseState() {
        var visibleColumns by string(MrColumn.toCsv(MrColumn.DEFAULT_VISIBLE))
        /** Columns that existed when [visibleColumns] was saved; empty = saved by 1.1. */
        var knownColumns by string("")
        var columnOrder by string("")
        /** `COLUMN=width` pairs separated by commas. */
        var columnWidths by string("")
        var sortColumn by string(SortSpec.DEFAULT.column.name)
        var sortAscending by property(SortSpec.DEFAULT.ascending)
        var grouping by string(Grouping.NONE.name)
        var showAllPipelines by property(false)
    }

    /** Always contains [MrColumn.STATUS] (the tree column). */
    var visibleColumns: Set<MrColumn>
        get() = MrColumn.effectiveVisible(
            MrColumn.parseList(state.visibleColumns),
            // a never-changed value is the current default, which knows every column
            if (state.visibleColumns == null || state.visibleColumns == MrColumn.toCsv(MrColumn.DEFAULT_VISIBLE)) {
                MrColumn.values().toList()
            } else {
                MrColumn.parseList(state.knownColumns)
            },
        )
        set(value) {
            state.visibleColumns = MrColumn.toCsv(MrColumn.values().filter { it in value || it == MrColumn.STATUS })
            state.knownColumns = MrColumn.toCsv(MrColumn.values().toList())
        }

    var columnOrder: List<MrColumn>
        get() = MrColumn.completeOrder(MrColumn.parseList(state.columnOrder))
        set(value) {
            state.columnOrder = MrColumn.toCsv(MrColumn.completeOrder(value))
        }

    var columnWidths: Map<MrColumn, Int>
        get() = state.columnWidths.orEmpty().split(',').mapNotNull { pair ->
            val column = MrColumn.parse(pair.substringBefore('=', "")) ?: return@mapNotNull null
            val width = pair.substringAfter('=', "").trim().toIntOrNull()?.takeIf { it in 16..5000 } ?: return@mapNotNull null
            column to width
        }.toMap()
        set(value) {
            state.columnWidths = value.entries.joinToString(",") { "${it.key.name}=${it.value}" }
        }

    var sort: SortSpec
        get() = SortSpec(MrColumn.parse(state.sortColumn) ?: SortSpec.DEFAULT.column, state.sortAscending)
        set(value) {
            state.sortColumn = value.column.name
            state.sortAscending = value.ascending
        }

    var grouping: Grouping
        get() = Grouping.parse(state.grouping)
        set(value) {
            state.grouping = value.name
        }

    var showAllPipelines: Boolean
        get() = state.showAllPipelines
        set(value) {
            state.showAllPipelines = value
        }

    companion object {
        fun getInstance(): ViewSettings = ApplicationManager.getApplication().getService(ViewSettings::class.java)
    }
}
