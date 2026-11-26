package seeyuer.sayin.data

import android.net.Network
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Proxy
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** A client exists for exactly one saved host. No system-wide TLS changes. */
internal class HostClient(endpoint: HostEndpoint, network: Network? = null) {
    private val client: OkHttpClient
    private val url = "https://${endpoint.tlsName}:${endpoint.port}/sayin/v3"

    init {
        val trust = PinnedTrust(endpoint)
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        check("TLSv1.3" in tls.supportedSSLParameters.protocols) { "TLS13_REQUIRED" }
        val address = numericAddress(endpoint.address)
        client = OkHttpClient.Builder()
            .apply { if (network != null) socketFactory(network.socketFactory) }
            .sslSocketFactory(tls.socketFactory, trust)
            // Leave OkHttp's normal hostname/SAN verifier enabled.
            .dns(Dns { name -> if (name == endpoint.tlsName) listOf(address) else throw UnknownHostException() })
            .proxy(Proxy.NO_PROXY)
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    fun open(listener: WebSocketListener): WebSocket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    fun openImages(listener: WebSocketListener): WebSocket = client.newWebSocket(Request.Builder().url("$url/images").build(), listener)
    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

/** Public three-argument overload is required by Android's certificate cleaner. */
internal class PinnedTrust(
    private val endpoint: HostEndpoint,
) : X509TrustManager {
    private val fixed = (endpoint as? PairedHost)?.certificate
    private val fixedTrust = fixed?.let(::trustFor)

    private fun trustFor(certificate: X509Certificate): X509TrustManager {
        val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("paired-host", certificate)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = fixed?.let { arrayOf(it) } ?: emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.size != 1) throw CertificateException()
        val leaf = chain[0]
        try {
            if (fixed != null) {
                if (!MessageDigest.isEqual(leaf.encoded, fixed.encoded)) throw CertificateException()
                leaf.checkValidity()
            } else endpoint.validateCertificate(leaf)
            (fixedTrust ?: trustFor(leaf)).checkServerTrusted(chain, authType)
        } catch (error: Exception) {
            throw CertificateException(error)
        }
    }
    @Suppress("unused")
    fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, hostname: String): List<X509Certificate> {
        if (hostname != endpoint.tlsName) throw CertificateException()
        checkServerTrusted(chain, authType)
        return chain.toList()
    }
}
