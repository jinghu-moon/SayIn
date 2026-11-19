package seeyuer.sayin.polish

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal enum class PolishProtocol { Chat, Messages }
internal enum class PolishAuth { Bearer, XApiKey }
internal enum class PolishMaxTokensField { MaxTokens, MaxCompletionTokens }
internal enum class PolishThinking { None, DisabledLow }
internal enum class PolishProvider(val id: String) {
    DeepSeek("deepseek"), Qwen("qwen"), OpenAi("openai"), Anthropic("anthropic"), Custom("custom");
    companion object {
        fun from(value: String) = entries.firstOrNull { it.id == value } ?: Custom
    }
}

internal data class PolishConfig(
    val provider: PolishProvider,
    val protocol: PolishProtocol,
    val baseUrl: String,
    val model: String,
    val key: String,
    val auth: PolishAuth,
    val apiVersion: String,
    val maxTokensField: PolishMaxTokensField,
    val thinking: PolishThinking,
) {
    val normalizedBaseUrl: String get() = baseUrl.trim().trimEnd('/')
    val fingerprint: String get() = sha256(
        listOf(provider.id, protocol.name, normalizedBaseUrl, model.trim(), auth.name,
            apiVersion, maxTokensField.name, thinking.name).joinToString("\u0000")
    )
    fun withKey(value: String) = copy(key = value.trim())
    fun validate(allowLoopbackHttp: Boolean = false): String? = when {
        !normalizedBaseUrl.startsWith("https://") &&
            !(allowLoopbackHttp && normalizedBaseUrl.startsWith("http://127.0.0.1")) &&
            !(allowLoopbackHttp && normalizedBaseUrl.startsWith("http://localhost")) -> "Base URL 必须是 HTTPS"
        normalizedBaseUrl.length > 300 -> "Base URL 过长"
        model.isBlank() || model.length > 120 -> "模型名无效"
        key.isBlank() || key.length > 8192 -> "API Key 为空或过长"
        protocol == PolishProtocol.Messages && apiVersion.isBlank() -> "Messages 需要 API 版本"
        else -> null
    }

    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("provider", provider.id)
        .put("protocol", protocol.name)
        .put("baseUrl", normalizedBaseUrl)
        .put("model", model.trim())
        .put("key", key)
        .put("auth", auth.name)
        .put("apiVersion", apiVersion)
        .put("maxTokensField", maxTokensField.name)
        .put("thinking", thinking.name)
        .put("fingerprint", fingerprint)

    companion object {
        val presets: List<PolishConfig> = listOf(
            PolishConfig(PolishProvider.DeepSeek, PolishProtocol.Chat, "https://api.deepseek.com",
                "deepseek-flash", "", PolishAuth.Bearer, "", PolishMaxTokensField.MaxTokens, PolishThinking.None),
            PolishConfig(PolishProvider.Qwen, PolishProtocol.Chat,
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.8-flash", "",
                PolishAuth.Bearer, "", PolishMaxTokensField.MaxTokens, PolishThinking.None),
            PolishConfig(PolishProvider.OpenAi, PolishProtocol.Chat, "https://api.openai.com/v1",
                "gpt-6-luna", "", PolishAuth.Bearer, "", PolishMaxTokensField.MaxCompletionTokens, PolishThinking.None),
            PolishConfig(PolishProvider.Anthropic, PolishProtocol.Messages, "https://api.anthropic.com/v1",
                "claude-haiku-5-5", "", PolishAuth.XApiKey, "2026-10-21",
                PolishMaxTokensField.MaxTokens, PolishThinking.DisabledLow),
        )

        fun preset(provider: PolishProvider, key: String = ""): PolishConfig =
            presets.firstOrNull { it.provider == provider }?.copy(key = key)
                ?: PolishConfig(provider, PolishProtocol.Chat, "", "", key, PolishAuth.Bearer, "",
                    PolishMaxTokensField.MaxTokens, PolishThinking.None)

        fun custom(protocol: PolishProtocol = PolishProtocol.Chat): PolishConfig =
            PolishConfig(PolishProvider.Custom, protocol, "", "", "",
                if (protocol == PolishProtocol.Messages) PolishAuth.XApiKey else PolishAuth.Bearer,
                if (protocol == PolishProtocol.Messages) "2023-06-01" else "",
                PolishMaxTokensField.MaxTokens, PolishThinking.None)

        fun fromJson(json: JSONObject): PolishConfig {
            require(json.getInt("version") == 1)
            val protocol = PolishProtocol.valueOf(json.getString("protocol"))
            val config = PolishConfig(
                provider = PolishProvider.from(json.getString("provider")),
                protocol = protocol,
                baseUrl = json.getString("baseUrl"),
                model = json.getString("model"),
                key = json.getString("key"),
                auth = PolishAuth.valueOf(json.getString("auth")),
                apiVersion = json.optString("apiVersion"),
                maxTokensField = PolishMaxTokensField.valueOf(json.getString("maxTokensField")),
                thinking = PolishThinking.valueOf(json.getString("thinking")),
            )
            require(config.normalizedBaseUrl.length <= 300 && config.model.length <= 120 && config.key.length <= 8192)
            require(config.validate() == null)
            return config
        }
    }
}

internal sealed interface PolishConfigState {
    data object Missing : PolishConfigState
    data object Corrupt : PolishConfigState
    data object Unauthorized : PolishConfigState
    data class Ready(val config: PolishConfig) : PolishConfigState
}

internal class PolishConfigStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "polish-config.bin"))
    private val alias = "sayin.ai.polish.v1"
    private val aad = "SayIn/ai-polish/v1".toByteArray()

    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create && !file.baseFile.exists()) { "Polish key missing while encrypted config exists" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    fun load(): PolishConfigState {
        val encrypted = try {
            file.openRead().use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 65_536)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: java.io.FileNotFoundException) {
            return if (file.baseFile.exists()) PolishConfigState.Corrupt else PolishConfigState.Missing
        } catch (_: Exception) {
            return PolishConfigState.Corrupt
        }
        return try {
            require(encrypted.size in 29..65_536)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            val json = JSONObject(cipher.doFinal(encrypted.copyOfRange(12, encrypted.size)).toString(Charsets.UTF_8))
            PolishConfigState.Ready(PolishConfig.fromJson(json))
        } catch (_: Exception) {
            PolishConfigState.Corrupt
        }
    }

    fun save(config: PolishConfig) {
        val error = config.validate()
        check(error == null) { error ?: "" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(aad)
        val bytes = cipher.iv + cipher.doFinal(config.toJson().toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun clear() {
        file.delete()
    }
}

internal fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
