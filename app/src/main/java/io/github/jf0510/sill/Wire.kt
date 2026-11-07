// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill by Noah Saffer; see NOTICE for provenance and modifications.

package io.github.jf0510.sill

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Sill generation 1. Swift Codable enum associated values use _0/_1, not a type field. */
object Wire {
    const val HEADER = 14
    const val PROTOCOL_VERSION = 1
    // The host's device gate compares the upstream compatibility version, not this
    // independently versioned Android application's release number.
    const val COMPATIBILITY_VERSION = "0.6.0"

    data class Message(val kind: Int, val time: Double, val key: Boolean, val payload: ByteArray)

    fun read(input: DataInputStream): Message {
        val kind = input.readUnsignedByte()
        val time = input.readDouble()
        val key = input.readUnsignedByte() == 1
        val length = input.readInt().toLong() and 0xffffffffL
        require(length <= if (kind == 1) 32L * 1024 * 1024 else 4L * 1024 * 1024) {
            "Mac sent an oversized message"
        }
        val bytes = ByteArray(length.toInt())
        input.readFully(bytes)
        return Message(kind, time, key, bytes)
    }

    fun bytes(
        kind: Int,
        payload: ByteArray = byteArrayOf(),
        key: Boolean = false,
        time: Double = 0.0,
    ): ByteArray =
        ByteBuffer.allocate(HEADER + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(kind.toByte())
            .putDouble(time)
            .put(if (key) 1.toByte() else 0.toByte())
            .putInt(payload.size)
            .put(payload)
            .array()

    fun json(vararg pairs: Pair<String, Any?>) =
        JSONObject().apply { pairs.forEach { (k, v) -> if (v != null) put(k, v) } }

    fun source(id: Long?) =
        if (id == null) json("desktop" to JSONObject()) else json("window" to json("_0" to id))

    fun pointer(action: String, x: Double, y: Double) =
        json(
            "pointer" to
                json("_0" to action, "x" to x.coerceIn(0.0, 1.0), "y" to y.coerceIn(0.0, 1.0))
        )

    fun scroll(x: Double, y: Double, dx: Double, dy: Double) =
        json("scroll" to json("x" to x, "y" to y, "dx" to dx, "dy" to dy))

    fun phase(phase: String, x: Double, y: Double) =
        json("scrollGesture" to json("_0" to phase, "x" to x, "y" to y))

    fun text(value: String) = json("text" to json("_0" to value))

    fun key(hid: Int, down: Boolean, modifiers: Long = 0) =
        json("key" to json("hidUsage" to hid, "down" to down, "modifiers" to modifiers))
}

object PairProof {
    private val damm =
        arrayOf(
            "0317598642",
            "7092154863",
            "4206871359",
            "1750983426",
            "6123045978",
            "3674209581",
            "5869720134",
            "8945362017",
            "9438617205",
            "2581436790",
        )

    fun normalize(raw: String): String {
        val code = raw.replace(" ", "").replace("-", "")
        require(code.length == 12 && code.all { it in '0'..'9' }) {
            "Enter the 12-digit code shown on your Mac."
        }
        var interim = 0
        code.forEach { interim = damm[interim][it - '0'] - '0' }
        require(interim == 0) { "That code has a typo. Check it against your Mac." }
        return code
    }

    fun key(code: String, macFingerprint: ByteArray): ByteArray {
        val spec =
            PBEKeySpec(
                normalize(code).toCharArray(),
                "sill-pair-v1".toByteArray() + macFingerprint,
                600_000,
                256,
            )
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun proof(
        key: ByteArray,
        device: ByteArray,
        host: ByteArray,
        fromMac: Boolean = false,
    ): ByteArray {
        val role = if (fromMac) "mac" else "device"
        val data =
            "sill-pair-v1 $role".toByteArray() +
                byteArrayOf(0) +
                (if (fromMac) host + device else device + host)
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
    }
}

/** Converts Apple's hvcC access units to MediaCodec's Annex B and reads SPS dimensions. */
object Hevc {
    data class Config(val lengthSize: Int, val csd: ByteArray, val width: Int, val height: Int)

    private val start = byteArrayOf(0, 0, 0, 1)

    fun config(data: ByteArray): Config {
        val b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val n = b.get().toInt() and 255
        require(n in 1..4)
        val count = b.get().toInt() and 255
        require(count in 1..16)
        val sets =
            (0 until count).map {
                val length = b.short.toInt() and 65535
                require(length > 1 && length <= b.remaining())
                ByteArray(length).also(b::get)
            }
        require(!b.hasRemaining())
        val sps = sets.first { ((it[0].toInt() ushr 1) and 63) == 33 }
        val (w, h) = dimensions(sps)
        return Config(n, sets.fold(byteArrayOf()) { a, s -> a + start + s }, w, h)
    }

    fun annexB(data: ByteArray, lengthSize: Int): ByteArray {
        require(lengthSize in 1..4)
        val out = java.io.ByteArrayOutputStream(data.size + 32)
        var p = 0
        while (p < data.size) {
            require(p + lengthSize <= data.size)
            var n = 0L
            repeat(lengthSize) { n = (n shl 8) or (data[p++].toLong() and 255) }
            require(n > 0 && n <= data.size - p)
            out.write(start)
            out.write(data, p, n.toInt())
            p += n.toInt()
        }
        return out.toByteArray()
    }

    private fun dimensions(sps: ByteArray): Pair<Int, Int> {
        val rbsp = java.io.ByteArrayOutputStream()
        var zeros = 0
        for (v in sps.drop(2)) {
            val n = v.toInt() and 255
            if (zeros >= 2 && n == 3) {
                zeros = 0
                continue
            }
            rbsp.write(n)
            zeros = if (n == 0) zeros + 1 else 0
        }
        val bits = Bits(rbsp.toByteArray())
        bits.skip(4)
        val sub = bits.take(3)
        bits.skip(1)
        bits.skip(96)
        val profiles = BooleanArray(sub)
        val levels = BooleanArray(sub)
        repeat(sub) {
            profiles[it] = bits.take(1) == 1
            levels[it] = bits.take(1) == 1
        }
        if (sub > 0) bits.skip((8 - sub) * 2)
        repeat(sub) {
            if (profiles[it]) bits.skip(88)
            if (levels[it]) bits.skip(8)
        }
        bits.ue()
        val chroma = bits.ue()
        require(chroma in 0..3)
        val separate = chroma == 3 && bits.take(1) == 1
        var w = bits.ue()
        var h = bits.ue()
        if (bits.take(1) == 1) {
            val left = bits.ue()
            val right = bits.ue()
            val top = bits.ue()
            val bottom = bits.ue()
            val c = if (separate) 0 else chroma
            w -= (left + right) * (if (c == 1 || c == 2) 2 else 1)
            h -= (top + bottom) * (if (c == 1) 2 else 1)
        }
        require(w in 1..16384 && h in 1..16384)
        return w to h
    }

    private class Bits(val bytes: ByteArray) {
        var p = 0

        fun skip(n: Int) {
            require(p + n <= bytes.size * 8)
            p += n
        }

        fun take(n: Int): Int {
            var v = 0
            repeat(n) {
                require(p < bytes.size * 8)
                v = (v shl 1) or ((bytes[p / 8].toInt() ushr (7 - p % 8)) and 1)
                p++
            }
            return v
        }

        fun ue(): Int {
            var n = 0
            while (take(1) == 0) {
                n++
                require(n < 24)
            }
            return (1 shl n) - 1 + take(n)
        }
    }
}
