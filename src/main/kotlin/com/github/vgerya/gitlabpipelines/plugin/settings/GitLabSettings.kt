package com.github.vgerya.gitlabpipelines.plugin.settings

import com.github.vgerya.gitlabpipelines.core.NotifyPrefs
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level settings. The token is NOT stored here (see [TokenStore]).
 * Registered as `applicationService` in plugin.xml.
 */
@State(name = "GitLabMrPipelinesSettings", storages = [Storage("gitlabMrPipelines.xml")])
class GitLabSettings : SimplePersistentStateComponent<GitLabSettings.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        var baseUrl by string("")
        var pollSeconds by property(DEFAULT_POLL_SECONDS)
        var notifyFailed by property(true)
        var notifySuccess by property(true)
        var notifyCanceled by property(false)
        var includeAssigned by property(false)
        var includeReview by property(false)
        var showDrafts by property(true)
    }

    val baseUrl: String get() = getState().baseUrl.orEmpty()
    val pollSeconds: Int get() = getState().pollSeconds.coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS)
    val includeAssigned: Boolean get() = getState().includeAssigned
    val includeReview: Boolean get() = getState().includeReview
    val showDrafts: Boolean get() = getState().showDrafts

    fun notifyPrefs(): NotifyPrefs = NotifyPrefs(
        failed = getState().notifyFailed,
        success = getState().notifySuccess,
        canceled = getState().notifyCanceled,
    )

    companion object {
        const val DEFAULT_POLL_SECONDS = 60
        const val MIN_POLL_SECONDS = 30
        const val MAX_POLL_SECONDS = 3600

        fun getInstance(): GitLabSettings =
            ApplicationManager.getApplication().getService(GitLabSettings::class.java)
    }
}
