package com.github.vgerya.gitlabpipelines.plugin.service

import com.github.vgerya.gitlabpipelines.core.HttpTransport
import com.github.vgerya.gitlabpipelines.core.JdkHttpTransport
import com.intellij.util.net.ssl.CertificateManager
import java.net.Authenticator
import java.net.ProxySelector
import java.net.http.HttpClient

/**
 * [JdkHttpTransport] configured with the IDE SSL context (honours certificates trusted in
 * Settings | Tools | Server Certificates, e.g. a corporate CA) and the IDE proxy settings
 * (the IDE installs its own default [ProxySelector] and [Authenticator]).
 */
object IdeTransport {

    fun create(): HttpTransport {
        val builder = HttpClient.newBuilder()
            .connectTimeout(JdkHttpTransport.DEFAULT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .sslContext(CertificateManager.getInstance().sslContext)
        ProxySelector.getDefault()?.let { builder.proxy(it) }
        Authenticator.getDefault()?.let { builder.authenticator(it) }
        return JdkHttpTransport(builder.build(), JdkHttpTransport.DEFAULT_TIMEOUT)
    }
}
