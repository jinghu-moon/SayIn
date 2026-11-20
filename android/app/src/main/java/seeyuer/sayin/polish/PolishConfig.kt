package seeyuer.sayin.polish

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
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
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val revision: Long = 1L,
) {
    val normalizedBaseUrl: String get() = baseUrl.trim().trimEnd('/')
    val officialCatalogProvider: String? get() = when (Triple(provider, protocol, normalizedBaseUrl)) {
        Triple(PolishProvider.DeepSeek, PolishProtocol.Chat, "https://api.deepseek.com"),
        Triple(PolishProvider.DeepSeek, PolishProtocol.Chat, "https://api.deepseek.com/v1") -> "deepseek"
        Triple(PolishProvider.Qwen, PolishProtocol.Chat, "https://dashscope.aliyuncs.com/compatible-mode/v1") -> "alibaba-cn"
        Triple(PolishProvider.Qwen, PolishProtocol.Chat, "https://dashscope-intl.aliyuncs.com/compatible-mode/v1") -> "alibaba"
        Triple(PolishProvider.OpenAi, PolishProtocol.Chat, "https://api.openai.com/v1") -> "openai"
        Triple(PolishProvider.Anthropic, PolishProtocol.Messages, "https://api.anthropic.com/v1") -> "anthropic"
        else -> null
    }
    val fingerprint: String get() = sha256(
        listOf(id, provider.id, protocol.name, normalizedBaseUrl, model.trim(), auth.name,
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
        .put("version", 2)
        .put("id", id)
        .put("name", name)
        .put("revision", revision)
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
            require(json.getInt("version") in 1..2)
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
                id = json.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                name = json.optString("name"),
                revision = json.optLong("revision", 1L).coerceAtLeast(1L),
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

internal data class PolishConfigSet(
    val configurations: List<PolishConfig>,
    val activeId: String?,
) {
    val active: PolishConfig? get() = configurations.firstOrNull { it.id == activeId }
}

internal sealed interface PolishConfigLoad {
    data object Missing : PolishConfigLoad
    data object Corrupt : PolishConfigLoad
    data class Ready(val set: PolishConfigSet) : PolishConfigLoad
}

internal class PolishConfigStore(
    context: Context,
    private val file: AtomicFile = AtomicFile(File(context.noBackupFilesDir, "polish-config.bin")),
    private val alias: String = "sayin.ai.polish.v1",
) {
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

    fun load(): PolishConfigLoad {
        val encrypted = try {
            file.openRead().use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1_048_576)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: java.io.FileNotFoundException) {
            return if (file.baseFile.exists()) PolishConfigLoad.Corrupt else PolishConfigLoad.Missing
        } catch (_: Exception) {
            return PolishConfigLoad.Corrupt
        }
        return try {
            require(encrypted.size in 29..1_048_576)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            val json = JSONObject(cipher.doFinal(encrypted.copyOfRange(12, encrypted.size)).toString(Charsets.UTF_8))
            when (json.getInt("version")) {
                1 -> {
                    // Migration from the first single-config build. Persist the generated
                    // stable id immediately so it survives the next restart.
                    val config = PolishConfig.fromJson(json)
                    val set = PolishConfigSet(listOf(config), config.id)
                    runCatching { save(set) }
                    PolishConfigLoad.Ready(set)
                }
                2 -> {
                    val array = json.getJSONArray("configurations")
                    require(array.length() in 1..32)
                    val configurations = buildList {
                        for (index in 0 until array.length()) add(PolishConfig.fromJson(array.getJSONObject(index)))
                    }
                    require(configurations.map { it.id }.toSet().size == configurations.size)
                    val activeId = if (json.isNull("activeId")) null else json.getString("activeId")
                    require(activeId == null || configurations.any { it.id == activeId })
                    PolishConfigLoad.Ready(PolishConfigSet(configurations, activeId))
                }
                else -> error("Unsupported polish config version")
            }
        } catch (_: Exception) {
            PolishConfigLoad.Corrupt
        }
    }

    fun save(set: PolishConfigSet) {
        require(set.configurations.isNotEmpty() && set.configurations.size <= 32)
        require(set.activeId == null || set.configurations.any { it.id == set.activeId })
        set.configurations.forEach { config -> check(config.validate() == null) { config.validate() ?: "" } }
        val json = JSONObject()
            .put("version", 2)
            .put("activeId", set.activeId ?: JSONObject.NULL)
            .put("configurations", org.json.JSONArray().also { array ->
                set.configurations.forEach { array.put(it.toJson()) }
            })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(aad)
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
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
