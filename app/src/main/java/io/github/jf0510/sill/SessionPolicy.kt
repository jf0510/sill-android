// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill by Noah Saffer; see NOTICE for provenance and modifications.

package io.github.jf0510.sill

/** Pure source recovery rules. A user choice cancels all outstanding automatic picks. */
class SourceRecovery(private val resume: Source? = null) {
    data class Source(
        val window: Long? = null
    ) // null window means Desktop; null Source means nothing.

    private var first = true
    private var watched: Source? = null
    private var retry: Source? = null
    private var retryAt = 0L
    private var missingSince: Long? = null
    private var userPending = false
    private var userTarget: Source? = null
    private var beforeChoice: Source? = null
    private var grace = 2000L
    private var retryWait = 10000L

    fun userChoice(target: Source? = null) {
        userPending = true
        userTarget = target
        beforeChoice = watched
        first = false
        watched = null
        retry = null
        missingSince = null
    }

    fun observe(active: Source?, windows: Set<Long>, now: Long, rttMs: Long? = null): Source? {
        val rtt = (rttMs ?: 0).coerceIn(0, 60000)
        grace = maxOf(2000, rtt * 3)
        retryWait = maxOf(10000, rtt * 4)
        if (userPending) {
            if (active == null || (active == beforeChoice && active != userTarget)) return null
            userPending = false
        }
        if (active != null) {
            first = false
            watched = active
            retry = null
            missingSince = null
            return null
        }
        if (first) {
            first = false
            val target = resume?.takeIf { it.window == null || it.window in windows } ?: Source()
            return request(target, now)
        }
        retry?.let {
            if (now >= retryAt)
                return request(
                    if (it.window != null && it.window !in windows) Source() else it,
                    now,
                )
            return null
        }
        val previous = watched ?: return null
        if (previous.window == null) return if (now >= retryAt) request(Source(), now) else null
        if (previous.window in windows) {
            missingSince = null
            return null
        }
        val since = missingSince ?: now.also { missingSince = it }
        return if (now - since >= grace && now >= retryAt) request(Source(), now) else null
    }

    private fun request(source: Source, now: Long): Source {
        retry = source
        retryAt = now + retryWait
        missingSince = null
        return source
    }
}

object ReconnectPolicy {
    fun retry(reason: String?, requested: Boolean = false): Boolean =
        when (reason) {
            null,
            "quit",
            "remoteOff",
            "internetOff",
            "busy" -> true
            "removed",
            "pairingRequired" -> false
            else -> requested
        }

    fun delayMs(attempt: Int): Long = (1000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000)
}
