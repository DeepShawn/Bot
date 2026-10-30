package dev.qingreply.ai

import dev.qingreply.data.BotSettings
import dev.qingreply.engine.ChatTurn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AiException(val displayMessage: String) : IOException(displayMessage)

class AiClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(55, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {

    suspend fun reply(settings: BotSettings, message: String, context: List<ChatTurn> = emptyList()): String {
        val url = endpoint(settings.baseUrl)
        val payload = JSONObject().apply {
            put("model", settings.model.trim())
            put("stream", false)
            put("max_tokens", 400)
            if (settings.providerId == "deepseek" && url.host == "api.deepseek.com")
                put("thinking", JSONObject().put("type", "disabled"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", settings.systemPrompt.ifBlank { BotSettings().systemPrompt }))
                context.takeLast(20).forEach { turn ->
                    put(JSONObject().put("role", if (turn.role == "assistant") "assistant" else "user").put("content", turn.content.take(600)))
                }
                put(JSONObject().put("role", "user").put("content", message.take(6000)))
            })
        }
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${settings.apiKey.trim()}")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val raw = client.newCall(request).awaitText()
        val content = try {
            JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim()
        } catch (_: Exception) { throw AiException("响应格式不兼容：需要 choices[0].message.content") }
        if (content.isBlank() || content == "null") throw AiException("模型返回了空内容，请检查模型或提示词")
        // Avoid creating another @ trigger and keep the quick reply within a sensible length.
        val safe = content.replace('@', '﹫').replace('＠', '﹫').filter { !it.isISOControl() || it == '\n' || it == '\t' }
        return if (safe.length > 500) safe.take(499) + "…" else safe
    }

    suspend fun models(settings: BotSettings): List<String> {
        val url = endpoint(settings.baseUrl)
        val path = url.encodedPath.removeSuffix("/chat/completions") + "/models"
        val request = Request.Builder().url(url.newBuilder().encodedPath(path).build())
            .header("Authorization", "Bearer ${settings.apiKey.trim()}").get().build()
        val raw = client.newCall(request).awaitText()
        return try {
            val items = JSONObject(raw).getJSONArray("data")
            (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
                .distinct().sorted().take(60).also {
                    if (it.isEmpty()) throw AiException("服务商没有返回模型列表，请按控制台手动填写")
                }
        } catch (error: AiException) { throw error }
        catch (_: Exception) { throw AiException("此接口不提供兼容的模型列表，请按控制台手动填写") }
    }

    companion object {
        fun endpoint(base: String): HttpUrl {
            val parsed = base.trim().toHttpUrl()
            require(parsed.isHttps && parsed.host.isNotBlank() && parsed.username.isEmpty() && parsed.password.isEmpty()
                && parsed.query == null && parsed.fragment == null)
            val path = parsed.encodedPath.trimEnd('/')
            return if (path.endsWith("/chat/completions")) parsed.newBuilder().encodedPath(path).build()
                else parsed.newBuilder().encodedPath("$path/chat/completions").build()
        }
    }
}

private suspend fun Call.awaitText(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(AiException("网络请求失败或超时，请检查网络和 API 地址"))
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    if (!it.isSuccessful) throw AiException(when (it.code) {
                        401, 403 -> "API 鉴权失败（${it.code}），请检查密钥与权限"
                        404 -> "接口或模型不存在（404），请检查地址与模型"
                        429 -> "API 额度不足或请求过于频繁（429）"
                        in 300..399 -> "接口发生重定向，请填写最终的 HTTPS API 地址"
                        else -> "AI 服务返回 HTTP ${it.code}"
                    })
                    val body = it.body ?: throw AiException("API 返回了空响应")
                    val source = body.source()
                    source.request(256 * 1024L + 1)
                    if (source.buffer.size > 256 * 1024L) throw AiException("API 响应过大，已停止处理")
                    val value = source.readUtf8()
                    if (continuation.isActive) continuation.resume(value)
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(
                    if (error is AiException) error else AiException("读取 API 响应失败"))
            }
        }
    })
}
