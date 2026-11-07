// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill by Noah Saffer; see NOTICE for provenance and modifications.

package io.github.jf0510.sill

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.*
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

class Identity(context: Context) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "sill-device-p256-v1"
    val key: PrivateKey
    val cert: X509Certificate
    val fingerprint: ByteArray

    init {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply {
                    initialize(
                        KeyGenParameterSpec.Builder(
                                alias,
                                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                            )
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            // Conscrypt signs the already-hashed TLS transcript with NONEwithECDSA.
                            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                            .build()
                    )
                }
                .generateKeyPair()
        }
        key = store.getKey(alias, null) as PrivateKey
        val public = store.getCertificate(alias).publicKey
        val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
        cert =
            prefs
                .getString("certificate", null)
                ?.let { encoded ->
                    runCatching {
                            java.security.cert.CertificateFactory.getInstance("X.509")
                                .generateCertificate(
                                    java.util.Base64.getDecoder().decode(encoded).inputStream()
                                ) as X509Certificate
                        }
                        .getOrNull()
                }
                ?.takeIf { it.publicKey.encoded.contentEquals(public.encoded) }
                ?: run {
                    val name = X500Name("CN=Sill Android")
                    val builder =
                        JcaX509v3CertificateBuilder(
                            name,
                            BigInteger(128, SecureRandom()).abs(),
                            Date(0),
                            Date(4102444800000L),
                            name,
                            public,
                        )
                    JcaX509CertificateConverter()
                        .getCertificate(
                            builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(key))
                        )
                        .also {
                            prefs
                                .edit()
                                .putString(
                                    "certificate",
                                    java.util.Base64.getEncoder().encodeToString(it.encoded),
                                )
                                .apply()
                        }
                }
        fingerprint = digest(public.encoded)
    }

    // A fresh context per dial prevents TLS session resumption bypassing certificate / revocation
    // checks.
    fun socket(
        host: String,
        port: Int,
        pairing: Boolean,
        pin: ByteArray?,
        onCreated: (SSLSocket) -> Unit = {},
    ): SSLSocket {
        require(pairing || pin != null) { "Pair with this Mac first." }
        val manager =
            object : X509ExtendedKeyManager() {
                override fun getClientAliases(type: String?, issuers: Array<out Principal>?) =
                    arrayOf(alias)

                override fun chooseClientAlias(
                    types: Array<out String>?,
                    issuers: Array<out Principal>?,
                    socket: Socket?,
                ) = alias

                override fun chooseEngineClientAlias(
                    types: Array<out String>?,
                    issuers: Array<out Principal>?,
                    engine: SSLEngine?,
                ) = alias

                override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = null

                override fun chooseServerAlias(
                    type: String?,
                    issuers: Array<out Principal>?,
                    socket: Socket?,
                ) = null

                override fun getCertificateChain(a: String?) = arrayOf(cert)

                override fun getPrivateKey(a: String?) = key
            }
        val trust =
            object : X509TrustManager {
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()

                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) {
                    throw java.security.cert.CertificateException("Client mode only")
                }

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) {
                    val pub =
                        chain?.firstOrNull()?.publicKey as? ECPublicKey
                            ?: throw java.security.cert.CertificateException(
                                "Mac has no P-256 identity"
                            )
                    if (pub.params.curve.field.fieldSize != 256)
                        throw java.security.cert.CertificateException("Unexpected Mac key")
                    if (pin != null && !MessageDigest.isEqual(pin, digest(pub.encoded)))
                        throw java.security.cert.CertificateException(
                            "This Mac's identity changed. Forget it and pair again."
                        )
                    // Unpinned trust is scoped to pairing only; no session data is accepted before
                    // HMAC proof.
                }
            }
        val ctx =
            SSLContext.getInstance("TLSv1.3").apply {
                init(arrayOf(manager), arrayOf(trust), SecureRandom())
            }
        val s = ctx.socketFactory.createSocket() as SSLSocket
        onCreated(s)
        try {
            s.enabledProtocols = arrayOf("TLSv1.3")
            s.tcpNoDelay = true
            s.soTimeout = 15_000
            s.sslParameters =
                s.sslParameters.apply {
                    applicationProtocols = arrayOf(if (pairing) "sill-pair/1" else "sill/1")
                    serverNames = listOf(SNIHostName("sill"))
                }
            s.connect(InetSocketAddress(host, port), 10_000)
            s.startHandshake()
            require(s.applicationProtocol == (if (pairing) "sill-pair/1" else "sill/1")) {
                "This server does not speak Sill."
            }
            return s
        } catch (e: Exception) {
            s.close()
            throw e
        }
    }

    companion object {
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

        fun pin(socket: SSLSocket) =
            digest(socket.session.peerCertificates.first().publicKey.encoded)

        fun b64(bytes: ByteArray) =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun unb64(s: String) = java.util.Base64.getUrlDecoder().decode(s)
    }
}
