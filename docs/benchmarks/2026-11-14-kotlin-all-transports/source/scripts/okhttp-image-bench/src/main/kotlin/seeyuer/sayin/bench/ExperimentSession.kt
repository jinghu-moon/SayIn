package seeyuer.sayin.bench

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
import org.json.JSONObject
import tech.kwik.core.QuicClientConnection
import tech.kwik.core.QuicStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
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
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** One measurement loop for all transports. Only endpoint framing/writes vary. */
internal class ExperimentSession(
    private val config: JSONObject,
    private val images: File,
    private val bind: String,
    private val round: Int,
    private val mode: String,
    private val row: (JSONObject) -> Unit,
    private val nextJob: () -> Int,
) : AutoCloseable {
    private val baseMode = mode.removeSuffix("-60")
    private val https = baseMode == "https-stream"
    private val quicMode = baseMode.startsWith("quic-")
    val streams = if (quicMode) baseMode.substringAfterLast('-').toInt() else 1
    val expectedConnections = if (quicMode) 1 else 2
    private val rate = if (mode.endsWith("-60")) 60_000_000L else 0L
    private val session = SecureRandom().nextLong() and Long.MAX_VALUE
    private val samples = config.getJSONArray("samples")
    private val token = config.getJSONArray("token").bytes()
    private val auth = "Bearer " + token.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val endpoint = config.getString("tcp")
    private val ip = endpoint.substringBeforeLast(':')
    private val port = endpoint.substringAfterLast(':').toInt()
    private val certificate = CertificateFactory.getInstance("X.509").generateCertificate(config.getJSONArray("cert").bytes().inputStream()) as X509Certificate
    private val activeJob = AtomicInteger()
    val connects = AtomicInteger()
    private val sequence = AtomicLong()
    private val waiting = ConcurrentHashMap<Long, Stamp>()
    private val rtts = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val schedule = Executors.newScheduledThreadPool(2)
    private val producer = Executors.newFixedThreadPool(streams)
    private var client: OkHttpClient? = null
    private var quic: QuicClientConnection? = null
    private var control: Endpoint? = null
    private val data = mutableListOf<Endpoint>()
    @Volatile private var peak = Peak()
    val setupMs: Double

    init {
        val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("benchmark", certificate) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
        val trust = PinnedTrust(factory.trustManagers.filterIsInstance<X509TrustManager>().single(), certificate)
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        try {
            val start = now()
            when {
                quicMode -> {
                    val remote = config.getString("quic")
                    val q = QuicClientConnection.newBuilder().host(HOST).port(remote.substringAfterLast(':').toInt())
                        // This API overrides the direct UDP destination, not the TLS/SNI name.
                        .proxy(remote.substringBeforeLast(':')).applicationProtocol("sayin-bench").customTrustManager(trust)
                        .connectTimeout(Duration.ofSeconds(8)).maxIdleTimeout(Duration.ofSeconds(90))
                        .defaultStreamReceiveBufferSize(2L * 1024 * 1024)
                        .maxOpenPeerInitiatedBidirectionalStreams(0).maxOpenPeerInitiatedUnidirectionalStreams(0)
                        .socketFactory {
                            DatagramSocket(InetSocketAddress(InetAddress.getByName(bind), 0)).also { connects.incrementAndGet() }
                        }.build().also { quic = it }
                    q.connect() // No resumption tickets or early data.
                    ensure(q.serverCertificateChain.size == 1 && MessageDigest.isEqual(q.serverCertificateChain[0].encoded, certificate.encoded), "quic_pin")
                    control = quicEndpoint(q.createStream(true), 0)
                    repeat(streams) { data += quicEndpoint(q.createStream(true), 1) }
                }
                baseMode == "tls-split" -> {
                    fun open(role: Int): Endpoint {
                        val socket = BoundSockets(bind, connects).createSocket()
                        try {
                            socket.connect(InetSocketAddress(InetAddress.getByName(ip), config.getString("raw").substringAfterLast(':').toInt()), 8000)
                            val ssl = tls.socketFactory.createSocket(socket, HOST, socket.port, true) as SSLSocket
                            ssl.enabledProtocols = arrayOf("TLSv1.3")
                            ssl.soTimeout = 90_000
                            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                            ssl.startHandshake()
                            return rawEndpoint(ssl.inputStream, ssl.outputStream, { ssl.close() }, role)
                        } catch (e: Throwable) { socket.close(); throw e }
                    }
                    control = open(0)
                    data += open(1)
                }
                else -> {
                    val http = OkHttpClient.Builder().socketFactory(BoundSockets(bind, connects)).sslSocketFactory(tls.socketFactory, trust)
                        .dns { name -> if (name != HOST) throw UnknownHostException(); listOf(InetAddress.getByName(ip)) }
                        .proxy(Proxy.NO_PROXY).protocols(listOf(Protocol.HTTP_1_1))
                        .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
                        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(8, TimeUnit.SECONDS).build()
                    client = http
                    control = Ws("/control").also { it.awaitOpen() }
                    if (https) http.newCall(request("/health").build()).execute().use { response ->
                        ensure(response.code == 200, "health"); response.body.bytes()
                    } else data += Ws("/data").also { it.awaitOpen() }
                }
            }
            setupMs = ms(now() - start)
            ensure(connects.get() == expectedConnections, "setup_connection_count")
            val ctrl = checkNotNull(control)
            schedule.scheduleWithFixedDelay({
                try {
                    val seq = sequence.incrementAndGet()
                    val job = activeJob.get()
                    waiting[seq] = Stamp(now(), job)
                    ensure(waiting.size < 4096, "echo_backlog")
                    ensure(ctrl.send(ByteBuffer.allocate(13).put(PING.toByte()).putLong(seq).putInt(job).array()), "ping_enqueue")
                } catch (e: Exception) { ctrl.failure.compareAndSet(null, e) }
            }, 0, 20, TimeUnit.MILLISECONDS)
            schedule.scheduleWithFixedDelay({
                try { peak.sample(queued()) } catch (e: Exception) { ctrl.failure.compareAndSet(null, e) }
            }, 0, 100, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) { close(); throw e }
    }
    private fun queued() = data.sumOf { it.queueSize() }
    private fun request(path: String) = Request.Builder().url("https://$HOST:$port$path")
        .header("Authorization", auth).header("X-Bench-Session", session.toString())

    private abstract inner class Endpoint {
        val events = LinkedBlockingQueue<ByteArray>(64)
        val failure = AtomicReference<Throwable>()
        abstract fun send(bytes: ByteArray): Boolean
        open fun queueSize() = 0L
        abstract fun cancel()
        fun checkFailure() { failure.get()?.let { throw IOException("channel_failure", it) } }
        fun receive(b: ByteArray) {
            try {
                ensure(b.isNotEmpty(), "empty_event")
                if (b[0].toInt() == PING) {
                    ensure(b.size == 13, "echo_length")
                    val buf = ByteBuffer.wrap(b).apply { get() }
                    val stamp = waiting.remove(buf.long)
                    val job = buf.int
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
    private inner class Ws(path: String) : Endpoint() {
        private val open = CountDownLatch(1)
        private val socket = checkNotNull(client).newWebSocket(request(path).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { open.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failure.compareAndSet(null, t); open.countDown() }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { receive(bytes.toByteArray()) }
        })
        fun awaitOpen() { ensure(open.await(10, TimeUnit.SECONDS), "websocket_open_timeout"); checkFailure() }
        override fun send(bytes: ByteArray) = socket.send(bytes.toByteString())
        override fun queueSize() = socket.queueSize()
        override fun cancel() = socket.cancel()
    }
    private fun quicEndpoint(stream: QuicStream, role: Int) = rawEndpoint(stream.inputStream, stream.outputStream, {
        stream.resetStream(1); stream.abortReading(1)
    }, role)
    private fun rawEndpoint(input: InputStream, output: OutputStream, closer: () -> Unit, role: Int): Endpoint {
        val read = DataInputStream(input)
        try {
            // All auth bytes are sent only after the TLS handshake and full certificate validation.
            output.write(ByteBuffer.allocate(42).put(0).put(token).put(role.toByte()).putLong(session).array()); output.flush()
            ensure(read.readUnsignedByte() == 1, "raw_auth")
        } catch (e: Throwable) { closer(); throw e }
        return object : Endpoint() {
            private val closed = AtomicBoolean()
            init {
                if (role == 0) thread(name = "bench-control-reader", isDaemon = true) {
                    try {
                        while (!closed.get()) {
                            val n = read.readInt()
                            ensure(n in 1..(CHUNK + 32), "raw_frame_length")
                            receive(ByteArray(n).also { read.readFully(it) })
                        }
                    } catch (e: Exception) { if (!closed.get()) failure.compareAndSet(null, e) }
                }
            }
            @Synchronized override fun send(bytes: ByteArray): Boolean {
                ensure(!closed.get(), "raw_closed")
                ensure(bytes.size in 1..(CHUNK + 32), "raw_frame_length")
                output.write(ByteBuffer.allocate(bytes.size + 4).putInt(bytes.size).put(bytes).array()); output.flush()
                return true
            }
            override fun cancel() { if (closed.compareAndSet(false, true)) closer() }
        }
    }

    fun idle(millis: Long) {
        val end = now() + millis * 1_000_000
        while (now() < end) { checkNotNull(control).checkFailure(); Thread.sleep(10) }
    }
    private fun start(file: Int, job: Int) {
        activeJob.set(job)
        peak = Peak().also { it.sample(queued()) }
        ensure(checkNotNull(control).send(ByteBuffer.allocate(10).put(START.toByte()).putInt(job).putInt(file).put(streams.toByte()).array()), "start_enqueue")
        checkNotNull(control).await(READY, job, 5000)
    }
    private inner class Transfer(file: Int, private val job: Int) {
        private val cancelled = AtomicBoolean()
        private val failure = AtomicReference<Throwable>()
        private val call = AtomicReference<Call>()
        val started = CountDownLatch(streams)
        val stopped = CountDownLatch(streams)
        val stopTime = AtomicLong()
        private val path = File(images, samples.getJSONObject(file).getString("name"))
        private val size = samples.getJSONObject(file).getLong("bytes")
        private val pacingStart = now()
        private val reserved = AtomicLong()
        private val futures = (0 until streams).map { part -> producer.submit {
            started.countDown()
            try {
                if (https) {
                    val body = object : RequestBody() {
                        override fun contentType() = "image/jpeg".toMediaType()
                        override fun contentLength() = size
                        override fun isOneShot() = true
                        override fun writeTo(sink: BufferedSink) = write(part, sink)
                    }
                    val upload = checkNotNull(client).newCall(request("/upload/$file/$job").put(body).build())
                    call.set(upload)
                    if (cancelled.get()) upload.cancel()
                    upload.execute().use { response ->
                        ensure(response.code == 200, "http_status")
                        ensure(JSONObject(response.body.string()).getBoolean("verified"), "http_digest")
                    }
                } else write(part, null)
            } catch (e: Throwable) { if (!cancelled.get()) failure.compareAndSet(null, e) }
            finally { stopTime.updateAndGet { maxOf(it, now()) }; stopped.countDown() }
        } }
        private fun pace(n: Int) {
            if (rate == 0L) return
            val offset = reserved.getAndAdd(n.toLong()) // Shared across all parallel streams, not per-stream 60Mbps.
            while (true) {
                val delay = pacingStart + (offset * 8e9 / rate).toLong() - now()
                if (delay <= 0) break
                checkCancel(); LockSupport.parkNanos(minOf(delay, 1_000_000))
            }
        }
        private fun write(part: Int, sink: BufferedSink?) {
            val offset = size * part / streams
            val length = size * (part + 1) / streams - offset
            val buffer = ByteArray(CHUNK)
            var sent = 0L
            RandomAccessFile(path, "r").use { input ->
                input.seek(offset)
                while (sent < length) {
                    checkCancel()
                    val n = minOf(CHUNK.toLong(), length - sent).toInt()
                    input.readFully(buffer, 0, n)
                    pace(n)
                    if (sink != null) sink.write(buffer, 0, n)
                    else {
                        val channel = data[part]
                        while (channel.queueSize() + n + 6 > QUEUE) {
                            checkCancel(); channel.checkFailure(); LockSupport.parkNanos(500_000)
                        }
                        val frame = ByteBuffer.allocate(n + 6).put(DATA.toByte()).putInt(job).put(part.toByte()).put(buffer, 0, n).array()
                        ensure(channel.send(frame), "data_enqueue")
                    }
                    sent += n
                    peak.recordQueue(queued())
                }
            }
            if (sink != null) sink.flush()
            else ensure(data[part].send(ByteBuffer.allocate(6).put(END.toByte()).putInt(job).put(part.toByte()).array()), "end_enqueue")
        }
        private fun checkCancel() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("cancelled")
        }
        fun checkFailure() { failure.get()?.let { throw IOException("producer_failed", it) } }
        fun cancel() {
            ensure(started.await(3, TimeUnit.SECONDS), "producer_not_started")
            cancelled.set(true); call.get()?.cancel()
            data.forEach { it.cancel() }
            futures.forEach { it.cancel(true) }
        }
    }
    fun upload(file: Int) {
        val job = nextJob()
        val start = now()
        start(file, job)
        val transfer = Transfer(file, job)
        checkNotNull(control).await(DONE, job, 90_000)
        ensure(transfer.stopped.await(5, TimeUnit.SECONDS), "producer_finish")
        transfer.checkFailure(); checkNotNull(control).checkFailure()
        val duration = ms(now() - start)
        val sample = samples.getJSONObject(file)
        peak.sample(queued())
        ensure(connects.get() == expectedConnections, "connection_not_reused")
        row(JSONObject().put("type", "upload").put("round", round).put("mode", mode).put("job", job)
            .put("file", sample.getString("name")).put("bytes", sample.getLong("bytes")).put("ms", duration)
            .put("mbps", sample.getLong("bytes") * 8 / (duration * 1000)).put("verified", true)
            .put("memory", peak.json()).put("connections", connects.get()).put("streams", streams))
        activeJob.set(0)
    }
    fun cancelProbe(file: Int) {
        val job = nextJob()
        start(file, job)
        val transfer = Transfer(file, job)
        ensure(transfer.started.await(3, TimeUnit.SECONDS), "producer_not_started")
        Thread.sleep(200)
        ensure(transfer.stopped.count > 0, "probe_already_finished")
        val start = now()
        transfer.cancel()
        val ctrl = checkNotNull(control)
        ensure(ctrl.send(command(CANCEL, job)), "cancel_enqueue")
        val ack = ctrl.await(CANCELLED, job, 5000)
        val serverMs = ms(now() - start)
        ensure(transfer.stopped.await(3, TimeUnit.SECONDS), "cancel_local_stop")
        val localMs = ms(transfer.stopTime.get() - start)
        val accepted = ByteBuffer.wrap(ack, 5, 8).long
        ensure(accepted in 1 until samples.getJSONObject(file).getLong("bytes"), "probe_byte_count")
        idle(300)
        ensure(ctrl.send(command(STATUS, job)), "status_enqueue")
        val after = ByteBuffer.wrap(ctrl.await(STATUS_ACK, job, 5000), 5, 8).long
        ensure(accepted == after, "bytes_accepted_after_cancel"); ensure(ctrl.events.isEmpty(), "completion_after_cancel")
        row(JSONObject().put("type", "cancel").put("round", round).put("mode", mode).put("job", job)
            .put("local_producer_stop_ms", localMs).put("server_cancel_ack_ms", serverMs)
            .put("accepted_bytes", accepted).put("accepted_bytes_after_300ms", after).put("no_completion_after_cancel", true))
        activeJob.set(0)
    }
    override fun close() {
        schedule.shutdownNow(); producer.shutdownNow()
        control?.cancel(); data.forEach { it.cancel() }
        quic?.closeAndWait(Duration.ofMillis(200))
        client?.let { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
    }
    fun flushRtts() { synchronized(rtts) { rtts.forEach(row) } }
}
