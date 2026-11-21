package com.github.vgerya.gitlabpipelines.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MrTreeTest {

    private fun m(
        id: Long,
        status: PipelineStatus?,
        project: String = "g/p",
        branch: String = "b$id",
        title: String = "MR $id",
        upd: String = "2024-05-0${id}T10:00:00Z",
        roles: Set<Role> = setOf(Role.AUTHOR),
    ): MergeRequest = mr(
        id,
        pipeline = status?.let { pipeline(id * 100, it, updatedAt = ts(upd)) },
        updatedAt = ts(upd),
        roles = roles,
    ).copy(projectPath = project, sourceBranch = branch, title = title)

    private val a = m(1, PipelineStatus.SUCCESS, project = "g/zeta", branch = "main", title = "beta")
    private val b = m(2, PipelineStatus.FAILED, project = "g/alpha", branch = "feature", title = "Alpha")
    private val c = m(3, null, project = "g/alpha", branch = "main", title = "gamma", roles = setOf(Role.REVIEWER))
    private val d = m(4, PipelineStatus.RUNNING, project = "G/beta", branch = "dev", title = "delta")
    private val e = m(5, PipelineStatus.PENDING, project = "g/zeta", branch = "main", title = "Epsilon", roles = setOf(Role.ASSIGNEE))
    private val all = listOf(a, b, c, d, e)

    private fun order(spec: SortSpec) = MrSort.sort(all, spec).map { it.iid }

    @Test
    fun defaultSortIsStatusThenUpdatedDesc() {
        assertEquals(listOf(2, 4, 5, 1, 3), order(SortSpec.DEFAULT))
        assertEquals(Summary.sort(all), MrSort.sort(all, SortSpec.DEFAULT))
        val f1 = m(6, PipelineStatus.FAILED, upd = "2024-05-06T10:00:00Z")
        val f2 = m(7, PipelineStatus.FAILED, upd = "2024-05-07T10:00:00Z")
        assertEquals(listOf(7, 6), MrSort.sort(listOf(f1, f2), SortSpec.DEFAULT).map { it.iid })
    }

    @Test
    fun statusDescendingKeepsNoneLast() {
        // category order reversed, MRs without pipeline still last
        assertEquals(listOf(1, 5, 4, 2, 3), order(SortSpec(MrColumn.STATUS, false)))
    }

    @Test
    fun sortByTextColumnsIsCaseInsensitive() {
        assertEquals(listOf(2, 1, 4, 5, 3), order(SortSpec(MrColumn.TITLE, true)))
        assertEquals(listOf(3, 5, 4, 1, 2), order(SortSpec(MrColumn.TITLE, false)))
        // projects: g/alpha (2 failed before 3 none), G/beta, g/zeta (5 pending before 1 success)
        assertEquals(listOf(2, 3, 4, 5, 1), order(SortSpec(MrColumn.PROJECT, true)))
        assertEquals(listOf(4, 2, 5, 1, 3), order(SortSpec(MrColumn.BRANCH, true)))
    }

    @Test
    fun sortByIidIsNumeric() {
        val x = m(2, null).copy(iid = 9)
        val y = m(3, null).copy(iid = 10)
        val z = m(4, null).copy(iid = 100)
        assertEquals(listOf(9, 10, 100), MrSort.sort(listOf(z, x, y), SortSpec(MrColumn.MR, true)).map { it.iid })
        assertEquals(listOf(100, 10, 9), MrSort.sort(listOf(z, x, y), SortSpec(MrColumn.MR, false)).map { it.iid })
    }

    @Test
    fun sortByUpdatedAndPipelineNullsLast() {
        val noTime = mr(9).copy(updatedAt = null)
        val list = all + noTime
        assertEquals(listOf(5, 4, 3, 2, 1, 9), MrSort.sort(list, SortSpec(MrColumn.UPDATED, false)).map { it.iid })
        assertEquals(listOf(1, 2, 3, 4, 5, 9), MrSort.sort(list, SortSpec(MrColumn.UPDATED, true)).map { it.iid })
        assertEquals(listOf(1, 2, 4, 5, 3, 9), MrSort.sort(list, SortSpec(MrColumn.PIPELINE, true)).map { it.iid })
        assertEquals(listOf(5, 4, 2, 1, 3, 9), MrSort.sort(list, SortSpec(MrColumn.PIPELINE, false)).map { it.iid })
        assertEquals(listOf(2, 4, 1, 5, 3), order(SortSpec(MrColumn.ROLE, true)))
    }

    @Test
    fun toggleSort() {
        assertEquals(SortSpec(MrColumn.STATUS, false), SortSpec.DEFAULT.toggle(MrColumn.STATUS))
        assertEquals(SortSpec(MrColumn.TITLE, true), SortSpec.DEFAULT.toggle(MrColumn.TITLE))
        assertEquals(SortSpec(MrColumn.UPDATED, false), SortSpec.DEFAULT.toggle(MrColumn.UPDATED))
        assertEquals(SortSpec(MrColumn.TITLE, false), SortSpec(MrColumn.TITLE, true).toggle(MrColumn.TITLE))
    }

    @Test
    fun columnAndGroupingParsing() {
        assertEquals(listOf(MrColumn.TITLE, MrColumn.STATUS), MrColumn.parseList("title, STATUS,bogus,title"))
        assertEquals("TITLE,STATUS", MrColumn.toCsv(listOf(MrColumn.TITLE, MrColumn.STATUS)))
        assertEquals(MrColumn.values().toList(), MrColumn.completeOrder(emptyList()))
        assertEquals(MrColumn.PIPELINE, MrColumn.completeOrder(listOf(MrColumn.PIPELINE)).first())
        assertEquals(MrColumn.values().size, MrColumn.completeOrder(listOf(MrColumn.PIPELINE)).size)
        assertEquals(Grouping.PROJECT_BRANCH, Grouping.parse("project_branch"))
        assertEquals(Grouping.NONE, Grouping.parse(null))
        assertEquals(Grouping.NONE, Grouping.parse("nonsense"))
    }

    @Test
    fun flatModeHasOnlyMrRows() {
        val rows = MrTreeBuilder.build(all, Grouping.NONE, SortSpec.DEFAULT)
        assertEquals(listOf(2, 4, 5, 1, 3), rows.map { (it as MergeRequestRow).mr.iid })
        assertTrue(rows.all { it.children.isEmpty() })
    }

    @Test
    fun groupByProjectWithWorstStatusAndCount() {
        val rows = MrTreeBuilder.build(all, Grouping.PROJECT, SortSpec.DEFAULT).map { it as GroupRow }
        assertEquals(listOf("g/alpha", "G/beta", "g/zeta"), rows.map { it.name })
        assertEquals(listOf(Category.FAILED, Category.RUNNING, Category.PENDING), rows.map { it.category })
        assertEquals(listOf(2, 1, 2), rows.map { it.count })
        assertEquals(listOf(2, 3), rows[0].children.map { (it as MergeRequestRow).mr.iid })
        assertEquals("project:g/alpha", rows[0].id)

        val byName = MrTreeBuilder.build(all, Grouping.PROJECT, SortSpec(MrColumn.PROJECT, false)).map { (it as GroupRow).name }
        assertEquals(listOf("g/zeta", "G/beta", "g/alpha"), byName)
        val otherKey = MrTreeBuilder.build(all, Grouping.PROJECT, SortSpec(MrColumn.TITLE, false)).map { (it as GroupRow).name }
        assertEquals(listOf("g/alpha", "G/beta", "g/zeta"), otherKey)
    }

    @Test
    fun groupByBranch() {
        val rows = MrTreeBuilder.build(all, Grouping.BRANCH, SortSpec.DEFAULT).map { it as GroupRow }
        assertEquals(listOf("feature", "dev", "main"), rows.map { it.name })
        assertEquals(3, rows[2].count)
        assertEquals(Category.PENDING, rows[2].category)
        assertEquals(GroupKind.BRANCH, rows[0].kind)
    }

    @Test
    fun groupByProjectAndBranch() {
        val rows = MrTreeBuilder.build(all, Grouping.PROJECT_BRANCH, SortSpec(MrColumn.PROJECT, true)).map { it as GroupRow }
        assertEquals(listOf("g/alpha", "G/beta", "g/zeta"), rows.map { it.name })
        val alphaBranches = rows[0].children.map { it as GroupRow }
        assertEquals(listOf("feature", "main"), alphaBranches.map { it.name })
        assertEquals("project:g/alpha/branch:main", alphaBranches[1].id)
        assertEquals(1, alphaBranches[1].count)
        val zeta = rows[2].children.single() as GroupRow
        assertEquals("main", zeta.name)
        assertEquals(2, zeta.count)
        assertEquals(2, rows[2].count)
        assertEquals(Category.PENDING, rows[2].category)
        assertEquals(7, MrTreeBuilder.flatten(rows).count { it is GroupRow })
    }

    @Test
    fun recentPipelinesBecomeChildrenNewestFirst() {
        val p1 = pipeline(10, PipelineStatus.SUCCESS)
        val p2 = pipeline(12, PipelineStatus.FAILED)
        val p3 = pipeline(11, PipelineStatus.CANCELED)
        val rows = MrTreeBuilder.build(listOf(a, b), Grouping.NONE, SortSpec.DEFAULT, mapOf(a.key to listOf(p1, p2, p3)))
        val aRow = rows.first { (it as MergeRequestRow).mr == a } as MergeRequestRow
        assertEquals(listOf(p2, p3, p1), aRow.children.map { it.pipeline })
        assertTrue(aRow.children.all { it.mr == a })
        val bRow = rows.first { (it as MergeRequestRow).mr == b }
        assertTrue(bRow.children.isEmpty())
        assertEquals("pl:${a.key}:${p2.id}", aRow.children[0].id)
        // without the map (toggle off): no children
        assertTrue(MrTreeBuilder.build(listOf(a), Grouping.NONE, SortSpec.DEFAULT, null).single().children.isEmpty())
    }

    @Test
    fun worstAndSearchText() {
        assertEquals(Category.NONE, MrTreeBuilder.worst(emptyList()))
        assertEquals(Category.FAILED, MrTreeBuilder.worst(listOf(Category.SUCCESS, Category.FAILED, Category.NONE)))
        val text = MrTreeBuilder.searchText(MergeRequestRow(b))
        assertTrue(text.contains("!2") && text.contains("Alpha") && text.contains("g/alpha") && text.contains("feature"), text)
        assertTrue(!text.contains("MergeRequest("), text)
        val pl = pipeline(77, PipelineStatus.SUCCESS).copy(ref = "main")
        assertEquals("#77 main", MrTreeBuilder.searchText(PipelineRow(b, pl)))
        assertEquals("g/alpha", MrTreeBuilder.searchText(GroupRow("project:g/alpha", GroupKind.PROJECT, "g/alpha", Category.NONE, 0, emptyList())))
        assertEquals(42L, GlobalIds.numericId("gid://gitlab/Ci::Pipeline/42"))
        assertEquals(null, GlobalIds.numericId("garbage/x"))
    }
}
