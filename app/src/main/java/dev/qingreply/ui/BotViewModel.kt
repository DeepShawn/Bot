package dev.qingreply.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.qingreply.QingReplyApp
import dev.qingreply.ai.AiException
import dev.qingreply.ai.AiProvider
import dev.qingreply.data.BotSettings
import dev.qingreply.data.EventKind
import dev.qingreply.engine.SourceCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BotViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QingReplyApp
    val saved = app.settings.settings
    val events = app.runtime.events
    val stats = app.runtime.stats
    val connected = app.runtime.connected
    val accessibilityConnected = app.runtime.accessibilityConnected
    val sources = app.runtime.sources
    private var captureJob: Job? = null
    private var modelJob: Job? = null
    private var testJob: Job? = null
    var captureSecondsLeft by mutableStateOf(0)
        private set
    var fetchingModels by mutableStateOf(false)
        private set
    var availableModels by mutableStateOf<List<String>>(emptyList())
        private set
    var draft by mutableStateOf(saved.value)
        private set
    var notice by mutableStateOf(if (app.settings.keyReadFailed) "密钥无法解密，请重新填写 API Key" else "")
        private set
    var testing by mutableStateOf(false)
        private set
    var testResult by mutableStateOf("")
        private set

    fun edit(transform: (BotSettings) -> BotSettings) {
        val next = transform(draft)
        if (draft.baseUrl != next.baseUrl || draft.apiKey != next.apiKey || draft.providerId != next.providerId) {
            modelJob?.cancel(); testJob?.cancel()
            availableModels = emptyList(); fetchingModels = false; testing = false; testResult = ""
        }
        draft = next
    }

    fun selectProvider(provider: AiProvider) {
        edit { provider.applyTo(it) }
        notice = "${provider.name}模板已填入，请检查密钥与模型后保存"
    }

    fun fetchModels() {
        draft.copy(model = "model-list").apiError()?.let { notice = it; return }
        if (fetchingModels) return
        val config = draft
        fetchingModels = true
        modelJob = viewModelScope.launch {
            try {
                val models = app.ai.models(config)
                if (draft.baseUrl == config.baseUrl && draft.apiKey == config.apiKey) {
                    availableModels = models
                    notice = "已获取 ${models.size} 个模型，点选后保存"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: AiException) { notice = error.displayMessage }
            catch (_: Exception) { notice = "获取模型失败，可按控制台手动填写" }
            finally { fetchingModels = false }
        }
    }

    fun startCapture(hasPermission: Boolean) {
        if (!hasPermission || !connected.value) { notice = "请先授予通知使用权，并确认监听已连接"; return }
        if (runCatching { app.settings.save(saved.value.copy(enabled = false)) }.isFailure) {
            notice = "无法暂停自动回复，请重试"; return
        }
        draft = draft.copy(enabled = false)
        captureJob?.cancel(); app.runtime.startCapture()
        val captureEnd = app.runtime.captureUntil.value
        captureSecondsLeft = 60
        captureJob = viewModelScope.launch {
            try {
                repeat(60) { delay(1000); captureSecondsLeft -= 1 }
                if (sources.value.isEmpty()) notice = "未收到 QQ 通知。请检查小号消息通知；小米系统也可能未向轻答开放双开通知"
            } finally {
                if (app.runtime.captureUntil.value == captureEnd) { app.runtime.stopCapture(); captureSecondsLeft = 0 }
            }
        }
    }

    fun stopCapture() { captureJob?.cancel(); app.runtime.stopCapture(); captureSecondsLeft = 0 }

    fun bind(candidate: SourceCandidate) {
        if (candidate !in sources.value) { notice = "通知来源已变化，请重新检测"; return }
        if (!candidate.hasText) {
            notice = "通知没有可读取正文，请先开启 QQ 消息详情"; return
        }
        runCatching { app.settings.save(saved.value.copy(boundSource = candidate.source.id, enabled = false)) }
            .onSuccess {
                draft = draft.copy(boundSource = candidate.source.id, enabled = false)
                stopCapture()
                notice = if (candidate.canReply) "小号已绑定。请填写昵称、保存规则，再到总览开启回复"
                    else "小号已绑定，可读取消息。请开启无障碍发送、填写目标群名并授予权限"
                app.runtime.event(EventKind.INFO, "已绑定通知实例，自动回复保持暂停")
            }.onFailure { notice = "绑定保存失败，请重试" }
    }

    fun unbind() {
        runCatching { app.settings.save(saved.value.copy(boundSource = "", enabled = false)) }.onSuccess {
            draft = draft.copy(boundSource = "", enabled = false)
            stopCapture(); notice = "已解除绑定并暂停自动回复"
        }.onFailure { notice = "解除绑定失败，请重试" }
    }

    fun save() {
        if (draft.cooldownSeconds !in 10..300 || draft.hourlyLimit !in 1..120) {
            notice = "冷却应为 10～300 秒，每小时上限应为 1～120"
            return
        }
        val updated = draft.copy(enabled = saved.value.enabled && draft.startError() == null)
        runCatching { app.settings.save(updated) }.onSuccess {
            draft = saved.value
            notice = "配置已保存" + (updated.startError()?.let { " · $it" } ?: "")
        }.onFailure { notice = "保存失败，请检查设备密钥库或存储空间" }
    }

    fun toggle(enabled: Boolean, hasPermission: Boolean) {
        if (enabled && !hasPermission) { notice = "请先授予通知使用权"; return }
        if (enabled && saved.value.startError() != null) { notice = saved.value.startError()!!; return }
        if (enabled && saved.value.accessibilityFallback && !accessibilityConnected.value) {
            notice = "请先在系统无障碍设置开启“轻答 · QQ 自动发送”，并确认已连接"; return
        }
        runCatching { app.settings.save(saved.value.copy(enabled = enabled)) }.onSuccess {
            draft = draft.copy(enabled = enabled)
            notice = if (enabled) "自动回复已开启，只处理之后收到的新消息" else "自动回复已暂停"
            app.runtime.event(EventKind.INFO, if (enabled) "用户开启自动回复" else "用户暂停自动回复")
        }.onFailure { notice = "开关状态保存失败，请重试" }
    }

    fun dismissNotice() { notice = "" }
    fun message(text: String) { notice = text }
    fun clearEvents() { app.runtime.clearEvents() }
    fun clearContext() { app.conversations.clear(); notice = "群聊上下文已清空" }

    fun test() {
        if (testing) return
        draft.apiError()?.let { notice = it; return }
        val config = draft
        testing = true; testResult = ""
        testJob = viewModelScope.launch {
            try {
                val response = app.ai.reply(config, "请只回复：连接成功")
                testResult = "${dev.qingreply.ai.AiProviders.find(config.providerId).name} 连接成功 · $response"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: AiException) { testResult = error.displayMessage }
            catch (_: Exception) { testResult = "连接失败，请检查 API 配置" }
            finally { testing = false }
        }
    }

    override fun onCleared() { app.runtime.stopCapture(); super.onCleared() }
}
