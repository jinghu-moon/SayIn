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
    internal val model get() = models[VoiceLinkViewModel::class.java]
    internal val cloudSettings get() = models[CloudSettings::class.java]
}
