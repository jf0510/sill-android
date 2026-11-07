// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill by Noah Saffer; see NOTICE for provenance and modifications.

package io.github.jf0510.sill

import java.io.DataInputStream
import java.nio.ByteBuffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WireTest {
    private fun canonical(o: JSONObject): String =
        o.keys().asSequence().sorted().joinToString { k ->
            val v = o.get(k)
            k +
                ":" +
                (if (v is JSONObject) canonical(v)
                else if (v is Number) v.toDouble().toString() else v.toString())
        }

    private fun fixture(name: String) = JSONObject(javaClass.getResource("/$name")!!.readText())

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun swiftCodableInteroperability() {
        val f = fixture("swift-wire.json")
        val actual =
            mapOf(
                "pointer" to Wire.pointer("leftDown", .25, .75),
                "scroll" to Wire.scroll(.25, .75, -.1, .2),
                "phase" to Wire.phase("began", .25, .75),
                "text" to Wire.text("你好🌍"),
                "key" to Wire.key(41, true, 1048576),
            )
        actual.forEach { (name, value) ->
            assertEquals("Swift Codable $name", canonical(f.getJSONObject(name)), canonical(value))
        }
        assertEquals(
            f.getString("headerHex"),
            Wire.bytes(8, byteArrayOf(1, 2, 3), time = 1234.5).hex(),
        )
    }

    @Test
    fun fragmentedStreamAndUnknownKind() {
        val data = Wire.bytes(233, byteArrayOf(3, 4)) + Wire.bytes(1, byteArrayOf(5), true)
        val input =
            object : java.io.ByteArrayInputStream(data) {
                override fun read(b: ByteArray, off: Int, len: Int) =
                    super.read(b, off, minOf(len, 1))
            }
        val stream = DataInputStream(input)
        assertEquals(233, Wire.read(stream).kind)
        val frame = Wire.read(stream)
        assertTrue(frame.key)
        assertArrayEquals(byteArrayOf(5), frame.payload)
    }

    @Test
    fun oversizedAndTruncatedMessagesRejected() {
        val oversized = ByteBuffer.allocate(14).put(1).putDouble(0.0).put(0).putInt(-1).array()
        assertThrows(IllegalArgumentException::class.java) {
            Wire.read(DataInputStream(oversized.inputStream()))
        }
        val truncated = Wire.bytes(1, byteArrayOf(1, 2, 3)).dropLast(1).toByteArray()
        assertThrows(java.io.EOFException::class.java) {
            Wire.read(DataInputStream(truncated.inputStream()))
        }
    }

    @Test
    fun pairingReferenceVectors() {
        val f = fixture("pair-proof.json")
        val host = ByteArray(32) { it.toByte() }
        val device = ByteArray(32) { (it + 32).toByte() }
        val key = PairProof.key(f.getString("code"), host)
        assertEquals(f.getString("key"), key.hex())
        assertEquals(f.getString("device"), PairProof.proof(key, device, host).hex())
        assertEquals(f.getString("mac"), PairProof.proof(key, device, host, true).hex())
        assertThrows(IllegalArgumentException::class.java) { PairProof.normalize("123") }
        val code = f.getString("code")
        assertThrows(IllegalArgumentException::class.java) {
            PairProof.normalize(code.dropLast(1) + ((code.last() - '0' + 1) % 10))
        }
    }

    @Test
    fun nalConversionAndBounds() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 38, 1, 9, 0, 0, 0, 1, 2, 3),
            Hevc.annexB(byteArrayOf(0, 0, 0, 3, 38, 1, 9, 0, 0, 0, 2, 2, 3), 4),
        )
        assertThrows(IllegalArgumentException::class.java) {
            Hevc.annexB(byteArrayOf(0, 0, 0, 9, 1), 4)
        }
        assertThrows(IllegalArgumentException::class.java) { Hevc.annexB(byteArrayOf(0, 0), 4) }
    }
}
