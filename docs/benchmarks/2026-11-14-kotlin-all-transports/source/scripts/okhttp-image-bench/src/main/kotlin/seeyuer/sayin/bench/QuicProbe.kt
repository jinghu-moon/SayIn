package seeyuer.sayin.bench

import org.json.JSONObject
import tech.kwik.core.QuicClientConnection
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Check Android API compatibility and real UDP/QUIC interoperation before a benchmark. */
object QuicProbe {
    @JvmStatic fun main(args: Array<String>) {
        println("PROBE_CONFIG")
        val config = JSONObject(File(args[0]).readText())
        val endpoint = config.getString("quic")
        val bytes = config.getJSONArray("cert").let { a -> ByteArray(a.length()) { a.getInt(it).toByte() } }
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
        val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("bench", cert) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
        val trust = PinnedTrust(factory.trustManagers.filterIsInstance<X509TrustManager>().single(), cert)
        println("PROBE_BUILDER")
        // Kwik's proxy(address) is a direct destination override; the TLS host stays unchanged.
        val connection = QuicClientConnection.newBuilder().host("bench.sayin.local").port(endpoint.substringAfterLast(':').toInt())
            .proxy(endpoint.substringBeforeLast(':')).applicationProtocol("sayin-bench").customTrustManager(trust)
            .connectTimeout(Duration.ofSeconds(8)).maxIdleTimeout(Duration.ofSeconds(30))
            .socketFactory { DatagramSocket(InetSocketAddress(InetAddress.getByName(args[1]), 0)) }.build()
        try {
            println("PROBE_CONNECT")
            connection.connect()
            println("PROBE_CERTIFICATE")
            check(connection.serverCertificateChain.single().encoded.contentEquals(cert.encoded))
            val stream = connection.createStream(true)
            println("PROBE_AUTH")
            val output = DataOutputStream(stream.outputStream)
            val input = DataInputStream(stream.inputStream)
            output.writeByte(0)
            output.write(config.getJSONArray("token").let { a -> ByteArray(a.length()) { a.getInt(it).toByte() } })
            output.writeByte(0); output.writeLong(93849824); output.flush()
            check(input.readUnsignedByte() == 1)
            output.writeInt(13); output.writeByte(3); output.writeLong(1); output.writeInt(0); output.flush()
            check(input.readInt() == 13); check(input.readUnsignedByte() == 3); check(input.readLong() == 1L); check(input.readInt() == 0)
            println("QUIC_TLS_PIN_AUTH_ECHO_PASSED")
        } finally { connection.closeAndWait(Duration.ofMillis(200)) }
    }
}
