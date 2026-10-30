package dev.qingreply.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.qingreply.QingReplyApp
import dev.qingreply.data.EventKind
import dev.qingreply.engine.ChatUiPolicy
import dev.qingreply.engine.NotificationSource
import dev.qingreply.engine.UiElement
import dev.qingreply.notification.NotificationReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

class UiReplyException(val displayMessage: String) : Exception(displayMessage)

/** Uses only a bound notification's PendingIntent, view actions and exact chat-header matching. */
class QqAccessibilityService : AccessibilityService() {
    private val app get() = application as QingReplyApp
    private val sendLock = Mutex()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        app.runtime.accessibilityConnected.value = true
        app.runtime.event(EventKind.INFO, "QQ 无障碍发送服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        app.runtime.accessibilityConnected.value = false
        super.onDestroy()
    }

    private data class Entry(val node: AccessibilityNodeInfo, val clickNode: AccessibilityNodeInfo?, val model: UiElement)
    private class Tree(val entries: List<Entry>, val bounds: Rect, val packageName: String) : AutoCloseable {
        val models get() = entries.map { it.model }
        @Suppress("DEPRECATION")
        override fun close() { entries.forEach { it.node.recycle() } }
    }

    @Suppress("DEPRECATION")
    private fun readTree(): Tree? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != NotificationReader.QQ_PACKAGE) {
            root.recycle()
            return null
        }
        val bounds = Rect().also(root::getBoundsInScreen)
        val entries = mutableListOf<Entry>()
        fun visit(node: AccessibilityNodeInfo, scrolling: Boolean, ancestorClick: AccessibilityNodeInfo?, clickDepth: Int, depth: Int) {
            val rect = Rect().also(node::getBoundsInScreen)
            val click = if (node.isClickable && node.isEnabled) node else if (clickDepth < 2) ancestorClick else null
            val text = if (node.isPassword) "" else if (node.isEditable) node.text?.toString().orEmpty()
                else node.text?.toString()?.takeIf(String::isNotBlank) ?: node.contentDescription?.toString().orEmpty()
            entries += Entry(node, click, UiElement(text, node.className?.toString().orEmpty(), node.viewIdResourceName.orEmpty(),
                rect.top, rect.bottom, rect.left, rect.right, node.isVisibleToUser, node.isEnabled, node.isEditable,
                node.isPassword, scrolling, click != null, node.isShowingHintText))
            if (entries.size >= 450 || depth >= 28) return
            val container = scrolling || node.isScrollable || node.className?.toString()?.let {
                it.contains("RecyclerView") || it.contains("ListView") || it.contains("ScrollView") } == true
            for (index in 0 until node.childCount) {
                if (entries.size >= 450) break
                node.getChild(index)?.let { visit(it, container, click, if (node.isClickable) 0 else clickDepth + 1, depth + 1) }
            }
        }
        visit(root, false, null, 0, 0)
        return Tree(entries, bounds, root.packageName?.toString().orEmpty())
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun setText(node: AccessibilityNodeInfo, text: String) = node.performAction(
        AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })

    private suspend fun send(open: PendingIntent, source: NotificationSource, title: String, answer: String, allowed: () -> Boolean) {
        if (!sendLock.tryLock()) throw UiReplyException("正在操作另一个 QQ 会话，本次无障碍发送已跳过")
        var filled = false
        var clicked = false
        var reason = "未打开原 QQ 会话，请检查小米后台弹出界面权限"
        suspend fun checkAllowed() {
            currentCoroutineContext().ensureActive()
            if (!allowed()) throw CancellationException("Automatic reply disabled or changed")
            if (locked()) throw UiReplyException("手机已锁屏：无障碍发送需要保持解锁，不会自动解锁手机")
        }
        try {
            checkAllowed()
            if (title.isBlank()) throw UiReplyException("请填写要核对的 QQ 聊天页群名")
            if (open.creatorPackage != source.packageName || (source.uid != 0 && open.creatorUid != source.uid))
                throw UiReplyException("通知的会话入口不属于绑定的小号实例，已停止发送")
            val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
            open.send(this, 0, Intent(), null, null, null, options)
            withTimeout(15_000L) {
                while (true) {
                    checkAllowed()
                    var submitted = false
                    readTree()?.use { tree ->
                        if (tree.packageName != NotificationReader.QQ_PACKAGE) return@use
                        if (!ChatUiPolicy.headerMatches(tree.models, title, tree.bounds.top, tree.bounds.height())) {
                            reason = "聊天页群名未匹配：请填写 QQ 页顶部的完整群名；不会向其他会话发送"
                            return@use
                        }
                        val inputs = ChatUiPolicy.inputs(tree.models)
                        if (inputs.size != 1) { reason = "无法唯一识别 QQ 聊天输入框，已停止自动填写"; return@use }
                        val input = tree.entries[inputs.single()]
                        if (!filled) {
                            if (!ChatUiPolicy.draftIsEmpty(input.model)) throw UiReplyException("QQ 输入框已有草稿，不会覆盖或发送你的草稿")
                            if (!setText(input.node, answer)) throw UiReplyException("QQ 输入框不接受无障碍写入，此版本暂不兼容")
                            filled = true
                            reason = "QQ 未显示可用发送按钮，已停止发送"
                            return@use
                        }
                        if (clicked) {
                            if (ChatUiPolicy.draftIsEmpty(input.model)) submitted = true
                            else reason = "已点击发送但 QQ 未清空输入框，无法确认提交，不会重复点击"
                            return@use
                        }
                        if (input.model.text != answer) throw UiReplyException("输入内容已变化，已取消自动发送")
                        val buttons = ChatUiPolicy.sendButtons(tree.models, input.model, tree.bounds.height())
                            .mapNotNull { tree.entries[it].clickNode }.distinct()
                        val button = buttons.singleOrNull() ?: return@use
                        if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw UiReplyException("QQ 发送按钮拒绝无障碍点击")
                        clicked = true
                    }
                    if (submitted) {
                        checkAllowed()
                        // Returning home allows QQ to post subsequent incoming notifications again.
                        readTree()?.use { tree ->
                            if (tree.packageName == NotificationReader.QQ_PACKAGE &&
                                ChatUiPolicy.headerMatches(tree.models, title, tree.bounds.top, tree.bounds.height()))
                                performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                        return@withTimeout
                    }
                    delay(250)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw UiReplyException(reason)
        } finally {
            if (filled && !clicked) runCatching {
                readTree()?.use { tree ->
                    if (tree.packageName == NotificationReader.QQ_PACKAGE &&
                        ChatUiPolicy.headerMatches(tree.models, title, tree.bounds.top, tree.bounds.height())) {
                        val inputs = ChatUiPolicy.inputs(tree.models)
                        inputs.singleOrNull()?.let { index ->
                            if (tree.entries[index].model.text == answer) setText(tree.entries[index].node, "")
                        }
                    }
                }
            }
            sendLock.unlock()
        }
    }

    companion object {
        @Volatile private var instance: QqAccessibilityService? = null
        suspend fun sendReply(open: PendingIntent, source: NotificationSource, title: String, answer: String, allowed: () -> Boolean) {
            val service = instance ?: throw UiReplyException("请先在系统无障碍设置中开启“轻答 · QQ 自动发送”")
            service.send(open, source, title, answer, allowed)
        }
    }
}
