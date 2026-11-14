package seeyuer.sayin.quick

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import seeyuer.sayin.R
import seeyuer.sayin.SayInApplication
import seeyuer.sayin.asr.RecognitionPhase

/** The microphone exists only during a user-started recording, with a system notification. */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session get() = (application as SayInApplication).model.speaking
    private var commanded = false
    private var foregroundAccepted = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.quick_recording_channel), NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompat.startForeground(this, ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            foregroundAccepted = true
        } catch (_: Exception) { session.microphoneServiceFailed(); stopSelf(); return }
        scope.launch { session.recognition.state.collect {
            if (commanded && !it.busy) stopSelf()
            else getSystemService(NotificationManager::class.java).notify(ID, notification())
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundAccepted) { session.microphoneServiceFailed(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == FINISH) session.finishRecording()
        else { commanded = true; session.startPreparedRecording() }
        if (!session.recognition.state.value.busy) stopSelf()
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val finishing = session.recognition.state.value.phase == RecognitionPhase.Finishing
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_microphone)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (finishing) R.string.asr_finishing else R.string.quick_recording_notification))
            .setContentIntent(QuickActivity.pendingIntent(this))
            .setOngoing(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (!finishing) builder.addAction(0, getString(R.string.asr_finish), PendingIntent.getService(this, 42,
            Intent(this, RecordingService::class.java).setAction(FINISH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return builder.build()
    }
    override fun onDestroy() {
        if (session.recognition.state.value.busy) session.cancelRecording(restore = false)
        scope.cancel(); super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "recording"
        private const val ID = 41
        private const val FINISH = "seeyuer.sayin.FINISH_RECORDING"
        fun intent(context: Context) = Intent(context, RecordingService::class.java)
    }
}
