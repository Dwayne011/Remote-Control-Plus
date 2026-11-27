package dev.amrakshay.atvremote.protocol

import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

internal const val PAIRING_PORT = 6467
internal const val REMOTE_PORT = 6466

/**
 * The TV's certificate is self-signed and can never chain to a trusted root, so
 * it is accepted as-is. Authentication runs the other way round: the TV decides
 * whether to trust *us*, from the certificate recorded during pairing.
 *
 * A future hardening step is to pin the TV's certificate on first pair and
 * compare it on every later connection; the official app does not do even that.
 */
private object AcceptAnyServer : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private const val CONNECT_TIMEOUT_MS = 5_000
private const val HANDSHAKE_TIMEOUT_MS = 8_000

/** Opens a TLS connection presenting [identity] as the client certificate. */
internal fun connectTls(host: String, port: Int, identity: ClientIdentity): SSLSocket {
    val password = CharArray(0)
    val keyStore = KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        setKeyEntry("client", identity.privateKey, password, arrayOf(identity.certificate))
    }
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        .apply { init(keyStore, password) }
        .keyManagers

    val context = SSLContext.getInstance("TLS").apply {
        init(keyManagers, arrayOf(AcceptAnyServer), SecureRandom())
    }

    // Local change: time limits on reaching the TV and on the handshake. Without them a host that
    // silently drops the connection (a device now holding the TV's old address) hangs for minutes.
    val plain = java.net.Socket()
    try {
        plain.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        val tls = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        tls.soTimeout = HANDSHAKE_TIMEOUT_MS
        tls.startHandshake()
        tls.soTimeout = 0
        return tls
    } catch (e: Exception) {
        runCatching { plain.close() }
        throw e
    }
}
