package seeyuer.sayin.models

import android.content.Context
import org.json.JSONArray
import seeyuer.sayin.translation.TranslationCatalog

internal enum class ModelCategory { Recognition, Translation }

internal data class ModelFile(val path: String, val bytes: Long, val sha256: String)

internal data class LocalModel(
    val id: String,
    val category: ModelCategory,
    val name: String,
    val precision: String,
    val files: List<ModelFile>,
    val huggingFacePath: String?,
    val modelScopePath: String?,
    val archive: String? = null,
    val modelEntryFile: String? = null,
) {
    val bytes = files.sumOf { it.bytes }
    val label = "$name · $precision"
}

internal class LocalModelCatalog(context: Context, translations: TranslationCatalog) {
    val defaultRecognitionId = "x-asr"
    val defaultTranslationId = translations.defaultModel.id
    val recognition: List<LocalModel> = context.assets.open("local-asr-catalog.json")
        .bufferedReader().use { reader ->
            val entries = JSONArray(reader.readText())
            List(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                val files = entry.getJSONObject("files")
                LocalModel(
                    entry.getString("id"), ModelCategory.Recognition,
                    entry.getString("name"), entry.getString("precision"),
                    files.keys().asSequence().map { path ->
                        val file = files.getJSONObject(path)
                        ModelFile(path, file.getLong("bytes"), file.getString("sha256"))
                    }.toList(),
                    entry.optString("hf").takeUnless { entry.isNull("hf") },
                    entry.optString("modelScope").takeUnless { entry.isNull("modelScope") },
                    entry.optString("archive").takeUnless { entry.isNull("archive") },
                    entry.optString("modelEntryFile").takeUnless { entry.isNull("modelEntryFile") },
                )
            }
        }
    val translation = translations.models.map { model ->
        LocalModel(model.id, ModelCategory.Translation, model.displayName, model.quantization,
            listOf(ModelFile(model.fileName, model.bytes, model.sha256)),
            "/${model.repository}/resolve/${model.revision}/${model.fileName}", model.modelScope)
    }
    val all = recognition + translation

    fun entries(category: ModelCategory) = when (category) {
        ModelCategory.Recognition -> recognition
        ModelCategory.Translation -> translation
    }

    fun recommendedId(category: ModelCategory) = when (category) {
        ModelCategory.Recognition -> defaultRecognitionId
        ModelCategory.Translation -> defaultTranslationId
    }
}
