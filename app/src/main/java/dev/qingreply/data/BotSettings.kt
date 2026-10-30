package dev.qingreply.data

import dev.qingreply.ai.AiClient
import dev.qingreply.engine.NotificationSource

data class BotSettings(
    val enabled: Boolean = false,
    val providerId: String = "deepseek",
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val model: String = "deepseek-flash",
    val systemPrompt: String = "你是群聊中的 AI 助手。请用自然、简洁、友善的中文回答被提及的问题，控制在 200 字以内。不要假装是人工回复。",
    val aliases: String = "",
    val allowMentionMarker: Boolean = false,
    val allowedConversations: String = "",
    val cooldownSeconds: Int = 20,
    val hourlyLimit: Int = 30,
    val boundSource: String = "",
    val contextEnabled: Boolean = true,
    val contextMessages: Int = 10,
) {
    fun aliasList() = aliases.lineSequence().map { it.trim().removePrefix("@").removePrefix("＠") }
        .filter { it.isNotEmpty() }.distinct().toList()

    fun conversationList() = allowedConversations.lineSequence().map(String::trim)
        .filter(String::isNotEmpty).toSet()

    fun apiError(): String? = when {
        runCatching { AiClient.endpoint(baseUrl) }.isFailure -> "API 地址须为有效的 HTTPS 地址，例如 https://api.openai.com/v1"
        apiKey.isBlank() -> "请填写 API Key"
        apiKey.any { it.code !in 33..126 } -> "API Key 应为不含空格或换行的 ASCII 字符"
        model.isBlank() -> "请填写模型名称"
        else -> null
    }

    fun startError(): String? = apiError() ?: when {
        aliasList().isEmpty() -> "请在触发规则中填写小号的 QQ 昵称或群名片"
        NotificationSource.parse(boundSource) == null -> "请先在触发规则中绑定小号通知实例"
        cooldownSeconds !in 10..300 -> "冷却时间应为 10～300 秒"
        hourlyLimit !in 1..120 -> "每小时上限应为 1～120 条"
        else -> null
    }
}
