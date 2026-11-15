package seeyuer.sayin

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import seeyuer.sayin.ui.VoiceLinkViewModel
import seeyuer.sayin.asr.CloudSettings
import seeyuer.sayin.data.EncryptedPhraseStorage
import seeyuer.sayin.data.PhraseLibrary

/** Main and quick Activities share exactly one repository, draft and microphone owner. */
class SayInApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private val models by lazy { ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this)) }
    private var initializedModel: VoiceLinkViewModel? = null
    internal val model get() = models[VoiceLinkViewModel::class.java].also { initializedModel = it }
    internal val cloudSettings get() = models[CloudSettings::class.java]

    /** One encrypted quick-phrase library for the full page and the quick card. */
    internal lateinit var phrases: PhraseLibrary
        private set
    private val phraseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        phrases = PhraseLibrary(EncryptedPhraseStorage(this), phraseScope).apply { start() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) initializedModel?.speaking?.releaseTranslation()
    }
}
