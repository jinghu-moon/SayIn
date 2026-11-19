package seeyuer.sayin.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat

internal class AndroidMicrophone @RequiresPermission(Manifest.permission.RECORD_AUDIO) constructor(
    context: Context,
    private val onAudio: (rms: Float, samples: Int) -> Unit,
) : PcmSource {
    private val lock = Any()
    @Volatile private var stopped = false
    private var released = false
    private val buffer = ShortArray(1600)
    private val recorder: AudioRecord

    init {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is required")
        }
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Unsupported recording format" }
        recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 32000)).build()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        } catch (error: Throwable) {
            recorder.release()
            throw error
        }
    }

    override fun read(): FloatArray? {
        if (stopped) return null
        val count = try {
            recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        } catch (error: IllegalStateException) {
            if (stopped) return null
            throw error
        }
        if (count > 0) {
            val samples = FloatArray(count) { buffer[it] / 32768f }
            val rms = kotlin.math.sqrt(samples.sumOf { (it * it).toDouble() } / count).toFloat()
            onAudio(rms, count)
            return samples
        }
        if (stopped) return null
        error("Microphone read failed: $count")
    }

    override fun requestStop() = synchronized(lock) {
        stopped = true
        if (!released && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                recorder.stop()
            } catch (error: IllegalStateException) {
                // The audio service can invalidate the recorder independently of our lock.
                // Stop is terminal; the capture owner still releases it in finally.
                Log.w("SayInAsr", "Microphone was already invalidated during stop", error)
            }
        }
    }

    override fun close() = synchronized(lock) {
        try { requestStop() } finally {
            if (!released) {
                released = true
                recorder.release()
            }
        }
    }
}
