package seeyuer.sayin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import seeyuer.sayin.data.VoiceLinkRepository

internal class VoiceLinkViewModel(application: Application) : AndroidViewModel(application) {
    val repository = VoiceLinkRepository(application)
    override fun onCleared() { repository.close() }
}
