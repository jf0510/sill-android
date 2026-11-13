// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill Pairing.swift; see NOTICE.
package io.github.jf0510.sill

import java.net.URI
import java.net.URLDecoder

class PairLink(
    val name: String,
    val pin: ByteArray,
    val secret: ByteArray,
    val routes: List<Pair<String, Int>>,
) {
    companion object {
        fun parse(text: String): PairLink {
            require(text.length <= 8192) { "Pairing link is too long." }
            val uri = URI(text.trim())
            require(
                uri.scheme.equals("sill", true) &&
                    uri.host.equals("pair", true) &&
                    uri.path in listOf("", "/")
            ) {
                "This is not a Sill pairing link."
            }
            val values =
                uri.rawQuery.orEmpty().split('&').map {
                    val parts = it.split('=', limit = 2)
                    URLDecoder.decode(parts[0], "UTF-8") to
                        URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                }
            fun one(key: String): String {
                val matches = values.filter { it.first == key }
                require(matches.size == 1) { "Invalid pairing field: $key" }
                return matches.single().second
            }
            require(one("v") == "1") { "Unsupported pairing link version." }
            val pin = Identity.unb64(one("k"))
            val secret = Identity.unb64(one("s"))
            require(pin.size == 32 && secret.size == 16 && one("m") == RemoteInfo.macID(pin)) {
                "Invalid Mac identity in pairing link."
            }
            val port = one("p").toInt()
            require(port in 1..65535)
            val routes =
                values
                    .filter { it.first == "a" }
                    .take(5)
                    .map {
                        val raw = it.second
                        require(
                            raw.isNotBlank() &&
                                !raw.contains('/') &&
                                !raw.contains('@') &&
                                !raw.contains('?') &&
                                !raw.contains('#')
                        )
                        val address = URI("sill://$raw")
                        val host =
                            address.host?.removeSurrounding("[", "]")
                                ?: error("Invalid Mac address")
                        val targetPort = if (address.port == -1) port else address.port
                        require(targetPort in 1..65535)
                        host to targetPort
                    }
            require(routes.isNotEmpty()) { "This pairing link has no reachable address." }
            val name =
                values
                    .firstOrNull { it.first == "n" }
                    ?.second
                    .orEmpty()
                    .filter { !it.isISOControl() }
                    .take(100)
                    .ifBlank { "Mac" }
            return PairLink(name, pin, secret, routes)
        }
    }
}
