package dev.qingreply.ai

import dev.qingreply.data.BotSettings
import org.junit.Assert.*
import org.junit.Test

class AiProvidersTest {
    @Test fun deepSeekTemplateFillsDocumentedEndpointAndModel() {
        val result = AiProviders.find("deepseek").applyTo(BotSettings(providerId = "custom", baseUrl = "https://example.com/v1", apiKey = "old-secret"))
        assertEquals("https://api.deepseek.com/chat/completions", AiClient.endpoint(result.baseUrl).toString())
        assertEquals("deepseek-flash", result.model)
        assertEquals("", result.apiKey)
    }

    @Test fun switchingProvidersNeverReusesTheOtherProvidersKey() {
        for (provider in AiProviders.all.filter { it.id != "custom" && it.id != "deepseek" }) {
            val result = provider.applyTo(BotSettings(apiKey = "deepseek-secret", systemPrompt = "my prompt", aliases = "小号", boundSource = "binding"))
            assertEquals("", result.apiKey)
            assertEquals(provider.baseUrl, result.baseUrl)
            assertEquals("my prompt", result.systemPrompt)
            assertEquals("小号", result.aliases)
            assertEquals("binding", result.boundSource)
        }
    }

    @Test fun sameProviderWithSameDestinationKeepsItsKey() {
        assertEquals("own-secret", AiProviders.find("deepseek").applyTo(BotSettings(apiKey = "own-secret")).apiKey)
        assertEquals("", AiProviders.find("deepseek").applyTo(BotSettings(baseUrl = "https://example.com", apiKey = "wrong-host-secret")).apiKey)
    }

    @Test fun customTemplatePreservesManualConfigurationAndEveryPresetUsesHttps() {
        val settings = BotSettings(baseUrl = "https://example.com/custom", model = "my-model", apiKey = "my-key")
        val custom = AiProviders.find("custom").applyTo(settings)
        assertEquals(settings.copy(providerId = "custom"), custom)
        AiProviders.all.filter { it.id != "custom" }.forEach { assertTrue(AiClient.endpoint(it.baseUrl).isHttps) }
    }
}
