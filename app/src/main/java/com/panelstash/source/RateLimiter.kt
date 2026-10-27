package com.panelstash.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Spacing limiter: guarantees a minimum gap between requests of one kind. */
class RateLimiter(private val minIntervalMs: Long) {
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    suspend fun acquire() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val wait = lastRequestAt + minIntervalMs - now
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }
}

/**
 * Once a source answers 403 we stop touching it for a while: the limit is shared per IP,
 * so continuing to call makes the ban worse.
 */
class SourceGate {
    @Volatile
    private var blockedUntil = 0L

    fun isBlocked(): Boolean = System.currentTimeMillis() < blockedUntil

    fun block(durationMs: Long = 10 * 60_000L) {
        blockedUntil = maxOf(blockedUntil, System.currentTimeMillis() + durationMs)
    }

    fun remainingMs(): Long = maxOf(0L, blockedUntil - System.currentTimeMillis())
}
