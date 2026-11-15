package seeyuer.sayin

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelProvider
import seeyuer.sayin.ui.VoiceLinkViewModel
import seeyuer.sayin.asr.CloudSettings

/** Main and quick Activities share exactly one repository, draft and microphone owner. */
class SayInApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private val models by lazy { ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this)) }
    private var initializedModel: VoiceLinkViewModel? = null
    internal val model get() = models[VoiceLinkViewModel::class.java].also { initializedModel = it }
    internal val cloudSettings get() = models[CloudSettings::class.java]
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) initializedModel?.speaking?.releaseTranslation()
    }
}
