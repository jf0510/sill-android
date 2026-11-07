// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill by Noah Saffer; see NOTICE for provenance and modifications.

package io.github.jf0510.sill

import io.github.jf0510.sill.ResolutionFallback.Action.*
import org.junit.Assert.*
import org.junit.Test

class ResolutionFallbackTest {
    private val fiveK = ResolutionFallback.Size(5120, 2880)

    @Test
    fun oversizedRetinaRequestsStandardOnlyOnce() {
        val p = ResolutionFallback()
        assertEquals(REQUEST_STANDARD, p.unsupported(fiveK, 2.0))
        repeat(100) {
            assertEquals(NONE, p.unsupported(fiveK, 2.0))
            assertEquals(NONE, p.settings(2.0))
        }
        // Even an acknowledgment is not evidence of a picture; wait for decoded output.
        assertEquals(NONE, p.settings(1.0))
        assertEquals(CANNOT_LOWER, p.timeout())
        assertEquals(CANNOT_LOWER, p.unsupported(fiveK, 2.0))
    }

    @Test
    fun parameterSetsMayArriveBeforeSettings() {
        val p = ResolutionFallback()
        assertEquals(WAIT_FOR_SETTINGS, p.unsupported(fiveK, null))
        assertEquals(REQUEST_STANDARD, p.settings(2.0))
    }

    @Test
    fun settingsMayArriveBeforeParameterSets() {
        val p = ResolutionFallback()
        assertEquals(NONE, p.settings(2.0))
        assertEquals(REQUEST_STANDARD, p.unsupported(fiveK, 2.0))
    }

    @Test
    fun standardCannotBeLoweredAgain() {
        val p = ResolutionFallback()
        assertEquals(CANNOT_LOWER, p.unsupported(fiveK, 1.0))
        assertEquals(CANNOT_LOWER, p.settings(1.0))
    }

    @Test
    fun missingOrInvalidSettingsDoNotWaitForever() {
        val p = ResolutionFallback()
        assertEquals(WAIT_FOR_SETTINGS, p.unsupported(fiveK, Double.NaN))
        assertEquals(WAIT_FOR_SETTINGS, p.settings(null))
        assertEquals(CANNOT_LOWER, p.timeout())
        assertEquals(CANNOT_LOWER, p.settings(2.0))
    }

    @Test
    fun successfulPictureOrNewSessionClearsFallback() {
        val p = ResolutionFallback()
        p.unsupported(fiveK, 2.0)
        p.reset()
        assertNull(p.rejected)
        assertEquals(NONE, p.timeout())
        assertEquals(NONE, p.settings(1.0))
        // A later explicit Retina selection can be handled as a new episode.
        assertEquals(REQUEST_STANDARD, p.unsupported(fiveK, 2.0))
    }

    @Test
    fun smallerButStillUnsupportedStreamCannotCauseAnotherRequest() {
        val p = ResolutionFallback()
        assertEquals(REQUEST_STANDARD, p.unsupported(fiveK, 2.0))
        assertEquals(NONE, p.unsupported(ResolutionFallback.Size(2560, 1440), 1.0))
        assertEquals(CANNOT_LOWER, p.timeout())
    }

    @org.junit.Test
    fun missingSettingsAfterTimeoutDoesNotRestartRecovery() {
        val policy = ResolutionFallback()
        val size = ResolutionFallback.Size(5120, 2880)
        org.junit.Assert.assertEquals(
            ResolutionFallback.Action.WAIT_FOR_SETTINGS,
            policy.unsupported(size, null),
        )
        org.junit.Assert.assertEquals(ResolutionFallback.Action.CANNOT_LOWER, policy.timeout())
        org.junit.Assert.assertEquals(ResolutionFallback.Action.CANNOT_LOWER, policy.settings(null))
        org.junit.Assert.assertEquals(
            ResolutionFallback.Action.CANNOT_LOWER,
            policy.unsupported(size, null),
        )
    }
}
