package dev.qingreply.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import dev.qingreply.ai.AiProviders
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("bot_settings", Context.MODE_PRIVATE)
    private val vault = SecretVault()
    var keyReadFailed = false
        private set
    private val _settings = MutableStateFlow(read())
    val settings = _settings.asStateFlow()

    private fun read(): BotSettings {
        val json = runCatching { JSONObject(prefs.getString("config", "{}")!!) }.getOrDefault(JSONObject())
        val defaults = BotSettings()
        val key = runCatching { prefs.getString("secret", null)?.let(vault::decrypt).orEmpty() }
            .getOrElse { keyReadFailed = true; "" }
        val base = json.optString("baseUrl", defaults.baseUrl)
        return BotSettings(
            enabled = json.optBoolean("enabled", false) && !keyReadFailed && json.optString("boundSource", "").isNotBlank(),
            providerId = json.optString("providerId", AiProviders.infer(base)),
            baseUrl = base, apiKey = key,
            model = json.optString("model", defaults.model),
            systemPrompt = json.optString("systemPrompt", defaults.systemPrompt),
            aliases = json.optString("aliases", ""),
            allowMentionMarker = json.optBoolean("allowMentionMarker", false),
            allowedConversations = json.optString("allowedConversations", ""),
            cooldownSeconds = json.optInt("cooldownSeconds", 20).coerceIn(10, 300),
            hourlyLimit = json.optInt("hourlyLimit", 30).coerceIn(1, 120),
            boundSource = json.optString("boundSource", ""),
            contextEnabled = json.optBoolean("contextEnabled", true),
            contextMessages = json.optInt("contextMessages", 10).coerceIn(1, 20),
            accessibilityFallback = json.optBoolean("accessibilityFallback", false),
            accessibilityChatTitle = json.optString("accessibilityChatTitle", ""),
        )
    }

    @Synchronized
    fun save(value: BotSettings) {
        val json = JSONObject().apply {
            put("enabled", value.enabled); put("baseUrl", value.baseUrl.trim())
            put("providerId", value.providerId); put("boundSource", value.boundSource)
            put("contextEnabled", value.contextEnabled); put("contextMessages", value.contextMessages.coerceIn(1, 20))
            put("accessibilityFallback", value.accessibilityFallback); put("accessibilityChatTitle", value.accessibilityChatTitle.trim())
            put("model", value.model.trim()); put("systemPrompt", value.systemPrompt)
            put("aliases", value.aliases); put("allowMentionMarker", value.allowMentionMarker)
            put("allowedConversations", value.allowedConversations)
            put("cooldownSeconds", value.cooldownSeconds); put("hourlyLimit", value.hourlyLimit)
        }
        val editor = prefs.edit().putString("config", json.toString())
        if (value.apiKey.isBlank()) editor.remove("secret")
        else editor.putString("secret", vault.encrypt(value.apiKey.trim()))
        check(editor.commit()) { "无法保存配置，请检查存储空间" }
        keyReadFailed = false
        _settings.value = value.copy(baseUrl = value.baseUrl.trim(), apiKey = value.apiKey.trim(), model = value.model.trim())
    }
}

private class SecretVault {
    private val alias = "qingreply.api-key.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return encode(cipher.iv) + "." + encode(encrypted)
    }

    fun decrypt(value: String): String {
        val parts = value.split('.')
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
}
