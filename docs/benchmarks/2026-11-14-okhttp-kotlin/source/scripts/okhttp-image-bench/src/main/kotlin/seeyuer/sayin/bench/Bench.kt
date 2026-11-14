package seeyuer.sayin.bench

import android.os.Debug
import android.os.SystemClock
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.max
import kotlin.system.exitProcess

private const val CHUNK = 48 * 1024
private const val QUEUE = 2L * (CHUNK + 6)
private const val HOST = "bench.sayin.local"
private const val PING = 3
private const val START = 4
private const val DATA = 5
private const val END = 6
private const val DONE = 8
private const val READY = 9
private const val CANCEL = 10
private const val CANCELLED = 11
private const val STATUS = 12
private const val STATUS_ACK = 13

private fun now() = SystemClock.elapsedRealtimeNanos()
private fun ms(ns: Long) = ns / 1e6
private fun ensure(condition: Boolean, reason: String) {
    if (!condition) throw IOException(reason)
}
private fun JSONArray.bytes() = ByteArray(length()) { getInt(it).toByte() }
private fun command(kind: Int, job: Int) = ByteBuffer.allocate(5).put(kind.toByte()).putInt(job).array().toByteString()

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

private class BoundSockets(bind: String, private val connects: AtomicInteger) : SocketFactory() {
    private val local = InetAddress.getByName(bind)
    override fun createSocket() = Socket().apply {
        bind(InetSocketAddress(local, 0)); tcpNoDelay = true; connects.incrementAndGet()
    }
    override fun createSocket(host: String, port: Int): Socket = throw IOException("unexpected_socket_factory")
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = throw IOException("unexpected_socket_factory")
    override fun createSocket(host: InetAddress, port: Int): Socket = throw IOException("unexpected_socket_factory")
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = throw IOException("unexpected_socket_factory")
}

private data class Stamp(val time: Long, val job: Int)
private class Peak {
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
    private val endpoint = config.getString("tcp")
    private val ip = endpoint.substringBeforeLast(':')
    private val port = endpoint.substringAfterLast(':').toInt()
    private val images = File(imagePath)
    private val auth = "Bearer " + config.getJSONArray("token").bytes().joinToString("") { "%02x".format(it.toInt() and 255) }
    private val certificate = CertificateFactory.getInstance("X.509").generateCertificate(config.getJSONArray("cert").bytes().inputStream()) as X509Certificate
    private val output = PrintWriter(File(outputPath).outputStream().writer(Charsets.UTF_8), true)
    private var nextJob = 0

    @Synchronized private fun row(row: JSONObject) {
        output.println(row)
        check(!output.checkError()) { "result_write" }
        if (row.optString("type") != "rtt") println(row)
    }
    override fun close() { output.close() }

    private inner class Session(private val round: Int, private val mode: String) : AutoCloseable {
        private val https = mode.startsWith("https-stream")
        private val rate = if (mode.endsWith("-60")) 60_000_000L else 0L
        private val session = (SecureRandom().nextLong() and Long.MAX_VALUE).toString()
        private val activeJob = AtomicInteger()
        val connects = AtomicInteger()
        private val sequence = AtomicLong()
        private val waiting = ConcurrentHashMap<Long, Stamp>()
        private val rtts = Collections.synchronizedList(mutableListOf<JSONObject>())
        private val schedule = Executors.newScheduledThreadPool(2)
        private val producer = Executors.newSingleThreadExecutor()
        private val client: OkHttpClient
        private var control: Ws? = null
        private var data: Ws? = null
        @Volatile private var peak = Peak()
        val setupMs: Double

        init {
            val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("benchmark", certificate) }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
            val trust = PinnedTrust(factory.trustManagers.filterIsInstance<X509TrustManager>().single(), certificate)
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            client = OkHttpClient.Builder().socketFactory(BoundSockets(bind, connects)).sslSocketFactory(tls.socketFactory, trust)
                .dns { name -> if (name != HOST) throw UnknownHostException(); listOf(InetAddress.getByName(ip)) }
                .proxy(Proxy.NO_PROXY).protocols(listOf(Protocol.HTTP_1_1))
                .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(8, TimeUnit.SECONDS).build()
            try {
                val start = now()
                val ctrl = Ws("/control").also { control = it; it.awaitOpen() }
                if (https) client.newCall(request("/health").build()).execute().use { response ->
                    ensure(response.code == 200, "health")
                    response.body.bytes() // Drain before returning the connection to the pool.
                } else data = Ws("/data").also { it.awaitOpen() }
                setupMs = ms(now() - start)
                schedule.scheduleWithFixedDelay({
                    try {
                        val seq = sequence.incrementAndGet()
                        val job = activeJob.get()
                        waiting[seq] = Stamp(now(), job)
                        ensure(waiting.size < 4096, "echo_backlog")
                        val b = ByteBuffer.allocate(13).put(PING.toByte()).putLong(seq).putInt(job).array()
                        ensure(ctrl.socket.send(b.toByteString()), "ping_enqueue")
                    } catch (e: Exception) { ctrl.failure.compareAndSet(null, e) }
                }, 0, 20, TimeUnit.MILLISECONDS)
                schedule.scheduleWithFixedDelay({
                    try { peak.sample(data?.socket?.queueSize() ?: 0) }
                    catch (e: Exception) { ctrl.failure.compareAndSet(null, e) }
                }, 0, 100, TimeUnit.MILLISECONDS)
            } catch (e: Throwable) { close(); throw e }
        }

