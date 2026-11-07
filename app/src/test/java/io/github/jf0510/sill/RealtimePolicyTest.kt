// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill by Noah Saffer; see NOTICE for provenance and modifications.

package io.github.jf0510.sill

import org.junit.Assert.*
import org.junit.Test

class RealtimePolicyTest {
    private fun decide(
        p: KeyframeRecovery,
        t: Long,
        active: Boolean = true,
        streaming: Boolean = true,
        wait: Long = 4000,
    ) = p.consider(t, active, streaming, wait)

    @Test
    fun unansweredKeyframesRetryAtFourEightSixteenAndThirtyTwoSeconds() {
        val p = KeyframeRecovery()
        p.interrupted(0)
        for (t in listOf(0L, 4000L, 12000L, 28000L, 60000L, 92000L)) {
            assertEquals(KeyframeRecovery.Decision.Ask, decide(p, t))
            assertEquals(KeyframeRecovery.Decision.At(t + 4000), decide(p, t + 1))
        }
    }

    @Test
    fun repeatedInterruptionReportsDoNotSpamRequests() {
        val p = KeyframeRecovery()
        p.interrupted(0)
        decide(p, 0)
        for (t in 1L..3999L) {
            p.interrupted(t)
            assertEquals(KeyframeRecovery.Decision.At(4000), decide(p, t))
        }
    }

    @Test
    fun pictureRecoveryCancelsOwedRequests() {
        val p = KeyframeRecovery()
        p.interrupted(0)
        decide(p, 0)
        p.pictureBack(100, true)
        assertEquals(KeyframeRecovery.Decision.Nothing, decide(p, 4000))
    }

    @Test
    fun quickRelapseBacksOffButCalmPictureResetsSpacing() {
        val p = KeyframeRecovery()
        p.interrupted(0)
        decide(p, 0)
        p.pictureBack(100, true)
        p.interrupted(101)
        assertEquals(KeyframeRecovery.Decision.At(4000), decide(p, 101))
        decide(p, 4000)
        p.pictureBack(4100, true)
        p.interrupted(14100)
        assertEquals(KeyframeRecovery.Decision.Ask, decide(p, 14100))
    }

    @Test
    fun backgroundPausesRequestsAndOwesAnOutstandingReplyAgain() {
        val p = KeyframeRecovery()
        p.interrupted(0)
        decide(p, 0)
        p.leftForeground()
        assertEquals(KeyframeRecovery.Decision.Nothing, decide(p, 10000, active = false))
        assertEquals(KeyframeRecovery.Decision.Ask, decide(p, 10000))
    }

    @Test
    fun backgroundPictureDoesNotMakeNextLossARepeat() {
        val p = KeyframeRecovery()
        p.interrupted(0)
        decide(p, 0)
        p.leftForeground()
        p.pictureBack(500, false)
        p.interrupted(1000)
        assertEquals(KeyframeRecovery.Decision.At(2000), decide(p, 1000))
    }

    @Test
    fun noSourceDefersRequestUntilStreaming() {
        val p = KeyframeRecovery()
        p.interrupted(0)
        assertEquals(KeyframeRecovery.Decision.Nothing, decide(p, 0, streaming = false))
        assertEquals(KeyframeRecovery.Decision.Ask, decide(p, 1000))
    }

    @Test
    fun slowLinkExtendsReplyDeadline() {
        assertEquals(4000L, KeyframeRecovery.answerWait(null))
        assertEquals(4000L, KeyframeRecovery.answerWait(100))
        assertEquals(8000L, KeyframeRecovery.answerWait(2000))
        val p = KeyframeRecovery()
        p.interrupted(0)
        decide(p, 0, wait = 8000)
        assertEquals(KeyframeRecovery.Decision.At(8000), decide(p, 4000, wait = 8000))
    }

    @Test
    fun movesKeepLatestAndClickFlushesPositionBeforeButton() {
        val p = MoveCoalescer<String>()
        assertEquals(listOf("move0"), p.offer("move0", true, 0))
        assertTrue(p.offer("move1", true, 1).isEmpty())
        assertTrue(p.offer("move2", true, 2).isEmpty())
        assertEquals(8L, p.due())
        assertEquals(listOf("move2", "down"), p.offer("down", false, 3))
        assertEquals(listOf("up"), p.offer("up", false, 4))
        assertNull(p.due())
        assertNull(p.pending)
    }

    @Test
    fun scrollAndKeysAreNeverCoalesced() {
        val p = MoveCoalescer<String>()
        p.offer("first", true, 0)
        p.offer("last", true, 1)
        assertEquals(listOf("last", "scroll1"), p.offer("scroll1", false, 2))
        assertEquals(listOf("scroll2"), p.offer("scroll2", false, 3))
        assertEquals(listOf("key"), p.offer("key", false, 4))
    }

    @Test
    fun timerFlushesFinalMoveAndResetDiscardsOldSession() {
        val p = MoveCoalescer<String>()
        p.offer("first", true, 0)
        p.offer("last", true, 1)
        assertEquals(listOf("last"), p.flush(8))
        assertTrue(p.flush(9).isEmpty())
        p.offer("old", true, 10)
        p.reset()
        assertTrue(p.flush(16).isEmpty())
        assertEquals(listOf("new"), p.offer("new", true, 16))
    }

    @Test
    fun newerMoveAtDeadlineSupersedesPendingMove() {
        val p = MoveCoalescer<String>()
        p.offer("first", true, 0)
        p.offer("old", true, 1)
        assertEquals(listOf("new"), p.offer("new", true, 8))
        assertNull(p.pending)
    }
}
