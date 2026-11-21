package com.github.vgerya.gitlabpipelines.plugin.service

import com.github.vgerya.gitlabpipelines.core.Counts
import com.github.vgerya.gitlabpipelines.core.ErrorKind
import com.github.vgerya.gitlabpipelines.core.GitLabClient
import com.github.vgerya.gitlabpipelines.core.GitLabException
import com.github.vgerya.gitlabpipelines.core.MergeRequest
import com.github.vgerya.gitlabpipelines.core.Pipeline
import com.github.vgerya.gitlabpipelines.core.PipelineEvent
import com.github.vgerya.gitlabpipelines.core.PipelineStatus
import com.github.vgerya.gitlabpipelines.core.Seen
import com.github.vgerya.gitlabpipelines.core.StatusDiff
import com.github.vgerya.gitlabpipelines.core.Summary
import com.github.vgerya.gitlabpipelines.core.UrlUtil
import com.github.vgerya.gitlabpipelines.plugin.MyBundle
import com.github.vgerya.gitlabpipelines.plugin.settings.GitLabSettings
import com.github.vgerya.gitlabpipelines.plugin.settings.TokenStore
import com.github.vgerya.gitlabpipelines.plugin.settings.ViewSettings
import com.github.vgerya.gitlabpipelines.plugin.ui.StatusIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Immutable view of the current state, published via [PipelinesListener.TOPIC]. */
data class Snapshot(
    val mrs: List<MergeRequest>,
    val counts: Counts,
    val lastSuccess: Instant?,
    val error: GitLabException?,
    val loading: Boolean,
    /** Recent pipelines per MR key; filled only while «Показывать все пайплайны» is on. */
    val pipelines: Map<String, List<Pipeline>> = emptyMap(),
) {
    companion object {
        val INITIAL = Snapshot(emptyList(), Counts.EMPTY, null, null, false)
    }
}

/**
 * Polls GitLab in the background, keeps the latest [Snapshot], computes status transitions and
 * shows balloon notifications. All state is in memory: the first poll after IDE start (or after a
 * settings change) is a baseline and produces no notifications.
 */
@Service(Service.Level.APP)
class PipelinesService(private val cs: CoroutineScope) {

    @Volatile
    var snapshot: Snapshot = Snapshot.INITIAL
        private set

    /** `null` → the next successful poll is a baseline (no notifications). */
    @Volatile
    private var seen: Map<String, Seen>? = null

    @Volatile
    private var lastPollAt: Instant? = null

    @Volatile
    private var backoffUntil: Instant? = null

    @Volatile
    private var tokenCache: Pair<String, String?>? = null

