package com.panelstash.source

/**
 * Errors are classified so the app can decide between retrying, backing off, and
 * treating a chapter as terminally unavailable. See the error-handling decision.
 */
sealed class SourceError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** HTTP 429: wait, then retry. Never switch sources for this. */
    class RateLimited(val retryAfterMs: Long? = null, cause: Throwable? = null) :
        SourceError("Rate limited by the source", cause)

    /** HTTP 403: a shared per-IP ban. All work against the source must pause. */
    class Banned(cause: Throwable? = null) : SourceError("Source temporarily blocked this IP", cause)

    /** Node failure, timeout, 5xx: re-resolve and retry once. */
    class Transient(cause: Throwable? = null) : SourceError("Source is temporarily unavailable", cause)

    /** 404, no pages, external-only: terminal for this chapter. */
    class Unavailable(cause: Throwable? = null) : SourceError("Not available on this source", cause)

    /** No network at all. */
    class Offline(cause: Throwable? = null) : SourceError("No network connection", cause)
}

/** Human, minimal strings. The UI shows one short label, never an explanation. */
fun SourceError.shortLabel(): String = when (this) {
    is SourceError.RateLimited -> "Slow down"
    is SourceError.Banned -> "Paused"
    is SourceError.Transient -> "Retry"
    is SourceError.Unavailable -> "Unavailable"
    is SourceError.Offline -> "Offline"
}
