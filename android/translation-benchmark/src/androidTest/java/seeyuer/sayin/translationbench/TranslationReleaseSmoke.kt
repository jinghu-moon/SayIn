package seeyuer.sayin.translationbench

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import org.json.JSONObject
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.util.Locale

/** External Kotlin UI driver: target product R8 and its shared runtime remain untouched. */
class TranslationReleaseSmoke {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation get() = instrumentation.uiAutomation
    private lateinit var product: Context
    private var phase = "setup"

    @Test fun optimizedProductTranslatesWithAsr() {
        try {
            val context = instrumentation.targetContext.createPackageContext("seeyuer.sayin.translationverification", 0)
            product = context
            check(context.packageName == "seeyuer.sayin.translationverification")
            check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
            val catalog = JSONObject(context.assets.open("translation-artifacts.json").bufferedReader().use { it.readText() })
            val models = catalog.getJSONArray("models")
            val model = (0 until models.length()).map(models::getJSONObject)
                .single { it.getString("id") == catalog.getString("defaultModel") }

            phase = "activity"
            // Foreground launch as in the existing UI suite; never change MIUI background permissions.
            uiAutomation.executeShellCommand(
                "am start -W -f 0x10008000 -n ${context.packageName}/seeyuer.sayin.MainActivity",
            ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.copyTo(ByteArrayOutputStream()) } }

            // Load and prewarm the real ASR weights, without opening the microphone.
            phase = "asr-source"
            clickResource("speak_source_cloud")
            clickResource("speak_source_local_short")
            phase = "asr-ready"
            waitResource("speak_local_ready", 180_000)
            val original = "SayIn 不会自动发送译文。😊"
            phase = "source-editor"
            val text = Bundle()
            text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, original)
            check(waitEditor().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text))
            val started = SystemClock.elapsedRealtime()
            phase = "translate-action"
            clickResource("translation_action")
            phase = "translation-completion"
            waitResource("translation_complete", 180_000)
            val elapsed = SystemClock.elapsedRealtime() - started
            val english = waitEditor().text.toString()
            check(english.contains("SayIn") && english.contains("😊") && english != original && !english.contains('\uFFFD'))
            phase = "original-retained"
            clickResource("translation_original")
            waitText(original, 20_000)
            check(waitEditor().text.toString() == original)
            phase = "english-retained"
            clickResource("translation_english")
            waitText(english, 20_000)
            check(waitEditor().text.toString() == english)

            // Never press Send or record audio; only synthetic text enters the local model.
            val report = JSONObject().put("passed", true).put("model", model.getString("id"))
                .put("modelSha256", model.getString("sha256")).put("device", android.os.Build.MODEL)
                .put("sdk", android.os.Build.VERSION.SDK_INT).put("debuggable", false)
                .put("method", "external Kotlin instrumentation -> actual optimized product page -> JNI")
                .put("asrWeightsCoResident", true).put("microphoneOpened", false).put("sendPressed", false)
                .put("originalRetained", true).put("uiTranslationMs", elapsed)

            File(instrumentation.targetContext.getExternalFilesDir(null), "product-native-report.json").writeText(report.toString(2))
        } catch (error: Throwable) {
            // No draft, model output or prompt in failure logs.
            throw AssertionError("${error.javaClass.simpleName} at $phase")
        } finally {
            uiAutomation.executeShellCommand("am force-stop seeyuer.sayin.translationverification").close()
        }
    }

    private fun resources(name: String): List<String> = listOf("en", "zh-Hans").map { language ->
        val config = android.content.res.Configuration(product.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        val context = product.createConfigurationContext(config)
        context.getString(context.resources.getIdentifier(name, "string", product.packageName))
    }

    private fun clickResource(name: String) {
        val status = Bundle()
        status.putString("stage", name)
        instrumentation.sendStatus(0, status)
        val values = resources(name)
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = uiAutomation.rootInActiveWindow
            var node = values.firstNotNullOfOrNull { findText(root, it) }
            for (depth in 0 until 4) {
                val current = node ?: break
                if (current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return
                }
                node = current.parent
            }
            SystemClock.sleep(100)
        }
        error("Control unavailable")
    }

    private fun waitResource(name: String, timeout: Long) = waitAnyText(resources(name), timeout)
    private fun waitText(value: String, timeout: Long) = waitAnyText(listOf(value), timeout)
    private fun waitAnyText(values: List<String>, timeout: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = uiAutomation.rootInActiveWindow
            if (values.any { findText(root, it) != null }) return
            SystemClock.sleep(100)
        }
        error("Expected state unavailable")
    }

    // Compose virtual nodes support tree traversal; text search on the framework provider is insufficient.
    private fun findText(node: AccessibilityNodeInfo?, value: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == value) return node
        for (index in 0 until node.childCount) {
            val found = findText(node.getChild(index), value)
            if (found != null) return found
        }
        return null
    }

    private fun waitEditor(): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val found = findEditor(uiAutomation.rootInActiveWindow)
            if (found != null) return found
            SystemClock.sleep(100)
        }
        error("Editor unavailable")
    }

    private fun findEditor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className?.toString() == "android.widget.EditText") return node
        for (index in 0 until node.childCount) {
            val found = findEditor(node.getChild(index))
            if (found != null) return found
        }
        return null
    }
}
