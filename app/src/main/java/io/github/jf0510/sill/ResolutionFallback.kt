// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill by Noah Saffer; see NOTICE for provenance and modifications.

package io.github.jf0510.sill

/**
 * One fallback per rejected picture. Host settings can arrive before or after parameter sets.
 * Standard is a host-wide setting, not a per-client resize; never automatically restore Retina.
 */
class ResolutionFallback {
    data class Size(val width: Int, val height: Int)

    enum class Action {
        NONE,
        WAIT_FOR_SETTINGS,
        REQUEST_STANDARD,
        CANNOT_LOWER,
    }

    var rejected: Size? = null
        private set

    private var scale: Double? = null
    private var attempted = false
    private var pending = false

    fun unsupported(size: Size, captureScale: Double?): Action {
        rejected = size
        scale = captureScale?.takeIf { it.isFinite() && it > 0 }
        return decide()
    }

    fun settings(captureScale: Double?): Action {
        scale = captureScale?.takeIf { it.isFinite() && it > 0 }
        return decide()
    }

    private fun decide(): Action {
        if (rejected == null || pending) return Action.NONE
        if (attempted) return Action.CANNOT_LOWER
        val value = scale ?: return Action.WAIT_FOR_SETTINGS
        if (value <= 1.0) return Action.CANNOT_LOWER
        attempted = true
        pending = true
        return Action.REQUEST_STANDARD
    }

    /**
     * A refusal, no changed parameter sets, or an unresponsive host cannot leave a spinner forever.
     */
    fun timeout(): Action {
        if (rejected == null) return Action.NONE
        pending = false
        attempted = true
        return Action.CANNOT_LOWER
    }

    fun reset() {
        rejected = null
        scale = null
        attempted = false
        pending = false
    }
}
