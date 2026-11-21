package com.github.vgerya.gitlabpipelines.core

import java.time.Instant

/** Columns of the tool window table; also the sort keys. */
enum class MrColumn {
    STATUS, PROJECT, MR, TITLE, BRANCH, ROLE, UPDATED, PIPELINE;

    companion object {
        /** Columns shown by default (ROLE and PIPELINE are opt-in). */
        val DEFAULT_VISIBLE: List<MrColumn> = listOf(STATUS, PROJECT, MR, TITLE, BRANCH, UPDATED)

        fun parse(s: String?): MrColumn? = s?.trim()?.uppercase()?.let { v -> values().firstOrNull { it.name == v } }

        /** Comma-separated names → columns (unknown names and duplicates skipped). */
        fun parseList(csv: String?): List<MrColumn> =
            csv.orEmpty().split(',').mapNotNull { parse(it) }.distinct()

        fun toCsv(columns: Collection<MrColumn>): String = columns.joinToString(",") { it.name }

        /** [saved] order first, then any columns missing from it in declaration order. */
        fun completeOrder(saved: List<MrColumn>): List<MrColumn> = (saved + values()).distinct()
    }
}

enum class Grouping {
    NONE, PROJECT, BRANCH, PROJECT_BRANCH;

    companion object {
        fun parse(s: String?): Grouping = values().firstOrNull { it.name == s?.trim()?.uppercase() } ?: NONE
    }
}

data class SortSpec(val column: MrColumn, val ascending: Boolean) {
    /** Header click: same column flips the direction; another column starts ascending (UPDATED: newest first). */
    fun toggle(clicked: MrColumn): SortSpec =
        if (clicked == column) copy(ascending = !ascending) else SortSpec(clicked, clicked != MrColumn.UPDATED)

    companion object {
        /** Status (failed first), ties broken by update time, newest first. */
        val DEFAULT = SortSpec(MrColumn.STATUS, true)
    }
}

enum class GroupKind { PROJECT, BRANCH }

/** A row of the tree table. */
sealed class MrRow {
    abstract val id: String
    abstract val children: List<MrRow>
}

data class GroupRow(
    override val id: String,
    val kind: GroupKind,
    val name: String,
    /** Worst category among the MRs of the group. */
    val category: Category,
    /** Number of merge requests in the group (recursively). */
    val count: Int,
    override val children: List<MrRow>,
) : MrRow()

data class MergeRequestRow(val mr: MergeRequest, override val children: List<PipelineRow> = emptyList()) : MrRow() {
    override val id: String get() = "mr:" + mr.key
}

data class PipelineRow(val mr: MergeRequest, val pipeline: Pipeline) : MrRow() {
    override val id: String get() = "pl:" + mr.key + ":" + pipeline.id
    override val children: List<MrRow> get() = emptyList()
}

object MrSort {

    private val NAME_ORDER: Comparator<String> = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())

    fun effectiveUpdatedAt(mr: MergeRequest): Instant? = mr.pipeline?.updatedAt ?: mr.updatedAt

    private fun roleRank(mr: MergeRequest): Int = mr.roles.minOfOrNull { it.ordinal } ?: Int.MAX_VALUE

    /**
     * Comparator for [spec]. `null` values (no pipeline, no time) are always last, whatever the
     * direction. Ties are broken by the default order (status, then updated desc, then key).
     */
    fun comparator(spec: SortSpec): Comparator<MergeRequest> {
        val primary: Comparator<MergeRequest> = when (spec.column) {
            // MRs without a pipeline stay last in both directions
            MrColumn.STATUS -> directed(spec.ascending) { mr -> mr.category.order.takeIf { mr.category != Category.NONE } }
            MrColumn.PROJECT -> directedWith(spec.ascending, NAME_ORDER) { it.projectPath }
            MrColumn.MR -> directed(spec.ascending) { it.iid }
            MrColumn.TITLE -> directedWith(spec.ascending, NAME_ORDER) { it.title }
            MrColumn.BRANCH -> directedWith(spec.ascending, NAME_ORDER) { it.sourceBranch }
            MrColumn.ROLE -> directed(spec.ascending) { roleRank(it) }
            MrColumn.UPDATED -> directed(spec.ascending) { effectiveUpdatedAt(it) }
            MrColumn.PIPELINE -> directed(spec.ascending) { GlobalIds.numericId(it.pipeline?.id) }
        }
        return primary.then(Summary.COMPARATOR)
    }

    fun sort(mrs: List<MergeRequest>, spec: SortSpec): List<MergeRequest> = mrs.sortedWith(comparator(spec))

    private fun <T : Comparable<T>> directed(ascending: Boolean, key: (MergeRequest) -> T?): Comparator<MergeRequest> =
        directedWith(ascending, Comparator.naturalOrder(), key)

    private fun <T> directedWith(
        ascending: Boolean,
        order: Comparator<in T>,
        key: (MergeRequest) -> T?,
    ): Comparator<MergeRequest> = Comparator { a, b ->
        val ka = key(a)
        val kb = key(b)
        when {
            ka == null && kb == null -> 0
            ka == null -> 1
            kb == null -> -1
            ascending -> order.compare(ka, kb)
            else -> order.compare(kb, ka)
        }
    }
}

