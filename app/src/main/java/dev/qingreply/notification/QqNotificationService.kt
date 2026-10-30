package dev.qingreply.notification

import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.qingreply.QingReplyApp
import dev.qingreply.ai.AiException
import dev.qingreply.data.BotSettings
import dev.qingreply.data.EventKind
import dev.qingreply.engine.GateResult
import dev.qingreply.engine.GateSnapshot
import dev.qingreply.engine.MentionMatcher
import dev.qingreply.engine.ReplyGate
import dev.qingreply.engine.SourceCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class QqNotificationService : NotificationListenerService() {
    private val app get() = application as QingReplyApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private data class Pending(val fingerprint: String, val conversationId: String, val job: Job)
    private val pending = mutableMapOf<String, Pending>()
    private lateinit var gate: ReplyGate
    private var readySince = System.currentTimeMillis()
    private var lastConfig: BotSettings? = null

    override fun onCreate() {
        super.onCreate()
        gate = ReplyGate(readGate())
        scope.launch {
            app.settings.settings.collect { settings ->
                if (lastConfig != settings) {
                    app.conversations.clear()
                    pending.values.forEach { it.job.cancel() }
                    pending.clear()
                    readySince = System.currentTimeMillis()
                    lastConfig = settings
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        readySince = System.currentTimeMillis()
        app.runtime.connected.value = true
        app.runtime.event(EventKind.INFO, "通知监听已连接，等待新的 QQ 消息")
        // Never replay existing notifications on connection.
    }

    override fun onListenerDisconnected() {
        app.conversations.clear()
        app.runtime.connected.value = false
        pending.values.forEach { it.job.cancel() }; pending.clear()
        app.runtime.event(EventKind.ERROR, "通知监听已断开，可在总览中重新连接")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != NotificationReader.QQ_PACKAGE) return
        scope.launch {
            try {
                val source = NotificationReader.source(sbn)
                if (System.currentTimeMillis() < app.runtime.captureUntil.value &&
                    sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0) {
                    app.runtime.observe(SourceCandidate(source, NotificationReader.read(sbn) != null,
                        NotificationReader.replyAction(sbn.notification) != null, System.currentTimeMillis()))
                }
                if (source.matches(app.settings.settings.value.boundSource)) process(sbn)
            }
            catch (_: CancellationException) { /* A changed notification invalidates pending work. */ }
            catch (_: Exception) { app.runtime.event(EventKind.ERROR, "QQ 通知格式无法解析，已跳过") }
        }
    }

    private fun process(sbn: StatusBarNotification) {
        val message = NotificationReader.read(sbn) ?: run {
            pending.remove(sbn.key)?.job?.cancel()
            return
        }
        val previous = pending[sbn.key]
        if (previous != null) {
            if (previous.fingerprint == message.fingerprint) return
            if (previous.conversationId != message.conversationId) {
                previous.job.cancel(); pending.remove(sbn.key)
            }
        }
        val config = app.settings.settings.value
        if (!message.source.matches(config.boundSource)) return
        if (!config.enabled || !app.runtime.connected.value) return
        if (config.startError() != null) return
        val now = System.currentTimeMillis()
        if (message.timestamp < readySince - 1000L || now - message.timestamp > 90_000L
            || message.timestamp > now + 5000L) return
        if (config.conversationList().isNotEmpty() && message.conversation !in config.conversationList()) return
        if (message.ownMessage) return
        if (config.contextEnabled) app.conversations.remember(message.conversationId, message.fingerprint,
            message.text, now, message.sender)
        if (previous != null && previous.conversationId == message.conversationId) return
        app.runtime.count(received = 1)
        if (!MentionMatcher.matches(message.text, config.aliasList(), config.allowMentionMarker)) return
        app.runtime.count(matched = 1)
        if (NotificationReader.replyAction(sbn.notification) == null) {
            app.runtime.event(EventKind.SKIPPED, "收到 @ 消息，但此 QQ 通知未提供可用的快捷回复入口"); return
        }
        if (pending.size >= 3) {
            app.runtime.event(EventKind.SKIPPED, "正在处理其他消息，已跳过以避免积压"); return
        }
        val result = gate.reserve(message.fingerprint, message.conversationId, now, config.cooldownSeconds, config.hourlyLimit)
        persistGate()
        if (result != GateResult.ALLOWED) {
            if (result != GateResult.DUPLICATE) app.runtime.event(EventKind.SKIPPED,
                if (result == GateResult.COOLDOWN) "同一会话仍在冷却中，已跳过" else "已达到每小时 AI 请求上限")
            return
        }
        app.runtime.event(EventKind.INFO, "已识别 @ 消息，正在请求 AI")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val context = if (config.contextEnabled) app.conversations.before(message.conversationId,
                    message.fingerprint, config.contextMessages, System.currentTimeMillis()) else emptyList()
                val answer = app.ai.reply(config, MentionMatcher.question(message.text, config.aliasList()), context)
                // Settings changes, dismissal and newer messages invalidate the original action.
                if (!app.runtime.connected.value || app.settings.settings.value != config) return@launch
                val current = activeNotifications?.firstOrNull { it.key == sbn.key }
                val currentMessage = current?.let(NotificationReader::read)
                if (current == null || currentMessage?.conversationId != message.conversationId ||
                    !currentMessage.source.matches(config.boundSource)) {
                    app.runtime.event(EventKind.SKIPPED, "通知已消失或消息已更新，本次回复已取消"); return@launch
                }
                val action = NotificationReader.replyAction(current.notification) ?: run {
                    app.runtime.event(EventKind.SKIPPED, "通知快捷回复入口已失效"); return@launch
                }
                val inputs = action.remoteInputs.filter { it.allowFreeFormInput }.toTypedArray()
                val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, answer) } }
                val intent = Intent()
                RemoteInput.addResultsToIntent(inputs, intent, results)
                if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
                action.actionIntent.send(this@QqNotificationService, 0, intent)
                if (config.contextEnabled) app.conversations.remember(message.conversationId, "reply:${message.fingerprint}",
                    answer, System.currentTimeMillis(), fromBot = true)
                app.runtime.count(submitted = 1)
                app.runtime.event(EventKind.SUCCESS, "AI 回复已提交给 QQ；实际送达请在 QQ 中确认")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: PendingIntent.CanceledException) {
                app.runtime.event(EventKind.ERROR, "QQ 回复入口已过期，请等待下一条新消息")
            } catch (error: AiException) {
                app.runtime.event(EventKind.ERROR, error.displayMessage)
            } catch (_: Exception) {
                app.runtime.event(EventKind.ERROR, "回复失败，请检查通知权限与 QQ 快捷回复功能")
            } finally {
                if (pending[sbn.key]?.fingerprint == message.fingerprint) pending.remove(sbn.key)
            }
        }
        pending[sbn.key] = Pending(message.fingerprint, message.conversationId, job)
        job.start()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        scope.launch { pending.remove(sbn.key)?.job?.cancel() }
    }

    private fun readGate(): GateSnapshot = runCatching {
        val json = JSONObject(getSharedPreferences("reply_gate", MODE_PRIVATE).getString("state", "{}")!!)
        fun map(name: String): Map<String, Long> {
            val obj = json.optJSONObject(name) ?: return emptyMap()
            return obj.keys().asSequence().associateWith { obj.getLong(it) }
        }
        val requests = json.optJSONArray("requests") ?: JSONArray()
        GateSnapshot(map("seen"), map("conversations"), (0 until requests.length()).map { requests.getLong(it) })
    }.getOrDefault(GateSnapshot(emptyMap(), emptyMap(), emptyList()))

    private fun persistGate() {
        val snapshot = gate.snapshot()
        val json = JSONObject().put("seen", JSONObject(snapshot.seen)).put("conversations", JSONObject(snapshot.conversations))
            .put("requests", JSONArray(snapshot.requests))
        // Commit before requesting AI so a process restart cannot replay the same trigger.
        check(getSharedPreferences("reply_gate", MODE_PRIVATE).edit().putString("state", json.toString()).commit())
    }

    override fun onDestroy() {
        app.conversations.clear()
        app.runtime.connected.value = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun reconnect(context: android.content.Context) = requestRebind(ComponentName(context, QqNotificationService::class.java))
    }
}
