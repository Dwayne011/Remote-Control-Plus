package com.remotecontrolplus.devices

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Samsung and LG TVs serve their WebSocket API with self-signed certificates,
 * so the only way to use the encrypted port is to accept them. This client is
 * used exclusively for connections to a TV's local IP, never for the internet.
 */
internal object TrustAll {
    private val manager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val okHttp: OkHttpClient by lazy {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, manager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