object MrTreeBuilder {

    /** Worst (lowest order) category; [Category.NONE] for an empty list. */
    fun worst(categories: Iterable<Category>): Category = categories.minByOrNull { it.order } ?: Category.NONE

    /**
     * Builds the rows for the tool window.
     *
     * @param recentPipelines `null` → MR rows have no children (only the head pipeline is shown);
     *   otherwise each MR row gets its recent pipelines (newest first) as children.
     */
    fun build(
        mrs: List<MergeRequest>,
        grouping: Grouping,
        sort: SortSpec,
        recentPipelines: Map<String, List<Pipeline>>? = null,
    ): List<MrRow> {
        val sorted = MrSort.sort(mrs, sort)
        fun leaves(list: List<MergeRequest>): List<MrRow> = list.map { mr ->
            val children = recentPipelines?.get(mr.key).orEmpty()
                .sortedWith(compareByDescending<Pipeline, Long?>(nullsFirst()) { GlobalIds.numericId(it.id) })
                .map { PipelineRow(mr, it) }
            MergeRequestRow(mr, children)
        }
        return when (grouping) {
            Grouping.NONE -> leaves(sorted)
            Grouping.PROJECT -> groups(sorted, GroupKind.PROJECT, "project:", sort, { it.projectPath }) { leaves(it) }
            Grouping.BRANCH -> groups(sorted, GroupKind.BRANCH, "branch:", sort, { it.sourceBranch }) { leaves(it) }
            Grouping.PROJECT_BRANCH -> groups(sorted, GroupKind.PROJECT, "project:", sort, { it.projectPath }) { inProject ->
                val projectId = "project:" + inProject.first().projectPath
                groups(inProject, GroupKind.BRANCH, "$projectId/branch:", sort, { it.sourceBranch }) { leaves(it) }
            }
        }
    }

    private fun groups(
        sortedMrs: List<MergeRequest>,
        kind: GroupKind,
        idPrefix: String,
        sort: SortSpec,
        key: (MergeRequest) -> String,
        children: (List<MergeRequest>) -> List<MrRow>,
    ): List<MrRow> {
        // LinkedHashMap keeps the MR sort order inside each group
        val byKey = LinkedHashMap<String, MutableList<MergeRequest>>()
        for (mr in sortedMrs) byKey.getOrPut(key(mr)) { ArrayList() } += mr
        val rows = byKey.map { (name, list) ->
            GroupRow(
                id = idPrefix + name,
                kind = kind,
                name = name,
                category = worst(list.map { it.category }),
                count = list.size,
                children = children(list),
            )
        }
        return rows.sortedWith(groupComparator(kind, sort))
    }

    private fun groupComparator(kind: GroupKind, sort: SortSpec): Comparator<GroupRow> {
        val byName: Comparator<GroupRow> = Comparator { a, b ->
            String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name).takeIf { it != 0 } ?: a.name.compareTo(b.name)
        }
        val nameMatchesSort = (kind == GroupKind.PROJECT && sort.column == MrColumn.PROJECT) ||
            (kind == GroupKind.BRANCH && sort.column == MrColumn.BRANCH)
        return when {
            sort.column == MrColumn.STATUS -> {
                // groups without any pipeline stay last in both directions (like MR rows)
                val byStatus = compareBy<GroupRow> { it.category.order }
                compareBy<GroupRow> { it.category == Category.NONE }
                    .then(if (sort.ascending) byStatus else byStatus.reversed())
                    .then(byName)
            }
            nameMatchesSort -> if (sort.ascending) byName else byName.reversed()
            else -> byName
        }
    }

    /** Text used by the speed search (never the `toString` of the model). */
    fun searchText(row: MrRow): String = when (row) {
        is GroupRow -> row.name
        is MergeRequestRow -> "!${row.mr.iid} ${row.mr.title} ${row.mr.projectPath} ${row.mr.sourceBranch}"
        is PipelineRow -> "#${row.pipeline.iid ?: GlobalIds.numericId(row.pipeline.id) ?: ""} ${row.pipeline.ref.orEmpty()}"
    }

    /** All rows in depth-first order (helper for selection restore and tests). */
    fun flatten(rows: List<MrRow>): List<MrRow> = buildList {
        fun walk(list: List<MrRow>) {
            for (r in list) {
                add(r)
                walk(r.children)
            }
        }
        walk(rows)
    }
}
