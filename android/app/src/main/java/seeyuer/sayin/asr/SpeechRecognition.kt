package seeyuer.sayin.asr

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import seeyuer.sayin.BuildConfig

internal enum class RecognitionPhase { Idle, Loading, Listening, Finishing, Cancelling }
internal enum class LocalModelPhase { Unloaded, Loading, Warming, Ready, Failed, Missing }
internal enum class RecognitionIssue { Permission, Model, Microphone, TooSlow, RecordingLimit, Recognition, NoSpeech, CloudConfiguration, CloudNetwork, CloudAuthentication, CloudAccessDenied, CloudQuota, CloudTimeout, CloudProtocol, CloudService }
internal data class RecognitionState(
    val session: Long = 0,
    val phase: RecognitionPhase = RecognitionPhase.Idle,
    val transcript: String = "",
    val issue: RecognitionIssue? = null,
    val finished: Boolean = false,
    val audioLevel: Float = 0f,
    val speechPause: Boolean = false,
    val speechEnding: Boolean = false,
    val noSpeech: Boolean = false,
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
    private var vad: VadAutoStop? = null
    private var generation = 0L
    private val modelLock = Mutex()
    private var decoder: LocalSpeechDecoder? = null
    private var punctuation: PunctuationRestorer? = null
    var localArtifact: () -> LocalVoiceArtifact? = { null }
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
                    modelLock.withLock {
                        val loaded = artifact?.openDecoder() ?: XAsrDecoder(application.assets)
                        try {
                            ensureActive()
                            mutableModel.value = LocalModelPhase.Warming
                            loaded.warmUp()
                            ensureActive()
                            decoder = loaded
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

    override fun start(cloud: CloudConfiguration?) {
        val cloudSession = cloud != null
        if (closed || job != null) return
        if (!cloudSession && mutableModel.value != LocalModelPhase.Ready) { prepareLocal(); return }
        val session = ++generation
        mutableState.value = RecognitionState(session = session, phase = RecognitionPhase.Loading)
        job = scope.launch {
            var issue = RecognitionIssue.Model
            try {
                withContext(Dispatchers.Default) {
                    fun openMicrophone(): PcmSource {
                        ensureActive()
                        issue = RecognitionIssue.Microphone
                        if (ContextCompat.checkSelfPermission(application, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            throw SecurityException("Microphone permission is required")
                        }
                        val fallbackPause = SpeechPause()
                        val detector = runCatching { VadAutoStop(application) }.getOrNull()
                        vad = detector
                        return AndroidMicrophone(application) { rms, samples, pcm ->
                            val progress = runCatching { detector?.accept(pcm) }.getOrNull()
                            if (detector != null && progress == null) {
                                detector.close()
                                vad = null
                            }
                            val paused = progress?.ended ?: fallbackPause.update(rms, samples)
                            val ending = progress?.ending == true
                            val noSpeech = progress?.noSpeech == true
                            mutableState.update {
                                if (it.session == session && it.phase == RecognitionPhase.Listening) {
                                    it.copy(
                                        audioLevel = (rms * 8f).coerceIn(0f, 1f),
                                        speechPause = paused,
                                        speechEnding = ending,
                                        noSpeech = noSpeech,
                                        issue = if (noSpeech) RecognitionIssue.NoSpeech else it.issue,
                                    )
                                } else {
                                    it
                                }
                            }
                        }.also { source ->
                            synchronized(microphoneLock) { microphone = source }
                        }
                    }
                    fun listening() {
                        ensureActive()
                        mutableState.update { if (it.session == session && it.phase == RecognitionPhase.Loading) it.copy(phase = RecognitionPhase.Listening) else it }
                        issue = RecognitionIssue.Recognition
                    }
                    val text = if (cloud != null) {
                        recognizeCloud(cloud, ::openMicrophone, ::listening) { text ->
                            mutableState.update { if (it.session == session && it.busy && it.phase != RecognitionPhase.Cancelling) it.copy(transcript = text) else it }
                        }.text
                    } else modelLock.withLock {
                        val local = checkNotNull(decoder)
                        val source = openMicrophone()
                        listening()
                        val raw = local.recognize(source) { text ->
                            mutableState.update { if (it.session == session && it.busy && it.phase != RecognitionPhase.Cancelling) it.copy(transcript = text) else it }
                        }
                        if (local is XAsrDecoder) punctuate(raw) else raw
                    }
                    ensureActive()
                    // Only Finish may authorize completion, even if a provider stops on its own.
                    if (mutableState.value.phase != RecognitionPhase.Finishing) throw CancellationException()
                    mutableState.update { if (it.phase == RecognitionPhase.Finishing) it.copy(transcript = text, finished = true) else it }
                }
            } catch (cancelled: CancellationException) {
                // Keep the last visible draft, but do not review or send an interrupted session.
            } catch (error: Exception) {
                if (!cloudSession) Log.e("SayInAsr", "Local recognition failed ($issue)", error)
                val reason = when (error) {
                    is SecurityException -> RecognitionIssue.Permission
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
                synchronized(microphoneLock) {
                    microphone?.close()
                    microphone = null
                }
                vad?.close()
                vad = null
                mutableState.update { it.copy(phase = RecognitionPhase.Idle) }
                job = null
            }
        }
    }

    override fun finish() {
        if (mutableState.value.phase != RecognitionPhase.Listening) return
        mutableState.update { it.copy(phase = RecognitionPhase.Finishing) }
        synchronized(microphoneLock) { microphone?.requestStop() }
    }

    @Synchronized
    private fun punctuate(text: String): String {
        if (text.isBlank()) return text
        return runCatching {
            (punctuation ?: PunctuationRestorer(application).also { punctuation = it }).restore(text)
        }.getOrDefault(text)
    }

    override fun clearResult() {
        if (job == null) mutableState.value = RecognitionState(session = generation)
    }

    override fun cancel() {
        if (job == null) return
        mutableState.update { it.copy(phase = RecognitionPhase.Cancelling, finished = false) }
        job?.cancel()
        synchronized(microphoneLock) { microphone?.requestStop() }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancel()
        preparation?.cancel()
        // The mutex waits for in-flight native inference before releasing its weights.
        scope.launch(NonCancellable + Dispatchers.Default) {
            modelLock.withLock { decoder?.close(); decoder = null }
            punctuation?.close()
            punctuation = null
        }
        scope.cancel()
    }
}
