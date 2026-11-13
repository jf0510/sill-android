// SPDX-License-Identifier: Apache-2.0
package io.github.jf0510.sill

import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ParityPolicyTest {
    @Test
    fun appleCookieIsUnwrappedBeforeAndroidDecoding() {
        // Captured from upstream AudioEncoder(framesPerPacket: 480), macOS AudioToolbox.
        val cookie =
            "0380808024000000048080801640140018000001f4000001f4000580808004f8e65000068080800102"
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        val asc = byteArrayOf(0xf8.toByte(), 0xe6.toByte(), 0x50, 0)
        assertArrayEquals(asc, AacCookie.configuration(cookie))
        assertArrayEquals(asc, AacCookie.configuration(asc))
        for (n in 0 until cookie.size - 6) assertNull(AacCookie.configuration(cookie.copyOf(n)))
        assertNull(AacCookie.configuration(byteArrayOf(0x12, 0x10)))
        assertNull(AacCookie.configuration(cookie.clone().also { it[4] = 127 }))
    }

    @Test
    fun idleHoldExpiresAndNeedsVisibleStream() {
        assertTrue(ParityPolicy.awake(true, true, true, 100, 600099))
        assertFalse(ParityPolicy.awake(true, true, true, 100, 600100))
        assertFalse(ParityPolicy.awake(false, true, true, 100, 100))
        assertFalse(ParityPolicy.awake(true, false, true, 100, 100))
        assertFalse(ParityPolicy.awake(true, true, false, 100, 100))
        assertFalse(ParityPolicy.awake(true, true, true, 100, 99))
    }

    @Test
    fun capabilityAndLockGates() {
        for (locked in listOf(false, true)) for (off in listOf(false, true)) for (view in
            listOf(false, true)) assertEquals(
            locked && off && view,
            ParityPolicy.viewingOffConsole(locked, off, view),
        )
        assertTrue(ParityPolicy.nextDisplay(true, 2, false, false))
        assertFalse(ParityPolicy.nextDisplay(false, 2, false, false))
        assertFalse(ParityPolicy.nextDisplay(true, 1, false, false))
        assertFalse(ParityPolicy.nextDisplay(true, 2, true, false))
        assertFalse(ParityPolicy.nextDisplay(true, 2, false, true))
    }

    @Test
    fun clipboardUsesUtf8AndJsonLimits() {
        assertNull(ParityPolicy.clipboard(""))
        assertNotNull(ParityPolicy.clipboard("a".repeat(262144)))
        assertNull(ParityPolicy.clipboard("a".repeat(262145)))
        assertNull(ParityPolicy.clipboard("你".repeat(90000)))
        assertNull(ParityPolicy.clipboard("\u0000".repeat(200000)))
        val text = "你好\n🌍\"\\"
        assertEquals(text, JSONObject(String(ParityPolicy.clipboard(text)!!)).getString("text"))
    }

    @Test
    fun audioPacketBoundsAndSequence() {
        val bytes =
            ByteBuffer.allocate(16)
                .put(2)
                .putShort(65535.toShort())
                .putInt(-1)
                .put(1)
                .put(2)
                .putShort(2)
                .put(byteArrayOf(4, 5))
                .putShort(1)
                .put(6)
                .array()
        val parsed = SoundPackets.parse(bytes)!!
        assertEquals(65535, parsed.epoch)
        assertEquals(0xffffffffL, parsed.sequence)
        assertTrue(parsed.segment)
        assertArrayEquals(byteArrayOf(4, 5), parsed.packets[0])
        assertArrayEquals(byteArrayOf(6), parsed.packets[1])
        for (n in 0 until bytes.size) assertNull(SoundPackets.parse(bytes.copyOf(n)))
        assertNull(SoundPackets.parse(bytes + byteArrayOf(0)))
        assertNull(SoundPackets.parse(bytes.clone().also { it[8] = 0 }))
        assertNull(SoundPackets.parse(bytes.clone().also { it[0] = 99 }))
        val random = java.util.Random(123)
        repeat(2000) {
            SoundPackets.parse(ByteArray(random.nextInt(200)).also { random.nextBytes(it) })
        }
    }

    @Test
    fun signedAddressUpdatesBindToPinnedIdentity() {
        val key =
            KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        val pin = Identity.digest(key.public.encoded)
        val info =
            JSONObject()
                .put("macID", RemoteInfo.macID(pin))
                .put("issuedAt", 42)
                .toString()
                .toByteArray()
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(key.private)
                update(info)
                sign()
            }
        fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        val envelope =
            JSONObject()
                .put("info", b64(info))
                .put("key", b64(key.public.encoded.takeLast(65).toByteArray()))
                .put("sig", b64(signature))
        assertEquals(42, RemoteInfo.verified(envelope, pin)!!.getInt("issuedAt"))
        assertNull(RemoteInfo.verified(envelope, ByteArray(32)))
        envelope.put("info", b64(info + byteArrayOf(32)))
        assertNull(RemoteInfo.verified(envelope, pin))
    }

    @Test
    fun missingVpnListDoesNotEraseSavedRoutes() {
        assertEquals(
            listOf("new|7455", "vpn.ts.net|7455", "old|7455"),
            ParityPolicy.addresses(
                listOf("vpn.ts.net|7455", "old|7455"),
                listOf("new|7455", "vpn.ts.net|7455"),
            ),
        )
        assertEquals(listOf("vpn|7455"), ParityPolicy.addresses(listOf("vpn|7455"), emptyList()))
    }

    @Test
    fun pairingLinksRejectIdentityMismatchAndDuplicateSecrets() {
        val pin = ByteArray(32) { it.toByte() }
        val secret = ByteArray(16) { (it + 10).toByte() }
        val link =
            "sill://pair?v=1&m=${RemoteInfo.macID(pin)}&k=${Identity.b64(pin)}&s=${Identity.b64(secret)}&n=Test+Mac&p=7455&a=mac.ts.net&a=%5B%3A%3A1%5D%3A7555"
        val parsed = PairLink.parse(link)
        assertEquals("Test Mac", parsed.name)
        assertEquals(listOf("mac.ts.net" to 7455, "::1" to 7555), parsed.routes)
        assertArrayEquals(secret, parsed.secret)
        assertThrows(IllegalArgumentException::class.java) { PairLink.parse(link + "&s=x") }
        assertThrows(IllegalArgumentException::class.java) {
            PairLink.parse(link.replace("m=${RemoteInfo.macID(pin)}", "m=WRONG"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairLink.parse(link.replace("v=1", "v=2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairLink.parse(link.replace("p=7455", "p=0"))
        }
    }

    @Test
    fun slowLinkDoesNotPrematurelyReplaceAClosedWindow() {
        val policy = SourceRecovery()
        policy.observe(SourceRecovery.Source(3), setOf(3), 0, 4000)
        assertNull(policy.observe(null, emptySet(), 100, 4000))
        assertNull(policy.observe(null, emptySet(), 2100, 4000))
        assertEquals(SourceRecovery.Source(), policy.observe(null, emptySet(), 12100, 4000))
        assertNull(policy.observe(null, emptySet(), 22100, 4000))
    }
}
