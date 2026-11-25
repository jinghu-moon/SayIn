package seeyuer.sayin.asr

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import seeyuer.sayin.BuildConfig

internal enum class RecognitionPhase { Idle, Loading, Listening, Finishing, Cancelling }
internal enum class RecordingEnd(val canSend: Boolean) {
    Manual(true), Silence(true), NoSpeech(false), Limit(false), CaptureStalled(false),
}
internal enum class LocalModelPhase { Unloaded, Loading, Warming, Ready, Failed, Missing }
internal enum class RecognitionIssue { Permission, Model, Microphone, TooSlow, RecordingLimit, Recognition, NoSpeech, CloudConfiguration, CloudNetwork, CloudAuthentication, CloudAccessDenied, CloudQuota, CloudTimeout, CloudProtocol, CloudService }
internal data class RecognitionState(
    val session: Long = 0,
    val phase: RecognitionPhase = RecognitionPhase.Idle,
    val transcript: String = "",
    val issue: RecognitionIssue? = null,
    val finished: Boolean = false,
    val audioLevel: Float = 0f,
    val end: RecordingEnd? = null,
    val autoFinishUnavailable: Boolean = false,
) {
    val busy get() = phase != RecognitionPhase.Idle
}

/** Session-owned engine. Only a requested finish can produce a completed result. */
internal class SpeechRecognition(context: Context) : RecognitionEngine {
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(RecognitionState())
    override val state = mutableState.asStateFlow()
    private var job: Job? = null
    private val microphoneLock = Any()
    private var microphone: PcmSource? = null
    @Volatile private var lastReadAtMillis = 0L
    private var generation = 0L
    private val modelLock = Mutex()
    private var decoder: LocalSpeechDecoder? = null
    private val punctuationLock = Mutex()
    private val punctuation = PunctuationRestorer(application)
    var localArtifact: () -> LocalVoiceArtifact? = { null }
    var beforeLocalPrepare: suspend () -> Unit = {}
    private var preparation: Job? = null
    private var closed = false
    private val mutableModel = MutableStateFlow(LocalModelPhase.Unloaded)
    override val model = mutableModel.asStateFlow()

