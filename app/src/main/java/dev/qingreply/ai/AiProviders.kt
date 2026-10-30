package dev.qingreply.ai

import dev.qingreply.data.BotSettings

data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val models: List<String>,
    val hint: String,
) {
    fun applyTo(settings: BotSettings): BotSettings {
        if (id == "custom") return settings.copy(providerId = id)
        val sameDestination = runCatching { AiClient.endpoint(settings.baseUrl) == AiClient.endpoint(baseUrl) }.getOrDefault(false)
        return settings.copy(providerId = id, baseUrl = baseUrl, model = models.firstOrNull().orEmpty(),
            apiKey = if (settings.providerId == id && sameDestination) settings.apiKey else "")
    }
}

object AiProviders {
    val all = listOf(
        AiProvider("deepseek", "DeepSeek", "https://api.deepseek.com", listOf("deepseek-flash", "deepseek-v4-pro"),
            "默认使用 DeepSeek Flash，并关闭深度思考以便快速回复。填入 DeepSeek 平台的 API Key 即可。"),
        AiProvider("openai", "OpenAI", "https://api.openai.com/v1", listOf("gpt-4o-mini", "gpt-4.1-mini"),
            "使用支持 Chat Completions 的模型；可获取模型列表后选择。"),
        AiProvider("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", listOf("qwen-plus", "qwen-turbo", "qwen-max"),
            "阿里云百炼中国内地接口。国际站或专属地域请修改 API 地址。"),
        AiProvider("moonshot", "Moonshot / Kimi", "https://api.moonshot.cn/v1", listOf("moonshot-v1-8k", "moonshot-v1-32k"),
            "使用 Moonshot 中国站密钥；模型可按控制台中已开通的名称修改。"),
        AiProvider("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", listOf("glm-4-flash", "glm-4-plus"),
            "使用智谱开放平台 API Key，模型可按控制台修改。"),
        AiProvider("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", listOf("deepseek-ai/DeepSeek-V3.2"),
            "可填入密钥后获取已开放的模型。不同模型的可用性和费用以平台为准。"),
        AiProvider("doubao", "火山方舟 / 豆包", "https://ark.cn-beijing.volces.com/api/v3", emptyList(),
            "地址已填好。模型需填写控制台实际开通的模型 ID 或 ep- 开头的推理接入点 ID。"),
        AiProvider("custom", "自定义", "", emptyList(), "保留当前配置，可接入其他 OpenAI 兼容的 HTTPS 服务。"),
    )
    fun find(id: String) = all.firstOrNull { it.id == id } ?: all.last()
    fun infer(base: String): String = all.firstOrNull { it.id != "custom" &&
        runCatching { AiClient.endpoint(it.baseUrl) == AiClient.endpoint(base) }.getOrDefault(false) }?.id ?: "custom"
}
