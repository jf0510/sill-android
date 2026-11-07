// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill by Noah Saffer; see NOTICE for provenance and modifications.

package io.github.jf0510.sill

import org.junit.Assert.*
import org.junit.Test

class SessionPolicyTest {
    private val desktop = SourceRecovery.Source()

    private fun window(id: Long) = SourceRecovery.Source(id)

    @Test
    fun initialEmptyRequestsDesktopWithoutFlooding() {
        val p = SourceRecovery()
        assertEquals(desktop, p.observe(null, emptySet(), 0))
        for (t in 1L..9999L) assertNull(p.observe(null, emptySet(), t))
        assertEquals(desktop, p.observe(null, emptySet(), 10000))
    }

    @Test
    fun joinsExistingStreamWithoutReplacingIt() {
        val p = SourceRecovery()
        assertNull(p.observe(window(3), setOf(3), 0))
        assertNull(p.observe(window(3), setOf(3), 20000))
    }

    @Test
    fun resumesWindowOnlyIfStillAvailable() {
        assertEquals(window(3), SourceRecovery(window(3)).observe(null, setOf(3), 0))
        assertEquals(desktop, SourceRecovery(window(3)).observe(null, emptySet(), 0))
    }

    @Test
    fun disappearingWindowHasGraceAndReappearanceCancelsIt() {
        val p = SourceRecovery()
        p.observe(window(3), setOf(3), 0)
        assertNull(p.observe(null, emptySet(), 100))
        assertNull(p.observe(null, emptySet(), 2099))
        assertNull(p.observe(null, setOf(3), 2100))
        assertNull(p.observe(null, emptySet(), 3000))
        assertEquals(desktop, p.observe(null, emptySet(), 5000))
    }

    @Test
    fun manualPickCancelsPendingAutomaticRetryEvenWithStaleList() {
        val p = SourceRecovery()
        p.observe(desktop, emptySet(), 0)
        p.userChoice(window(9))
        assertNull(p.observe(desktop, setOf(9), 100))
        assertNull(p.observe(null, setOf(9), 20000))
        assertNull(p.observe(window(9), setOf(9), 21000))
        assertNull(p.observe(null, emptySet(), 22000))
        assertEquals(desktop, p.observe(null, emptySet(), 24000))
    }

    @Test
    fun manualChoiceBeforeFirstListIsNotOverridden() {
        val p = SourceRecovery()
        p.userChoice(window(7))
        assertNull(p.observe(null, setOf(7), 10000))
    }

    @Test
    fun temporaryCaptureFailureDoesNotReplaceListedWindow() {
        val p = SourceRecovery()
        p.observe(window(3), setOf(3), 0)
        assertNull(p.observe(null, setOf(3), 60000))
    }

    @Test
    fun desktopRestartIsThrottled() {
        val p = SourceRecovery()
        p.observe(null, emptySet(), 0)
        p.observe(desktop, emptySet(), 100)
        assertNull(p.observe(null, emptySet(), 200))
        assertEquals(desktop, p.observe(null, emptySet(), 10000))
    }

    @Test
    fun resumeTargetDisappearsWhileAwaitingHost() {
        val p = SourceRecovery(window(3))
        assertEquals(window(3), p.observe(null, setOf(3), 0))
        assertNull(p.observe(null, emptySet(), 9000))
        assertEquals(desktop, p.observe(null, emptySet(), 10000))
    }

    @Test
    fun terminationReasonsAndBackoff() {
        for (reason in listOf(null, "quit", "busy", "remoteOff", "internetOff")) assertTrue(
            ReconnectPolicy.retry(reason)
        )
        for (reason in listOf("removed", "pairingRequired")) assertFalse(
            ReconnectPolicy.retry(reason, true)
        )
        for (reason in listOf("update", "futureReason")) {
            assertFalse(ReconnectPolicy.retry(reason))
            assertTrue(ReconnectPolicy.retry(reason, true))
        }
        assertEquals(
            listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L),
            (0..6).map(ReconnectPolicy::delayMs),
        )
    }
}
