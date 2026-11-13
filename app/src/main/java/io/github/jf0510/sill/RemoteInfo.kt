// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill Remote.swift and Pairing.swift; see NOTICE.
package io.github.jf0510.sill

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

object RemoteInfo {
    fun macID(pin: ByteArray): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var bits = 0
        var buffer = 0
        return buildString {
            for (byte in pin.take(10)) {
                buffer = (buffer shl 8) or (byte.toInt() and 255)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    append(alphabet[(buffer shr bits) and 31])
                }
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
    }

    fun verified(envelope: JSONObject, pin: ByteArray): JSONObject? =
        runCatching {
                val point = java.util.Base64.getDecoder().decode(envelope.getString("key"))
                require(point.size == 65 && point[0].toInt() == 4)
                val prefix =
                    "3059301306072a8648ce3d020106082a8648ce3d030107034200"
                        .chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                val spki = prefix + point
                require(MessageDigest.isEqual(Identity.digest(spki), pin))
                val bytes = java.util.Base64.getDecoder().decode(envelope.getString("info"))
                require(bytes.size <= 65536)
                val publicKey =
                    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
                require(
                    Signature.getInstance("SHA256withECDSA").run {
                        initVerify(publicKey)
                        update(bytes)
                        verify(java.util.Base64.getDecoder().decode(envelope.getString("sig")))
                    }
                )
                JSONObject(bytes.toString(Charsets.UTF_8)).also {
                    require(it.getString("macID") == macID(pin))
                }
            }
            .getOrNull()

    fun recognizes(tag: String, key: String): Boolean =
        runCatching {
                val bytes = Identity.unb64(tag)
                val secret = Identity.unb64(key)
                require(bytes.size == 12 && secret.size == 32)
                val expected =
                    Mac.getInstance("HmacSHA256").run {
                        init(SecretKeySpec(secret, "HmacSHA256"))
                        doFinal("sill-tag-v1".toByteArray() + bytes.copyOfRange(0, 6)).copyOf(6)
                    }
                MessageDigest.isEqual(expected, bytes.copyOfRange(6, 12))
            }
            .getOrDefault(false)
}
