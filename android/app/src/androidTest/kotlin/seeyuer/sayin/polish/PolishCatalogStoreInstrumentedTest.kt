package seeyuer.sayin.polish

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PolishCatalogStoreInstrumentedTest {
    private lateinit var context: android.content.Context
    private lateinit var file: AtomicFile
    private lateinit var preferences: android.content.SharedPreferences

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        file = AtomicFile(File(context.cacheDir, "polish-catalog-test.json"))
        preferences = context.getSharedPreferences("polish_catalog_test", android.content.Context.MODE_PRIVATE)
        file.delete()
        preferences.edit().clear().commit()
    }

    @After fun tearDown() {
        file.delete()
        preferences.edit().clear().commit()
    }

    @Test fun snapshotCacheAndUpgradeSelection() {
        val store = PolishCatalogStore(context, file, preferences)
        val snapshot = store.load()
        assertNotNull(snapshot)
        val manifest = JSONObject(context.assets.open("basellm/manifest.json").use {
            it.readBytes().toString(Charsets.UTF_8)
        })
        val snapshotSha = manifest.getString("sha256")

        writeCache(cachedCatalog("cached-model"))
        preferences.edit().putString("cache_snapshot", snapshotSha).commit()
        assertEquals("cached-model", store.load()!!.modelsFor(PolishProvider.DeepSeek).single().id)

        // A cache built from a different bundled snapshot is ignored after upgrade.
        preferences.edit().putString("cache_snapshot", "old-snapshot").commit()
        assertTrue(store.load()!!.modelsFor(PolishProvider.DeepSeek).none { it.id == "cached-model" })

        // Corrupt cache falls back to the bundled snapshot even when the marker matches.
        val corrupt = file.startWrite()
        corrupt.write("not-json".toByteArray())
        file.finishWrite(corrupt)
        preferences.edit().putString("cache_snapshot", snapshotSha).commit()
        assertNotNull(store.load())
        assertTrue(store.load()!!.modelsFor(PolishProvider.DeepSeek).isNotEmpty())
    }

    private fun cachedCatalog(model: String): JSONObject = JSONObject()
        .put("deepseek", JSONObject().put("models", JSONObject().put(model, JSONObject()
            .put("id", model).put("name", model)
            .put("modalities", JSONObject().put("output", org.json.JSONArray().put("text"))))))

    private fun writeCache(catalog: JSONObject) {
        val payload = JSONObject()
            .put("version", "cached")
            .put("fetchedAt", "now")
            .put("catalog", catalog)
        val stream = file.startWrite()
        stream.write(payload.toString().toByteArray(Charsets.UTF_8))
        file.finishWrite(stream)
    }
}
