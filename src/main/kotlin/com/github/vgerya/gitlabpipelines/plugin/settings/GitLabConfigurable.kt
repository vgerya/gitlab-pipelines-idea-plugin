package com.github.vgerya.gitlabpipelines.plugin.settings

import com.github.vgerya.gitlabpipelines.core.ErrorKind
import com.github.vgerya.gitlabpipelines.core.GitLabClient
import com.github.vgerya.gitlabpipelines.core.GitLabException
import com.github.vgerya.gitlabpipelines.core.UrlUtil
import com.github.vgerya.gitlabpipelines.plugin.MyBundle
import com.github.vgerya.gitlabpipelines.plugin.service.IdeTransport
import com.github.vgerya.gitlabpipelines.plugin.service.PipelinesService
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/** Settings | Tools | GitLab MR Pipelines. */
class GitLabConfigurable : Configurable {

    private val urlField = JBTextField()
    private val tokenField = JBPasswordField()
    private val tokenHint = JBLabel()
    private val tokenLink = ActionLink("") { BrowserUtil.browse(tokenPageUrl()) }
    private val pollSpinner = JBIntSpinner(
        GitLabSettings.DEFAULT_POLL_SECONDS,
        GitLabSettings.MIN_POLL_SECONDS,
        GitLabSettings.MAX_POLL_SECONDS,
        10,
    )
    private val notifyFailed = JBCheckBox(MyBundle.message("settings.notify.failed"))
    private val notifySuccess = JBCheckBox(MyBundle.message("settings.notify.success"))
    private val notifyCanceled = JBCheckBox(MyBundle.message("settings.notify.canceled"))
    private val includeAssigned = JBCheckBox(MyBundle.message("settings.include.assigned"))
    private val includeReview = JBCheckBox(MyBundle.message("settings.include.review"))
    private val showDrafts = JBCheckBox(MyBundle.message("settings.showDrafts"))
    private val testButton = JButton(MyBundle.message("settings.test"))
    private val testResult = JBLabel()

    /** Token as last loaded from / saved to PasswordSafe; `null` until loaded. */
    @Volatile
    private var storedToken: String? = null

    @Volatile
    private var disposed = false

    private val settings: GitLabSettings get() = GitLabSettings.getInstance()

    override fun getDisplayName(): String = MyBundle.message("settings.displayName")

