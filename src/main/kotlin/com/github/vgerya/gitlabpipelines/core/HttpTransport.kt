package com.github.vgerya.gitlabpipelines.core

import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class HttpRequestSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
) {
    /** Never print header values (they contain the token). */
    override fun toString(): String = "HttpRequestSpec(method=$method, url=$url, headers=${headers.keys})"
}

data class HttpResponseSpec(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    /** Case-insensitive lookup of the first value of a response header. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/** Throws [IOException] on network failure; any HTTP status is returned as a response. */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun send(req: HttpRequestSpec): HttpResponseSpec
}

/**
 * [HttpTransport] on top of `java.net.http.HttpClient`. Redirects are NOT followed so that the
 * token can never be sent to a host other than the configured one.
 */
class JdkHttpTransport(
    private val client: HttpClient = defaultClient(DEFAULT_TIMEOUT),
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : HttpTransport {

    override fun send(req: HttpRequestSpec): HttpResponseSpec {
        val builder = try {
            HttpRequest.newBuilder(URI.create(req.url)).timeout(timeout)
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid URL", e)
        }
        req.headers.forEach { (k, v) -> builder.header(k, v) }
        val publisher = if (req.body != null) {
            HttpRequest.BodyPublishers.ofString(req.body, Charsets.UTF_8)
        } else {
            HttpRequest.BodyPublishers.noBody()
        }
        builder.method(req.method.uppercase(), publisher)
        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted").apply { initCause(e) }
        }
        return HttpResponseSpec(response.statusCode(), response.headers().map(), response.body() ?: "")
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(20)

        fun defaultClient(connectTimeout: Duration = DEFAULT_TIMEOUT): HttpClient =
            HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
    }
}
