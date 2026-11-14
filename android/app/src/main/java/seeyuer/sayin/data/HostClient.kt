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
    private val url = "https://${endpoint.tlsName}:${endpoint.port}/v2"

    init {
        val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("paired-host", endpoint.certificate)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
        val delegate = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val trust = PinnedTrust(delegate, endpoint.certificate, endpoint.tlsName)
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
    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

/** Public three-argument overload is required by Android's certificate cleaner. */
internal class PinnedTrust(
    private val delegate: X509TrustManager,
    private val certificate: X509Certificate,
    private val host: String,
) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(certificate)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.size != 1 || !MessageDigest.isEqual(chain[0].encoded, certificate.encoded)) throw CertificateException()
        chain[0].checkValidity()
        delegate.checkServerTrusted(chain, authType)
    }
    @Suppress("unused")
    fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, hostname: String): List<X509Certificate> {
        if (hostname != host) throw CertificateException()
        checkServerTrusted(chain, authType)
        return chain.toList()
    }
}
