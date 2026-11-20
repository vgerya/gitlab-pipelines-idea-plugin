package com.github.vgerya.gitlabpipelines.core

enum class ErrorKind {
    /** HTTP 401 or GraphQL `currentUser: null`. */
    AUTH,

    /** HTTP 403. */
    FORBIDDEN,

    /** HTTP 429. */
    RATE_LIMIT,

    /** IOException / timeout / connection refused. */
    NETWORK,

    /** HTTP 5xx. */
    SERVER,

    /** GraphQL `errors[]`. */
    GRAPHQL,

    /** Non-JSON body, unexpected shape, unexpected HTTP status (3xx, 400, 404, ...). */
    BAD_RESPONSE,

    /** Base URL or token missing / invalid. */
    NOT_CONFIGURED,
}

/**
 * Error raised by [GitLabClient]. The message never contains the token (it is never put into
 * URLs and the client never echoes request headers).
 */
class GitLabException(
    val kind: ErrorKind,
    val httpStatus: Int? = null,
    val retryAfterSec: Long? = null,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: defaultMessage(kind, httpStatus), cause) {

    override fun toString(): String = "GitLabException(kind=$kind, httpStatus=$httpStatus, message=$message)"

    companion object {
        private fun defaultMessage(kind: ErrorKind, httpStatus: Int?): String =
            if (httpStatus != null) "$kind (HTTP $httpStatus)" else kind.name
    }
}