    override fun prepareLocal() {
        if (closed || preparation != null || mutableModel.value == LocalModelPhase.Ready) return
        val artifact = localArtifact()
        if (artifact == null && !BuildConfig.LOCAL_MODEL_FIXTURES) {
            mutableModel.value = LocalModelPhase.Missing
            return
        }
        mutableModel.value = LocalModelPhase.Loading
        preparation = scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    beforeLocalPrepare()
                    modelLock.withLock {
                        val loaded = artifact?.openDecoder() ?: XAsrDecoder(application.assets)
                        try {
                            ensureActive()
                            mutableModel.value = LocalModelPhase.Warming
                            loaded.warmUp()
                            ensureActive()
                            decoder = loaded
                            if (loaded is XAsrDecoder) scope.launch(Dispatchers.Default) {
                                punctuationLock.withLock { punctuation.prepare() }
                            }
                            mutableModel.value = LocalModelPhase.Ready
                        } catch (error: Throwable) { loaded.close(); throw error }
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableModel.value = LocalModelPhase.Unloaded
            } catch (error: Exception) {
                mutableModel.value = LocalModelPhase.Failed
            } catch (error: LinkageError) {
                mutableModel.value = LocalModelPhase.Failed
            } catch (error: OutOfMemoryError) {
                mutableModel.value = LocalModelPhase.Failed
            } finally { preparation = null }
        }
    }

    override suspend fun releaseLocal() {
        preparation?.let { it.cancel(); it.join() }
        withContext(Dispatchers.Default) {
            modelLock.withLock {
                decoder?.close()
                decoder = null
            }
        }
        mutableModel.value = LocalModelPhase.Unloaded
    }

    override fun start(cloud: CloudConfiguration?, autoFinish: Boolean, localSilenceMillis: Int) {
        val cloudSession = cloud != null
        if (closed || job != null) return
        if (!cloudSession) prepareLocal()
        val session = ++generation
        mutableState.value = RecognitionState(session = session, phase = RecognitionPhase.Loading)
        job = scope.launch {
            var issue = RecognitionIssue.Model
            var detector: VadAutoStop? = null
            val watchdog = launch {
                while (true) {
                    delay(500)
                    if (mutableState.value.phase == RecognitionPhase.Listening &&
                        SystemClock.elapsedRealtime() - lastReadAtMillis >= 3_000
                    ) endCapture(session, RecordingEnd.CaptureStalled)
                }
            }
            try {
                withContext(Dispatchers.Default) {
                    fun openMicrophone(): PcmSource {
                        ensureActive()
                        issue = RecognitionIssue.Microphone
                        if (ContextCompat.checkSelfPermission(application, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            throw SecurityException("Microphone permission is required")
                        }
                        fun createVad() = runCatching {
                            VadAutoStop(application, autoFinish = autoFinish,
                                localSilenceMillis = localSilenceMillis.takeUnless { cloudSession })
                        }.getOrNull()
                        if (cloudSession) detector = createVad()
                        val input = AndroidMicrophone(application)
                        val source = object : PcmSource by input {
                            private var captured = 0
                            private var needsVad = !cloudSession
                            override var segmentBoundary = false
                                private set
                            override var speechInBlock: Boolean? = null
                                private set
                            override var retainedSampleEnd: Int? = null
                                private set

                            override suspend fun read(): FloatArray? {
                                val block = input.read() ?: return null
                                lastReadAtMillis = SystemClock.elapsedRealtime()
                                if (needsVad) {
                                    needsVad = false
                                    detector = createVad()
                                }
                                val remaining = RECORDING_LIMIT - captured
                                val pcm = if (block.size <= remaining) block else block.copyOf(remaining)
                                captured += pcm.size
                                val vad = detector
                                val progress = if (vad == null) null else runCatching { vad.accept(pcm) }.getOrElse {
                                    detector = null
                                    vad.close()
                                    null
                                }
                                segmentBoundary = progress?.segmentBoundary == true
                                speechInBlock = progress?.speech
                                retainedSampleEnd = detector?.retainedSampleEnd
                                val rms = kotlin.math.sqrt(pcm.sumOf { (it * it).toDouble() } / pcm.size).toFloat()
                                mutableState.update {
                                    if (it.session == session && it.phase == RecognitionPhase.Listening) {
                                        it.copy(
                                            audioLevel = (rms * 8f).coerceIn(0f, 1f),
                                            autoFinishUnavailable = autoFinish && detector == null,
                                        )
                                    } else it
                                }
                                val end = when {
                                    captured == RECORDING_LIMIT -> RecordingEnd.Limit
                                    progress?.noSpeech == true -> RecordingEnd.NoSpeech
                                    progress?.ended == true -> RecordingEnd.Silence
                                    else -> null
                                }
                                end?.let { endCapture(session, it) }
                                return pcm
                            }
                        }
                        synchronized(microphoneLock) {
                            microphone = source
                            if (mutableState.value.phase != RecognitionPhase.Loading) source.requestStop()
                        }
                        return source
                    }
                    fun listening() {
                        ensureActive()
                        lastReadAtMillis = SystemClock.elapsedRealtime()
                        mutableState.update {
                            if (it.session == session && it.phase == RecognitionPhase.Loading) {
                                it.copy(phase = RecognitionPhase.Listening,
                                    autoFinishUnavailable = cloudSession && autoFinish && detector == null)
                            } else it
                        }
                        issue = RecognitionIssue.Recognition
                    }
                    fun publish(text: String) {
                        mutableState.update {
                            if (it.session == session && it.busy && it.phase != RecognitionPhase.Cancelling &&
                                it.end != RecordingEnd.NoSpeech && it.transcript != text
                            ) it.copy(transcript = text) else it
                        }
                    }
                    val text = if (cloud != null) {
                        recognizeCloud(cloud, ::openMicrophone, ::listening) { text ->
                            mutableState.update { if (it.session == session && it.busy && it.phase != RecognitionPhase.Cancelling) it.copy(transcript = text) else it }
                        }.text
                    } else coroutineScope {
                        val source = openMicrophone()
                        listening()
                        val buffer = LocalPcmBuffer(source::requestStop)
                        val capture = launch(Dispatchers.IO) {
                            try {
                                source.use {
                                    while (true) {
                                        ensureActive()
                                        val pcm = source.read() ?: break
                                        buffer.offer(pcm, source.segmentBoundary, source.speechInBlock, source.retainedSampleEnd)
                                    }
                                }
                            } catch (failure: Throwable) {
                                buffer.finish(failure)
                                throw failure
                            } finally {
                                buffer.finish()
                            }
                        }
                        try {
                            issue = RecognitionIssue.Model
                            val ready = withTimeoutOrNull(LOCAL_READY_MILLIS) {
                                model.first { it in setOf(LocalModelPhase.Ready, LocalModelPhase.Failed, LocalModelPhase.Missing) }
                            }
                            check(ready == LocalModelPhase.Ready) { "Local model did not become ready" }
                            issue = RecognitionIssue.Recognition
                            val raw = modelLock.withLock { checkNotNull(decoder).recognize(buffer, ::publish) }
                            if (mutableState.value.end == RecordingEnd.NoSpeech) "" else punctuate(LocalItn.normalize(raw))
                        } finally {
                            source.requestStop()
                            capture.cancel()
                        }
                    }
                    ensureActive()
                    mutableState.update {
                        if (it.phase != RecognitionPhase.Finishing) it else {
                            val endIssue = when (it.end) {
                                RecordingEnd.CaptureStalled -> RecognitionIssue.Microphone
                                RecordingEnd.Limit -> RecognitionIssue.RecordingLimit
                                RecordingEnd.NoSpeech -> if (text.isBlank()) RecognitionIssue.NoSpeech else null
                                else -> null
                            }
                            it.copy(
                                transcript = text,
                                finished = it.end != RecordingEnd.CaptureStalled,
                                issue = endIssue,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // Keep the last visible draft, but do not review or send an interrupted session.
            } catch (error: Exception) {
                if (!cloudSession) Log.e("SayInAsr", "Local recognition failed ($issue)", error)
                val reason = when (error) {
                    is SecurityException -> RecognitionIssue.Permission
                    is MicrophoneReadException -> RecognitionIssue.Microphone
                    is AudioBacklogException -> RecognitionIssue.TooSlow
                    is OfflineRecordingLimitException -> RecognitionIssue.RecordingLimit
                    is CloudRecognitionException -> error.issue.recognitionIssue()
                    else -> issue
                }
                mutableState.update { it.copy(issue = reason, finished = false) }
            } catch (error: LinkageError) {
                Log.e("SayInAsr", "ASR native library could not load", error)
                mutableState.update { it.copy(issue = RecognitionIssue.Model, finished = false) }
            } catch (error: OutOfMemoryError) {
                Log.e("SayInAsr", "Insufficient memory for recognition", error)
                mutableState.update { it.copy(issue = RecognitionIssue.Model, finished = false) }
            } finally {
                watchdog.cancel()
                synchronized(microphoneLock) {
                    microphone?.close()
                    microphone = null
                }
                detector?.close()
                job = null
                mutableState.update { it.copy(phase = RecognitionPhase.Idle) }
            }
        }
    }

    override fun finish() = endCapture(mutableState.value.session, RecordingEnd.Manual)

    private fun endCapture(session: Long, end: RecordingEnd) = synchronized(microphoneLock) {
        val current = mutableState.value
        if (current.session != session || current.phase != RecognitionPhase.Listening) return@synchronized
        mutableState.update { it.copy(phase = RecognitionPhase.Finishing, end = end) }
        microphone?.requestStop()
        Unit
    }

    private suspend fun punctuate(text: String): String {
        if (text.isBlank()) return text
        // A late result is discarded. The native call owns its model until it returns.
        val result = scope.async(Dispatchers.Default) {
            if (!punctuationLock.tryLock()) return@async text
            try {
                punctuation.restore(text)
            } finally {
                punctuationLock.unlock()
            }
        }
        return withTimeoutOrNull(350) { result.await() } ?: text
    }

    override fun clearResult() {
        if (job == null) mutableState.value = RecognitionState(session = generation)
    }

    override fun cancel() {
        if (job == null) return
        synchronized(microphoneLock) {
            mutableState.update { it.copy(phase = RecognitionPhase.Cancelling, finished = false) }
            job?.cancel()
            microphone?.requestStop()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancel()
        preparation?.cancel()
        // The mutex waits for in-flight native inference before releasing its weights.
        scope.launch(NonCancellable + Dispatchers.Default) {
            modelLock.withLock { decoder?.close(); decoder = null }
            punctuationLock.withLock { punctuation.close() }
        }
        scope.cancel()
    }

    private companion object {
        const val RECORDING_LIMIT = 120 * 16_000
        const val LOCAL_READY_MILLIS = 12_000L
    }
}