    /** Incremented on settings change; results of polls started before are discarded. */
    private val generation = AtomicLong()
    private val started = AtomicBoolean(false)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        cs.launch(Dispatchers.IO) {
            var manual = true
            while (isActive) {
                pollOnce(manual)
                val timeoutMs = GitLabSettings.getInstance().pollSeconds * 1000L
                manual = withTimeoutOrNull(timeoutMs) { refreshRequests.receive() } != null
            }
        }
    }

    /** Polls as soon as possible, ignoring an active rate-limit backoff. */
    fun refreshNow() {
        ensureStarted()
        refreshRequests.trySend(Unit)
    }

    /** Called after the settings were applied (and the token stored). */
    fun settingsChanged() {
        generation.incrementAndGet()
        seen = null
        lastPollAt = null
        backoffUntil = null
        tokenCache = null
        val current = snapshot
        publish(current.copy(error = null))
        refreshNow()
    }

    private fun loadToken(baseUrl: String): String? {
        tokenCache?.let { (url, token) -> if (url == baseUrl) return token }
        val token = TokenStore.getToken(baseUrl)
        tokenCache = baseUrl to token
        return token
    }

    private fun pollOnce(manual: Boolean) {
        val gen = generation.get()
        val settings = GitLabSettings.getInstance()
        try {
            val baseUrl = UrlUtil.normalizeBaseUrl(settings.baseUrl)
            val token = baseUrl?.let { loadToken(it) }
            if (baseUrl == null || token.isNullOrBlank()) {
                seen = null
                publish(
                    Snapshot(
                        mrs = emptyList(),
                        counts = Counts.EMPTY,
                        lastSuccess = null,
                        error = GitLabException(ErrorKind.NOT_CONFIGURED),
                        loading = false,
                    ),
                )
                return
            }
            val pollStart = Instant.now()
            val backoff = backoffUntil
            if (!manual && backoff != null && pollStart.isBefore(backoff)) return

            publish(snapshot.copy(loading = true))
            val client = GitLabClient(baseUrl, token, IdeTransport.create())
            val result = client.fetchMergeRequests(settings.includeAssigned, settings.includeReview)
            if (gen != generation.get()) return // settings changed meanwhile; a new poll is queued

            val visible = Summary.sort(Summary.filter(result.mrs, settings.showDrafts))
            // Notifications are computed from head pipelines only; recent pipelines are display-only.
            val pipelines = if (ViewSettings.getInstance().showAllPipelines) loadRecentPipelines(client, visible) else emptyMap()
            if (gen != generation.get()) return
            val diff = StatusDiff.diff(seen, visible, settings.notifyPrefs(), lastPollAt)
            seen = diff.next
            lastPollAt = pollStart
            backoffUntil = null
            publish(Snapshot(visible, Summary.compute(visible), Instant.now(), null, false, pipelines))
            if (diff.events.isNotEmpty()) notify(diff.events)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitLabException) {
            if (gen != generation.get()) return
            LOG.info("GitLab poll failed: kind=${e.kind}, httpStatus=${e.httpStatus}")
            when (e.kind) {
                ErrorKind.RATE_LIMIT ->
                    backoffUntil = Instant.now().plusSeconds(e.retryAfterSec ?: GitLabClient.DEFAULT_RETRY_AFTER_SEC)
                // No notifications while the token is rejected; the next successful poll is a baseline.
                ErrorKind.AUTH -> seen = null
                else -> Unit
            }
            publish(snapshot.copy(error = e, loading = false))
        } catch (e: Exception) {
            if (gen != generation.get()) return
            LOG.info("GitLab poll failed: ${e.javaClass.name}")
            publish(
                snapshot.copy(
                    error = GitLabException(ErrorKind.BAD_RESPONSE, message = e.javaClass.simpleName),
                    loading = false,
                ),
            )
        }
    }

    /** Recent pipelines are optional: on failure keep the previous ones and do not fail the poll. */
    private fun loadRecentPipelines(client: GitLabClient, mrs: List<MergeRequest>): Map<String, List<Pipeline>> {
        return try {
            client.fetchRecentPipelines(mrs)
        } catch (e: GitLabException) {
            LOG.info("Loading recent pipelines failed: kind=${e.kind}, httpStatus=${e.httpStatus}")
            val keys = mrs.mapTo(HashSet()) { it.key }
            snapshot.pipelines.filterKeys { it in keys }
        }
    }

    private fun publish(newSnapshot: Snapshot) {
        snapshot = newSnapshot
        val app = ApplicationManager.getApplication()
        if (app.isDisposed) return
        app.messageBus.syncPublisher(PipelinesListener.TOPIC).snapshotChanged(newSnapshot)
    }

    private fun notify(events: List<PipelineEvent>) {
        ApplicationManager.getApplication().invokeLater {
            val project = targetProject()
            val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)
            for (event in events) {
                val mr = event.mr
                val title = StringUtil.escapeXmlEntities("${mr.projectPath} !${mr.iid}")
                val content = StringUtil.escapeXmlEntities(mr.title) + "<br>" +
                    StringUtil.escapeXmlEntities(StatusIcons.label(event.status, mr.pipeline?.rawStatus))
                val type = if (event.status == PipelineStatus.FAILED) NotificationType.ERROR else NotificationType.INFORMATION
                val notification = group.createNotification(title, content, type)
                notification.addAction(
                    NotificationAction.createSimpleExpiring(MyBundle.message("notification.openPipeline")) {
                        BrowserUtil.browse(event.url)
                    },
                )
                notification.addAction(
                    NotificationAction.createSimpleExpiring(MyBundle.message("notification.openMr")) {
                        BrowserUtil.browse(mr.webUrl)
                    },
                )
                notification.notify(project)
            }
        }
    }

    private fun targetProject(): Project? {
        val focused = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
        if (focused != null && !focused.isDisposed) return focused
        return ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
    }

    companion object {
        const val NOTIFICATION_GROUP_ID = "GitLab MR Pipelines"
        private val LOG = Logger.getInstance(PipelinesService::class.java)

        fun getInstance(): PipelinesService =
            ApplicationManager.getApplication().getService(PipelinesService::class.java)
    }
}
