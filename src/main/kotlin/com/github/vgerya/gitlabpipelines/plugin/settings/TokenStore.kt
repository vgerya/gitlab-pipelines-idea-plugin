package com.github.vgerya.gitlabpipelines.plugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Personal access token storage in the IDE PasswordSafe, keyed by the normalised base URL.
 * Calls may block (OS keychain / KeePass): use from a background thread only.
 * The token is never logged.
 */
object TokenStore {

    private const val SUBSYSTEM = "GitLab MR Pipelines"

    private fun attributes(baseUrl: String) = CredentialAttributes(generateServiceName(SUBSYSTEM, baseUrl))

    fun getToken(baseUrl: String): String? {
        if (baseUrl.isBlank()) return null
        return PasswordSafe.instance.getPassword(attributes(baseUrl))?.takeIf { it.isNotBlank() }
    }

    fun setToken(baseUrl: String, token: String?) {
        if (baseUrl.isBlank()) return
        PasswordSafe.instance.setPassword(attributes(baseUrl), token?.trim()?.takeIf { it.isNotEmpty() })
    }
}
