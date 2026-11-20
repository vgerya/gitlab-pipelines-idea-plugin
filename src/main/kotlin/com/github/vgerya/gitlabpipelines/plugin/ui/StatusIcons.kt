package com.github.vgerya.gitlabpipelines.plugin.ui

import com.github.vgerya.gitlabpipelines.core.Category
import com.github.vgerya.gitlabpipelines.core.ErrorKind
import com.github.vgerya.gitlabpipelines.core.GitLabException
import com.github.vgerya.gitlabpipelines.core.PipelineStatus
import com.github.vgerya.gitlabpipelines.core.TimeAgo
import com.github.vgerya.gitlabpipelines.plugin.MyBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.EmptyIcon
import java.awt.Color
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Icon

/** Pipeline status → icon / localized label; category → color; error → localized text. */
object StatusIcons {

    @JvmField
    val TOOL_WINDOW: Icon = IconLoader.getIcon("/icons/gitlabPipelines.svg", StatusIcons::class.java)

    fun icon(status: PipelineStatus?): Icon = when (status) {
        null -> EmptyIcon.ICON_16
        PipelineStatus.FAILED -> AllIcons.RunConfigurations.TestFailed
        PipelineStatus.RUNNING, PipelineStatus.CANCELING -> AllIcons.RunConfigurations.TestState.Run
        PipelineStatus.PENDING, PipelineStatus.CREATED, PipelineStatus.WAITING_FOR_RESOURCE,
        PipelineStatus.WAITING_FOR_CALLBACK, PipelineStatus.PREPARING, PipelineStatus.SCHEDULED,
        -> AllIcons.RunConfigurations.TestNotRan
        PipelineStatus.SUCCESS -> AllIcons.RunConfigurations.TestPassed
        PipelineStatus.MANUAL -> AllIcons.Actions.Execute
        PipelineStatus.CANCELED -> AllIcons.RunConfigurations.TestIgnored
        PipelineStatus.SKIPPED -> AllIcons.RunConfigurations.TestSkipped
        PipelineStatus.UNKNOWN -> AllIcons.RunConfigurations.TestUnknown
    }

    /** Russian label; for [PipelineStatus.UNKNOWN] the raw GitLab status is shown. */
    fun label(status: PipelineStatus?, rawStatus: String?): String = when (status) {
        null -> MyBundle.message("status.none")
        PipelineStatus.UNKNOWN -> rawStatus?.takeIf { it.isNotBlank() } ?: "unknown"
        PipelineStatus.FAILED -> MyBundle.message("status.failed")
        PipelineStatus.RUNNING -> MyBundle.message("status.running")
        PipelineStatus.CANCELING -> MyBundle.message("status.canceling")
        PipelineStatus.PENDING -> MyBundle.message("status.pending")
        PipelineStatus.CREATED -> MyBundle.message("status.created")
        PipelineStatus.WAITING_FOR_RESOURCE -> MyBundle.message("status.waiting_for_resource")
        PipelineStatus.WAITING_FOR_CALLBACK -> MyBundle.message("status.waiting_for_callback")
        PipelineStatus.PREPARING -> MyBundle.message("status.preparing")
        PipelineStatus.SCHEDULED -> MyBundle.message("status.scheduled")
        PipelineStatus.SUCCESS -> MyBundle.message("status.success")
        PipelineStatus.MANUAL -> MyBundle.message("status.manual")
        PipelineStatus.CANCELED -> MyBundle.message("status.canceled")
        PipelineStatus.SKIPPED -> MyBundle.message("status.skipped")
    }

    fun color(category: Category): Color = when (category) {
        Category.FAILED -> JBColor.RED
        Category.RUNNING -> JBColor.BLUE
        Category.PENDING -> JBColor.ORANGE
        Category.SUCCESS -> JBColor.GREEN
        Category.OTHER, Category.NONE -> JBColor.GRAY
    }

    fun errorText(e: GitLabException): String = when (e.kind) {
        ErrorKind.NOT_CONFIGURED -> MyBundle.message("error.NOT_CONFIGURED")
        ErrorKind.AUTH -> MyBundle.message("error.AUTH")
        ErrorKind.FORBIDDEN -> MyBundle.message("error.FORBIDDEN")
        ErrorKind.RATE_LIMIT -> MyBundle.message("error.RATE_LIMIT", (e.retryAfterSec ?: 60L).toString())
        ErrorKind.NETWORK -> MyBundle.message("error.NETWORK", e.message ?: "")
        ErrorKind.SERVER -> MyBundle.message("error.SERVER", (e.httpStatus ?: 500).toString())
        ErrorKind.GRAPHQL -> MyBundle.message("error.GRAPHQL")
        ErrorKind.BAD_RESPONSE -> MyBundle.message("error.BAD_RESPONSE")
    }

    fun needsSettings(e: GitLabException?): Boolean =
        e != null && (e.kind == ErrorKind.AUTH || e.kind == ErrorKind.FORBIDDEN || e.kind == ErrorKind.NOT_CONFIGURED)

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun relativeTime(then: Instant?, now: Instant = Instant.now()): String {
        if (then == null) return ""
        return when (val ago = TimeAgo.of(then, now)) {
            TimeAgo.JustNow -> MyBundle.message("time.justNow")
            is TimeAgo.Minutes -> MyBundle.message("time.minutes", ago.n.toString())
            is TimeAgo.Hours -> MyBundle.message("time.hours", ago.n.toString())
            is TimeAgo.Days -> MyBundle.message("time.days", ago.n.toString())
            is TimeAgo.Absolute -> DATE_FORMAT.format(ago.instant.atZone(ZoneId.systemDefault()))
        }
    }
}
