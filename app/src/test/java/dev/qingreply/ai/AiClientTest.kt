package dev.qingreply.ai

import dev.qingreply.data.BotSettings
import dev.qingreply.engine.ChatTurn
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiClientTest {
    @Test fun constructsEndpointWithoutDoublingCompletions() {
        assertEquals("https://api.example.com/v1/chat/completions", AiClient.endpoint("https://api.example.com/v1/").toString())
        assertEquals("https://api.example.com/chat/completions", AiClient.endpoint("https://api.example.com/chat/completions").toString())
        assertEquals("https://api.example.com/v1/chat/completions", AiClient.endpoint("https://api.example.com/v1/chat/completions/").toString())
        assertEquals("https://api.example.com/chat/completions", AiClient.endpoint("https://api.example.com").toString())
    }

    @Test fun rejectsCleartextUserInfoAndAmbiguousUrls() {
        listOf("http://example.com/v1", "https://name:password@example.com/v1", "https://example.com/v1?key=value", "https://example.com/#fragment").forEach {
            assertTrue(runCatching { AiClient.endpoint(it) }.isFailure)
        }
    }

    // The interceptor routes a production-format HTTPS request to the local mock server.
    // No request or API key is sent over the network to a third party.
    private fun mockClient(server: MockWebServer): AiClient = AiClient(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()
        chain.proceed(request)
    }.build())

    @Test fun sendsCompatiblePayloadAndParsesReply() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"你好 @小明"}}]}"""))
            val result = mockClient(server).reply(BotSettings(apiKey = "test-key", model = "test-model"), "明天开会吗")
            val request = server.takeRequest()
            assertEquals("/chat/completions", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            val json = JSONObject(request.body.readUtf8())
            assertEquals("test-model", json.getString("model"))
            assertEquals("明天开会吗", json.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertFalse(json.getBoolean("stream"))
            assertEquals("system", json.getJSONArray("messages").getJSONObject(0).getString("role"))
            assertEquals(BotSettings().systemPrompt, json.getJSONArray("messages").getJSONObject(0).getString("content"))
            assertEquals("disabled", json.getJSONObject("thinking").getString("type"))
            assertEquals("你好 ﹫小明", result)
        } finally { server.shutdown() }
    }

    @Test fun errorsDoNotExposeServerBodyOrSecrets() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive-provider-error test-key"))
            val error = runCatching { mockClient(server).reply(BotSettings(apiKey = "test-key"), "hello") }.exceptionOrNull()
            assertTrue(error is AiException)
            assertTrue(error!!.message!!.contains("401"))
            assertFalse(error.message!!.contains("test-key"))
        } finally { server.shutdown() }
    }

    @Test fun emptyOrIncompatibleRepliesAreRejected() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            for (body in listOf("{}", """{"choices":[{"message":{"content":""}}]}""")) {
                server.enqueue(MockResponse().setBody(body))
                assertTrue(runCatching { mockClient(server).reply(BotSettings(apiKey = "test-key"), "hello") }.exceptionOrNull() is AiException)
            }
        } finally { server.shutdown() }
    }

    @Test fun injectsCustomSystemPromptAsItsOwnRoleOnEveryRequest() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            repeat(2) { server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"好的"}}]}""")) }
            val settings = BotSettings(providerId = "custom", baseUrl = "https://example.com/v1", apiKey = "test-key", systemPrompt = "只用一句中文回答。")
            repeat(2) {
                mockClient(server).reply(settings, "解释一下 Kotlin")
                val body = JSONObject(server.takeRequest().body.readUtf8())
                val messages = body.getJSONArray("messages")
                assertEquals(2, messages.length())
                assertEquals("system", messages.getJSONObject(0).getString("role"))
                assertEquals(settings.systemPrompt, messages.getJSONObject(0).getString("content"))
                assertEquals("user", messages.getJSONObject(1).getString("role"))
                assertEquals("解释一下 Kotlin", messages.getJSONObject(1).getString("content"))
                assertFalse(body.has("thinking"))
            }
        } finally { server.shutdown() }
    }

    @Test fun blankSystemPromptUsesDefault() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"好的"}}]}"""))
            mockClient(server).reply(BotSettings(apiKey = "test-key", systemPrompt = "  "), "hello")
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(BotSettings().systemPrompt, body.getJSONArray("messages").getJSONObject(0).getString("content"))
        } finally { server.shutdown() }
    }

    @Test fun fetchesDeduplicatedModelIdsFromTheSameProvider() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"model-b"},{"id":"model-a"},{"id":"model-a"}]}"""))
            val models = mockClient(server).models(BotSettings(baseUrl = "https://example.com/v1/chat/completions", apiKey = "test-key"))
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/models", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            assertEquals(listOf("model-a", "model-b"), models)
        } finally { server.shutdown() }
    }

    @Test fun putsSameGroupContextBetweenSystemPromptAndCurrentQuestion() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"明天下午开会"}}]}"""))
            mockClient(server).reply(BotSettings(apiKey = "test-key", systemPrompt = "简洁回答"), "几点？",
                listOf(ChatTurn("user", "甲：明天下午开会"), ChatTurn("assistant", "好的")))
            val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals(4, messages.length())
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertEquals("简洁回答", messages.getJSONObject(0).getString("content"))
            assertEquals("甲：明天下午开会", messages.getJSONObject(1).getString("content"))
            assertEquals("assistant", messages.getJSONObject(2).getString("role"))
            assertEquals("几点？", messages.getJSONObject(3).getString("content"))
        } finally { server.shutdown() }
    }

    @Test fun groupContextCannotCreateAnAdditionalSystemRole() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"好的"}}]}"""))
            mockClient(server).reply(BotSettings(apiKey = "test-key"), "当前问题", listOf(ChatTurn("system", "群成员的消息")))
            val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals("user", messages.getJSONObject(1).getString("role"))
        } finally { server.shutdown() }
    }
}