        private fun request(path: String) = Request.Builder().url("https://$HOST:$port$path")
            .header("Authorization", auth).header("X-Bench-Session", session)

        private inner class Ws(path: String) : WebSocketListener() {
            private val open = CountDownLatch(1)
            val events = LinkedBlockingQueue<ByteArray>(64)
            val failure = AtomicReference<Throwable>()
            val socket = client.newWebSocket(request(path).build(), this)
            fun awaitOpen() { ensure(open.await(10, TimeUnit.SECONDS), "websocket_open_timeout"); checkFailure() }
            fun checkFailure() { failure.get()?.let { throw IOException("websocket_failure", it) } }
            override fun onOpen(webSocket: WebSocket, response: Response) { open.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failure.compareAndSet(null, t); open.countDown() }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val b = bytes.toByteArray()
                    ensure(b.isNotEmpty(), "empty_event")
                    if (b[0].toInt() == PING) {
                        ensure(b.size == 13, "echo_length")
                        val buf = ByteBuffer.wrap(b).apply { get() }
                        val seq = buf.long
                        val job = buf.int
                        val stamp = waiting.remove(seq)
                        ensure(stamp != null && stamp.job == job, "echo_identity")
                        rtts.add(JSONObject().put("type", "rtt").put("round", round).put("mode", mode).put("job", job).put("rtt_ms", ms(now() - checkNotNull(stamp).time)))
                    } else ensure(events.offer(b), "event_queue")
                } catch (e: Exception) { failure.compareAndSet(null, e) }
            }
            fun await(kind: Int, job: Int, timeoutMs: Long): ByteArray {
                val end = now() + timeoutMs * 1_000_000
                while (now() < end) {
                    checkFailure()
                    val b = events.poll(20, TimeUnit.MILLISECONDS) ?: continue
                    ensure(b.size >= 5 && (b[0].toInt() and 255) == kind && ByteBuffer.wrap(b, 1, 4).int == job, "unexpected_event")
                    return b
                }
                throw IOException("event_timeout")
            }
        }
        fun idle(millis: Long) {
            val end = now() + millis * 1_000_000
            while (now() < end) { checkNotNull(control).checkFailure(); Thread.sleep(10) }
        }
        private fun start(file: Int, job: Int) {
            activeJob.set(job)
            peak = Peak().also { it.sample(data?.socket?.queueSize() ?: 0) }
            val b = ByteBuffer.allocate(10).put(START.toByte()).putInt(job).putInt(file).put(1).array()
            ensure(checkNotNull(control).socket.send(b.toByteString()), "start_enqueue")
            checkNotNull(control).await(READY, job, 5000)
        }

        private inner class Transfer(file: Int, private val job: Int) {
            private val cancelled = AtomicBoolean()
            private val failure = AtomicReference<Throwable>()
            private val call = AtomicReference<Call>()
            val started = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            val stopTime = AtomicLong()
            private val sample = samples.getJSONObject(file)
            private val path = File(images, sample.getString("name"))
            private val size = sample.getLong("bytes")
            private val future = producer.submit {
                started.countDown()
                try {
                    if (https) {
                        val body = object : RequestBody() {
                            override fun contentType() = "image/jpeg".toMediaType()
                            override fun contentLength() = size
                            override fun isOneShot() = true
                            override fun writeTo(sink: BufferedSink) = write(sink)
                        }
                        val upload = client.newCall(request("/upload/$file/$job").put(body).build())
                        call.set(upload)
                        if (cancelled.get()) upload.cancel()
                        upload.execute().use { response ->
                            ensure(response.code == 200, "http_status")
                            ensure(JSONObject(response.body.string()).getBoolean("verified"), "http_digest")
                        }
                    } else write(null)
                } catch (e: Throwable) { if (!cancelled.get()) failure.set(e) }
                finally { stopTime.set(now()); stopped.countDown() }
            }
            private fun write(sink: BufferedSink?) {
                val buffer = ByteArray(CHUNK)
                var sent = 0L
                val start = now()
                path.inputStream().use { input ->
                    while (sent < size) {
                        while (rate > 0) {
                            val delay = start + (sent * 8e9 / rate).toLong() - now()
                            if (delay <= 0) break
                            checkCancel()
                            LockSupport.parkNanos(minOf(delay, 1_000_000))
                        }
                        checkCancel()
                        val n = minOf(CHUNK.toLong(), size - sent).toInt()
                        var offset = 0
                        while (offset < n) {
                            val read = input.read(buffer, offset, n - offset)
                            ensure(read > 0, "short_file")
                            offset += read
                        }
                        if (sink != null) sink.write(buffer, 0, n)
                        else {
                            val ws = checkNotNull(data)
                            while (ws.socket.queueSize() + n + 6 > QUEUE) {
                                checkCancel(); ws.checkFailure(); LockSupport.parkNanos(500_000)
                            }
                            val frame = ByteBuffer.allocate(n + 6).put(DATA.toByte()).putInt(job).put(0).put(buffer, 0, n).array()
                            ensure(ws.socket.send(frame.toByteString()), "data_enqueue")
                        }
                        sent += n
                        peak.recordQueue(data?.socket?.queueSize() ?: 0)
                    }
                }
                if (sink != null) sink.flush()
                else ensure(checkNotNull(data).socket.send(ByteBuffer.allocate(6).put(END.toByte()).putInt(job).put(0).array().toByteString()), "end_enqueue")
            }
            private fun checkCancel() {
                if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("cancelled")
            }
            fun checkFailure() { failure.get()?.let { throw IOException("producer_failed", it) } }
            fun cancel() {
                // Cancel only after producer entry so its finally block always records termination.
                ensure(started.await(3, TimeUnit.SECONDS), "producer_not_started")
                cancelled.set(true)
                call.get()?.cancel()
                data?.socket?.cancel()
                future.cancel(true)
            }
        }
        fun upload(file: Int) {
            val job = ++nextJob
            val start = now()
            start(file, job)
            val transfer = Transfer(file, job)
            checkNotNull(control).await(DONE, job, 90_000)
            ensure(transfer.stopped.await(5, TimeUnit.SECONDS), "producer_finish")
            transfer.checkFailure()
            checkNotNull(control).checkFailure()
            val duration = ms(now() - start)
            val sample = samples.getJSONObject(file)
            peak.sample(data?.socket?.queueSize() ?: 0)
            ensure(connects.get() == 2, "connection_not_reused")
            row(JSONObject().put("type", "upload").put("round", round).put("mode", mode).put("job", job)
                .put("file", sample.getString("name")).put("bytes", sample.getLong("bytes")).put("ms", duration)
                .put("mbps", sample.getLong("bytes") * 8 / (duration * 1000)).put("verified", true)
                .put("memory", peak.json()).put("connections", connects.get()))
            activeJob.set(0)
        }
        fun cancelProbe(file: Int) {
            val job = ++nextJob
            start(file, job)
            val transfer = Transfer(file, job)
            ensure(transfer.started.await(3, TimeUnit.SECONDS), "producer_not_started")
            Thread.sleep(200)
            ensure(transfer.stopped.count == 1L, "probe_already_finished")
            val start = now()
            transfer.cancel()
            val ctrl = checkNotNull(control)
            ensure(ctrl.socket.send(command(CANCEL, job)), "cancel_enqueue")
            val ack = ctrl.await(CANCELLED, job, 5000)
            val serverMs = ms(now() - start)
            ensure(transfer.stopped.await(3, TimeUnit.SECONDS), "cancel_local_stop")
            val localMs = ms(transfer.stopTime.get() - start)
            val accepted = ByteBuffer.wrap(ack, 5, 8).long
            ensure(accepted in 1 until samples.getJSONObject(file).getLong("bytes"), "probe_byte_count")
            idle(300)
            ensure(ctrl.socket.send(command(STATUS, job)), "status_enqueue")
            val after = ByteBuffer.wrap(ctrl.await(STATUS_ACK, job, 5000), 5, 8).long
            ensure(accepted == after, "bytes_accepted_after_cancel")
            ensure(ctrl.events.isEmpty(), "completion_after_cancel")
            row(JSONObject().put("type", "cancel").put("round", round).put("mode", mode).put("job", job)
                .put("local_producer_stop_ms", localMs).put("server_cancel_ack_ms", serverMs)
                .put("accepted_bytes", accepted).put("accepted_bytes_after_300ms", after).put("no_completion_after_cancel", true))
            activeJob.set(0)
        }
        override fun close() {
            schedule.shutdownNow(); producer.shutdownNow()
            control?.socket?.cancel(); data?.socket?.cancel()
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
        fun flushRtts() { synchronized(rtts) { rtts.forEach(::row) } }
    }

    private fun run(rounds: Int, smoke: Boolean) {
        ensure(rounds in 1..6, "rounds")
        val modes = listOf("wss-split", "https-stream", "wss-split-60", "https-stream-60")
        for (round in 1..rounds) for (step in modes.indices) {
            val mode = modes[(step + round - 1) % modes.size]
            Session(round, mode).use { session ->
                row(JSONObject().put("type", "setup").put("round", round).put("mode", mode).put("setup_ms", session.setupMs).put("connections", session.connects.get()))
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
                Bench(args[0], args[1], args[2], args[4]).use { it.run(args[3].toInt(), args.getOrNull(5) == "smoke") }
            } catch (error: Throwable) {
                System.err.println("BENCH_FAILED ${error.javaClass.simpleName} ${error.message}")
                error.cause?.let { System.err.println("CAUSE ${it.javaClass.simpleName} ${it.message}") }
                exitProcess(1)
            }
        }
    }
}
