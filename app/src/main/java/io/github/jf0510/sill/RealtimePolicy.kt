// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill by Noah Saffer; see NOTICE for provenance and modifications.

package io.github.jf0510.sill

/** Millisecond-clock port of iOSClient/KeyframeRecovery.swift. Owned by the main thread. */
class KeyframeRecovery {
    var open = false
        private set

    private var asked = false
    private var repeats = 0
    private var lastAsk: Long? = null
    private var backAt: Long? = null
    private var wentAway = false

    sealed class Decision {
        data object Nothing : Decision()

        data object Ask : Decision()

        data class At(val time: Long) : Decision()
    }

    fun interrupted(now: Long) {
        if (open) return
        open = true
        asked = false
        repeats =
            if (!wentAway && backAt?.let { now >= it && now - it < 10000 } == true)
                (repeats + 1).coerceAtMost(4)
            else 0
    }

    fun pictureBack(now: Long, active: Boolean) {
        if (!open) return
        open = false
        backAt = now
        if (active) wentAway = false
    }

    fun leftForeground() {
        wentAway = true
        asked = false
        repeats = 0
    }

    fun consider(now: Long, active: Boolean, streaming: Boolean, wait: Long): Decision {
        if (!open || !active || !streaming) return Decision.Nothing
        if (asked) {
            lastAsk?.let { if (now >= it && now < it + wait) return Decision.At(it + wait) }
            asked = false
            repeats = (repeats + 1).coerceAtMost(4)
        }
        val spacing = 2000L shl repeats
        lastAsk?.let { if (now >= it && now < it + spacing) return Decision.At(it + spacing) }
        asked = true
        lastAsk = now
        return Decision.Ask
    }

    companion object {
        fun answerWait(worstRttMs: Long?) = maxOf(4000L, 4 * (worstRttMs ?: 0L))
    }
}

/** Only absolute pointer moves are replaceable. Every other input flushes the latest move first. */
class MoveCoalescer<T> {
    var pending: T? = null
        private set

    private var lastMove: Long? = null

    fun offer(value: T, move: Boolean, now: Long): List<T> {
        if (!move) return flush(now) + value
        if (lastMove?.let { now < it + 8 } != true) {
            pending = null
            lastMove = now
            return listOf(value)
        }
        pending = value
        return emptyList()
    }

    fun due(): Long? = if (pending != null) lastMove?.plus(8) else null

    fun flush(now: Long): List<T> {
        val value = pending ?: return emptyList()
        pending = null
        lastMove = now
        return listOf(value)
    }

    fun reset() {
        pending = null
        lastMove = null
    }
}