    init {
        urlField.emptyText.setText(MyBundle.message("settings.url.hint"))
        urlField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                updateTokenLink()
            }
        })
        tokenHint.foreground = UIUtil.getContextHelpForeground()
        testButton.addActionListener { testConnection() }
    }

    override fun createComponent(): JComponent {
        disposed = false
        return panel {
            row(MyBundle.message("settings.url")) {
                cell(urlField).columns(COLUMNS_LARGE)
            }
            row(MyBundle.message("settings.token")) {
                cell(tokenField).columns(COLUMNS_LARGE)
            }
            row("") {
                cell(tokenHint)
                cell(tokenLink)
            }
            row(MyBundle.message("settings.poll")) {
                cell(pollSpinner)
            }
            group(MyBundle.message("settings.notify.group")) {
                row { cell(notifyFailed) }
                row { cell(notifySuccess) }
                row { cell(notifyCanceled) }
            }
            row { cell(includeAssigned) }
            row { cell(includeReview) }
            row { cell(showDrafts) }
            row {
                cell(testButton)
                cell(testResult)
            }
        }
    }

    private fun tokenPageUrl(): String {
        val base = UrlUtil.normalizeBaseUrl(urlField.text) ?: "https://gitlab.com"
        return "$base/-/user_settings/personal_access_tokens"
    }

    private fun updateTokenLink() {
        tokenHint.text = MyBundle.message("settings.token.comment", "")
        tokenLink.text = tokenPageUrl()
    }

    private fun typedToken(): String = String(tokenField.password).trim()

    override fun isModified(): Boolean {
        val s = settings.getState()
        if (urlField.text.trim() != s.baseUrl.orEmpty()) return true
        if (pollSpinner.number != s.pollSeconds) return true
        if (notifyFailed.isSelected != s.notifyFailed) return true
        if (notifySuccess.isSelected != s.notifySuccess) return true
        if (notifyCanceled.isSelected != s.notifyCanceled) return true
        if (includeAssigned.isSelected != s.includeAssigned) return true
        if (includeReview.isSelected != s.includeReview) return true
        if (showDrafts.isSelected != s.showDrafts) return true
        val typed = typedToken()
        return typed != storedToken.orEmpty()
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val rawUrl = urlField.text.trim()
        val token = typedToken()
        val url: String
        if (rawUrl.isEmpty() && token.isEmpty()) {
            url = "" // explicitly not configured
        } else {
            url = UrlUtil.normalizeBaseUrl(rawUrl) ?: throw ConfigurationException(MyBundle.message("settings.error.url"))
            if (token.isEmpty()) throw ConfigurationException(MyBundle.message("settings.error.token"))
        }
        val poll = pollSpinner.number
        if (poll < GitLabSettings.MIN_POLL_SECONDS || poll > GitLabSettings.MAX_POLL_SECONDS) {
            throw ConfigurationException(MyBundle.message("settings.error.poll"))
        }

        val s = settings.getState()
        s.baseUrl = url
        s.pollSeconds = poll
        s.notifyFailed = notifyFailed.isSelected
        s.notifySuccess = notifySuccess.isSelected
        s.notifyCanceled = notifyCanceled.isSelected
        s.includeAssigned = includeAssigned.isSelected
        s.includeReview = includeReview.isSelected
        s.showDrafts = showDrafts.isSelected
        urlField.text = url

        storedToken = token
        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            if (url.isNotEmpty()) TokenStore.setToken(url, token)
            PipelinesService.getInstance().settingsChanged()
        })
    }

    override fun reset() {
        val s = settings.getState()
        val url = s.baseUrl.orEmpty()
        urlField.text = url
        pollSpinner.number = s.pollSeconds.coerceIn(GitLabSettings.MIN_POLL_SECONDS, GitLabSettings.MAX_POLL_SECONDS)
        notifyFailed.isSelected = s.notifyFailed
        notifySuccess.isSelected = s.notifySuccess
        notifyCanceled.isSelected = s.notifyCanceled
        includeAssigned.isSelected = s.includeAssigned
        includeReview.isSelected = s.includeReview
        showDrafts.isSelected = s.showDrafts
        testResult.text = ""
        testResult.icon = null
        updateTokenLink()

        tokenField.text = ""
        storedToken = null
        if (url.isEmpty()) {
            storedToken = ""
            return
        }
        // PasswordSafe may block: load in background, then fill the field on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            val token = TokenStore.getToken(url).orEmpty()
            ApplicationManager.getApplication().invokeLater({
                if (disposed || urlField.text.trim() != url) return@invokeLater
                storedToken = token
                if (tokenField.password.isEmpty()) tokenField.text = token
            }, ModalityState.any())
        })
    }

    private fun testConnection() {
        val url = urlField.text.trim()
        val token = typedToken()
        testResult.icon = null
        testResult.text = MyBundle.message("settings.test.running")
        testButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            val (ok, text) = try {
                val user = GitLabClient(url, token, IdeTransport.create()).getUser()
                true to MyBundle.message("settings.test.ok", user.username, user.name)
            } catch (e: GitLabException) {
                false to testErrorText(e)
            } catch (e: Exception) {
                false to MyBundle.message("error.NETWORK", e.javaClass.simpleName)
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                testButton.isEnabled = true
                testResult.icon = if (ok) AllIcons.General.InspectionsOK else AllIcons.General.Error
                testResult.text = text
            }, ModalityState.any())
        })
    }

    private fun testErrorText(e: GitLabException): String = when (e.kind) {
        ErrorKind.NOT_CONFIGURED ->
            if (UrlUtil.normalizeBaseUrl(urlField.text) == null) {
                MyBundle.message("settings.error.url")
            } else {
                MyBundle.message("settings.error.token")
            }
        ErrorKind.AUTH -> MyBundle.message("error.AUTH")
        ErrorKind.FORBIDDEN -> MyBundle.message("error.FORBIDDEN")
        ErrorKind.NETWORK -> MyBundle.message("error.NETWORK", e.message ?: "")
        ErrorKind.RATE_LIMIT -> MyBundle.message("error.RATE_LIMIT", (e.retryAfterSec ?: 60L).toString())
        ErrorKind.SERVER -> MyBundle.message("error.SERVER", (e.httpStatus ?: 500).toString())
        ErrorKind.GRAPHQL -> MyBundle.message("error.GRAPHQL")
        ErrorKind.BAD_RESPONSE -> MyBundle.message("error.BAD_RESPONSE")
    }

    override fun disposeUIResources() {
        disposed = true
    }
}
