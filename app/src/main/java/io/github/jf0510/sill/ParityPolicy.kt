// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill by Noah Saffer; see NOTICE.
package io.github.jf0510.sill

import java.nio.ByteBuffer
import org.json.JSONObject

/** Decisions shared by UI and transport. Missing capabilities always mean unsupported. */
object ParityPolicy {
    const val IDLE_HOLD_MS = 600_000L

    fun awake(
        connected: Boolean,
        streaming: Boolean,
        foreground: Boolean,
        lastUse: Long,
        now: Long,
    ) = connected && streaming && foreground && now - lastUse in 0 until IDLE_HOLD_MS

    fun viewingOffConsole(locked: Boolean, offConsole: Boolean, view: Boolean) =
        locked && offConsole && view

    fun nextDisplay(desktop: Boolean, count: Int, locked: Boolean, offConsole: Boolean) =
        desktop && count >= 2 && !locked && !offConsole

    fun clipboard(text: String): ByteArray? {
        if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > 262144) return null
        return JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8).takeIf {
            it.size <= 1048576
        }
    }

    // A list temporarily missing VPN addresses must not erase a previously verified route.
    fun addresses(old: List<String>, fresh: List<String>) =
        (fresh + old).filter { it.isNotBlank() }.distinct().take(16)
}

data class SoundPackets(
    val epoch: Int,
    val sequence: Long,
    val segment: Boolean,
    val packets: List<ByteArray>,
) {
    companion object {
        fun parse(bytes: ByteArray): SoundPackets? {
            if (bytes.size !in 9..16384 || bytes[0].toInt() != 2) return null
            val b = ByteBuffer.wrap(bytes)
            b.get()
            val epoch = b.short.toInt() and 65535
            val seq = b.int.toLong() and 0xffffffffL
            val segment = b.get().toInt() and 1 != 0
            val count = b.get().toInt() and 255
            if (count == 0) return null
            val packets = mutableListOf<ByteArray>()
            repeat(count) {
                if (b.remaining() < 2) return null
                val n = b.short.toInt() and 65535
                if (n == 0 || n > b.remaining()) return null
                packets += ByteArray(n).also { b.get(it) }
            }
            if (b.hasRemaining()) return null
            return SoundPackets(epoch, seq, segment, packets)
        }
    }
}

/** AudioToolbox may wrap the ASC in MPEG-4 ES/DecoderConfig descriptors (tags 3/4/5). */
object AacCookie {
    fun configuration(cookie: ByteArray): ByteArray? {
        if (cookie.size !in 2..4096) return null
        if (cookie[0].toInt() and 255 != 3) return cookie.takeIf { eld(it) }
        fun descriptor(start: Int, end: Int, target: Int): ByteArray? {
            var p = start
            while (p < end) {
                val tag = cookie[p++].toInt() and 255
                var length = 0
                var done = false
                for (i in 0..3) {
                    if (p >= end) return null
                    val v = cookie[p++].toInt() and 255
                    length = (length shl 7) or (v and 127)
                    if (v and 128 == 0) {
                        done = true
                        break
                    }
                }
                if (!done || length > end - p) return null
                val limit = p + length
                if (tag == target) return cookie.copyOfRange(p, limit)
                var nested = p
                if (tag == 3) {
                    if (length < 3) return null
                    val flags = cookie[p + 2].toInt() and 255
                    nested += 3
                    if (flags and 128 != 0) nested += 2
                    if (flags and 64 != 0) {
                        if (nested >= limit) return null
                        nested += 1 + (cookie[nested].toInt() and 255)
                    }
                    if (flags and 32 != 0) nested += 2
                } else if (tag == 4) nested += 13
                if ((tag == 3 || tag == 4) && nested <= limit)
                    descriptor(nested, limit, target)?.let {
                        return it
                    }
                p = limit
            }
            return null
        }
        return descriptor(0, cookie.size, 5)?.takeIf { eld(it) }
    }

    private fun eld(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        val first = bytes[0].toInt() and 255
        val second = bytes[1].toInt() and 255
        return first shr 3 == 31 && 32 + ((first and 7) shl 3) + (second shr 5) == 39
    }
}
