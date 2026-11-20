package seeyuer.sayin.polish

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PolishConfigStoreInstrumentedTest {
    @Test fun multipleConfigurationsKeepIndependentKeysAndModels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = AtomicFile(File(context.cacheDir, "polish-config-test.bin"))
        file.delete()
        try {
            val store = PolishConfigStore(context, file, alias = "sayin.ai.polish.test")
            val deepseek = PolishConfig.preset(PolishProvider.DeepSeek).copy(
                id = "config-a", name = "DeepSeek work", key = "key-a", model = "deepseek-flash",
            )
            val gateway = PolishConfig.preset(PolishProvider.Custom).copy(
                id = "config-b", name = "Gateway", protocol = PolishProtocol.Chat,
                baseUrl = "https://gateway.example.com/v1", model = "gateway-model", key = "key-b",
            )
            store.save(PolishConfigSet(listOf(deepseek, gateway), deepseek.id))
            val first = store.load() as PolishConfigLoad.Ready
            assertEquals("config-a", first.set.activeId)
            assertEquals("key-a", first.set.configurations.first { it.id == "config-a" }.key)
            assertEquals("key-b", first.set.configurations.first { it.id == "config-b" }.key)
            assertEquals("gateway-model", first.set.configurations.first { it.id == "config-b" }.model)

            store.save(first.set.copy(activeId = gateway.id))
            val second = store.load() as PolishConfigLoad.Ready
            assertEquals("config-b", second.set.activeId)
            assertEquals("deepseek-flash", second.set.configurations.first { it.id == "config-a" }.model)
            assertEquals("https://gateway.example.com/v1",
                second.set.configurations.first { it.id == "config-b" }.baseUrl)
        } finally {
            file.delete()
        }
    }
}
