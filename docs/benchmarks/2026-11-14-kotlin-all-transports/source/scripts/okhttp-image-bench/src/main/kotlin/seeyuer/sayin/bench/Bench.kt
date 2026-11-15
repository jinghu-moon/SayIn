package seeyuer.sayin.bench

import android.os.Debug
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.max
import kotlin.system.exitProcess

internal const val CHUNK = 48 * 1024
internal const val QUEUE = 2L * (CHUNK + 6)
internal const val HOST = "bench.sayin.local"
internal const val PING = 3
internal const val START = 4
internal const val DATA = 5
internal const val END = 6
internal const val DONE = 8
internal const val READY = 9
internal const val CANCEL = 10
internal const val CANCELLED = 11
internal const val STATUS = 12
internal const val STATUS_ACK = 13

internal fun now() = SystemClock.elapsedRealtimeNanos()
internal fun ms(ns: Long) = ns / 1e6
internal fun ensure(condition: Boolean, reason: String) {
    if (!condition) throw IOException(reason)
}
internal fun JSONArray.bytes() = ByteArray(length()) { getInt(it).toByte() }
internal fun command(kind: Int, job: Int) = ByteBuffer.allocate(5).put(kind.toByte()).putInt(job).array()

/** Public three-argument overload is required by Android's certificate chain cleaner. */
class PinnedTrust(private val delegate: X509TrustManager, private val certificate: X509Certificate) : X509TrustManager {
    override fun getAcceptedIssuers() = arrayOf(certificate)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String): Unit = throw CertificateException()
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.size != 1 || !MessageDigest.isEqual(chain[0].encoded, certificate.encoded)) throw CertificateException("pin")
        chain[0].checkValidity()
        delegate.checkServerTrusted(chain, authType)
    }
    @Suppress("unused") // Invoked reflectively by Android's X509TrustManagerExtensions.
    fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String): List<X509Certificate> {
        if (host != HOST) throw CertificateException("host")
        checkServerTrusted(chain, authType)
        return chain.toList()
    }
}

internal class BoundSockets(bind: String, private val connects: AtomicInteger) : SocketFactory() {
    private val local = InetAddress.getByName(bind)
    override fun createSocket() = Socket().apply {
        bind(InetSocketAddress(local, 0)); tcpNoDelay = true; connects.incrementAndGet()
    }
    override fun createSocket(host: String, port: Int): Socket = throw IOException("unexpected_socket_factory")
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = throw IOException("unexpected_socket_factory")
    override fun createSocket(host: InetAddress, port: Int): Socket = throw IOException("unexpected_socket_factory")
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = throw IOException("unexpected_socket_factory")
}

internal data class Stamp(val time: Long, val job: Int)
internal class Peak {
    private var rss = 0L
    private var heap = 0L
    private var nativeHeap = 0L
    private var queue = 0L
    @Synchronized fun recordQueue(queued: Long) { queue = max(queue, queued) }
    @Synchronized fun sample(queued: Long) {
        val runtime = Runtime.getRuntime()
        heap = max(heap, runtime.totalMemory() - runtime.freeMemory())
        nativeHeap = max(nativeHeap, Debug.getNativeHeapAllocatedSize())
        recordQueue(queued)
        val line = File("/proc/self/status").useLines { lines -> lines.first { it.startsWith("VmRSS:") } }
        rss = max(rss, line.filter(Char::isDigit).toLong() * 1024)
    }
    @Synchronized fun json(): JSONObject = JSONObject()
        .put("rss_sampled_peak_bytes", rss).put("managed_heap_sampled_peak_bytes", heap)
        .put("native_heap_sampled_peak_bytes", nativeHeap).put("wss_queue_peak_bytes", queue)
}

/** Isolated Kotlin/Android ART experiment; never opens SayIn data or input APIs. */
class Bench private constructor(configPath: String, imagePath: String, private val bind: String, outputPath: String) : AutoCloseable {
    private val config = JSONObject(File(configPath).readText())
    private val samples = config.getJSONArray("samples")
    private val images = File(imagePath)
    private val output = PrintWriter(File(outputPath).outputStream().writer(Charsets.UTF_8), true)
    private var nextJob = 0

    @Synchronized private fun row(row: JSONObject) {
        output.println(row)
        check(!output.checkError()) { "result_write" }
        if (row.optString("type") != "rtt") println(row)
    }
    override fun close() { output.close() }

    private fun run(rounds: Int, smoke: Boolean, selected: String?) {
        ensure(rounds in 1..6, "rounds")
        val base = listOf("wss-split", "https-stream", "tls-split", "quic-1", "quic-2", "quic-4")
        val available = base + base.map { "$it-60" }
        val modes = selected?.split(',') ?: available
        ensure(modes.isNotEmpty() && modes.distinct().size == modes.size && modes.all { it in available }, "modes")
        for (round in 1..rounds) for (step in modes.indices) {
            val mode = modes[(step + (round - 1) * 3) % modes.size]
            ExperimentSession(config, images, bind, round, mode, ::row) { ++nextJob }.use { session ->
                row(JSONObject().put("type", "setup").put("round", round).put("mode", mode).put("setup_ms", session.setupMs).put("connections", session.connects.get()).put("streams", session.streams))
                session.idle(1500)
                for (index in 0 until if (smoke) 1 else samples.length()) {
                    session.upload(if (smoke) 0 else if (round % 2 == 1) index else samples.length() - index - 1)
                }
                session.idle(1000); session.cancelProbe(samples.length() - 1); session.idle(300); session.flushRtts()
            }
            Thread.sleep(250)
        }
    }
    companion object {
        @JvmStatic fun main(args: Array<String>) {
            try {
                ensure(args.size >= 5, "arguments")
                Bench(args[0], args[1], args[2], args[4]).use { it.run(args[3].toInt(), args.getOrNull(5) == "smoke", args.getOrNull(6)) }
            } catch (error: Throwable) {
                System.err.println("BENCH_FAILED ${error.javaClass.simpleName} ${error.message}")
                error.cause?.let { System.err.println("CAUSE ${it.javaClass.simpleName} ${it.message}") }
                exitProcess(1)
            }
        }
    }
}
