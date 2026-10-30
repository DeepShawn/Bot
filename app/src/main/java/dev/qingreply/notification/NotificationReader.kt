package dev.qingreply.notification

import android.app.Notification
import android.app.PendingIntent
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import dev.qingreply.engine.NotificationSource
import dev.qingreply.engine.ReplyActionPolicy
import dev.qingreply.engine.ReplyCapability
import java.security.MessageDigest

data class IncomingMessage(
    val notificationKey: String,
    val conversation: String,
    val conversationId: String,
    val text: String,
    val timestamp: Long,
    val fingerprint: String,
    val ownMessage: Boolean,
    val source: NotificationSource,
    val sender: String,
)

data class QuickReplyAction(val actionIntent: PendingIntent, val remoteInputs: Array<RemoteInput>)

object NotificationReader {
    const val QQ_PACKAGE = "com.tencent.mobileqq"

    @Suppress("DEPRECATION")
    fun source(sbn: StatusBarNotification) = NotificationSource(sbn.packageName, sbn.userId,
        if (Build.VERSION.SDK_INT >= 29) sbn.uid else 0)

    fun read(sbn: StatusBarNotification): IncomingMessage? {
        if (sbn.packageName != QQ_PACKAGE) return null
        val notification = sbn.notification
        val source = source(sbn)
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = notification.extras ?: return null
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val latest = style?.messages?.maxByOrNull { it.timestamp }
        // Do not scan history, summary or bigText: an older mention can remain there.
        val text = (if (latest != null) latest.text else extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val title = style?.conversationTitle?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val conversationId = source.scoped(notification.shortcutId?.takeIf { it.isNotBlank() }
            ?.let { "shortcut:$it" } ?: "notification:${sbn.key}:$title")
        val own = latest != null &&
            (latest.person == null || samePerson(latest.person, style.user))
        val timestamp = latest?.timestamp ?: sbn.postTime
        // With legacy notifications no stable message id exists. Suppress identical text for
        // 24 hours rather than risk repeated sends after the notification gets updated.
        val identityTime = latest?.timestamp ?: 0L
        val fingerprint = digest("$conversationId\u0000$identityTime\u0000$text")
        return IncomingMessage(sbn.key, title, digest(conversationId), text, timestamp, fingerprint, own, source,
            latest?.person?.name?.toString() ?: "群成员")
    }

    private fun compatibleActions(notification: Notification): List<NotificationCompat.Action> {
        val normal = notification.actions.orEmpty().indices.mapNotNull { NotificationCompat.getAction(notification, it) }
        val wearable = runCatching { NotificationCompat.WearableExtender(notification).actions }.getOrDefault(emptyList())
        val invisible = runCatching { NotificationCompat.getInvisibleActions(notification) }.getOrDefault(emptyList())
        val car = runCatching {
            @Suppress("DEPRECATION")
            val conversation = NotificationCompat.CarExtender(notification).unreadConversation
            val reply = conversation?.replyPendingIntent
            val input = conversation?.remoteInput
            if (reply == null || input == null) null else NotificationCompat.Action.Builder(0, "回复", reply)
                .addRemoteInput(input).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build()
        }.getOrNull()
        return (normal + wearable + invisible + listOfNotNull(car))
            .filter { it.actionIntent?.creatorPackage == QQ_PACKAGE }
            .distinctBy { it.actionIntent to it.remoteInputs.orEmpty().map { input -> input.resultKey } }
    }

    fun replyAction(notification: Notification): QuickReplyAction? {
        val actions = compatibleActions(notification)
        val index = ReplyActionPolicy.choose(actions.map { action -> ReplyCapability(
            action.remoteInputs?.any { it.allowFreeFormInput } == true, action.isAuthenticationRequired,
            action.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_REPLY, action.title?.toString().orEmpty()) }) ?: return null
        val action = actions[index]
        return QuickReplyAction(action.actionIntent ?: return null, action.remoteInputs.orEmpty().filter { it.allowFreeFormInput }.toTypedArray())
    }

    fun replyDiagnostic(notification: Notification): String {
        val actions = compatibleActions(notification)
        val free = actions.count { it.remoteInputs?.any { input -> input.allowFreeFormInput } == true }
        return "通知操作 ${actions.size} 个 · 自由文本操作 $free 个 · 原会话入口${if (notification.contentIntent != null) "可用" else "缺失"}"
    }

    private fun samePerson(a: Person?, b: Person?): Boolean {
        if (a == null || b == null) return false
        if (!a.key.isNullOrEmpty() && !b.key.isNullOrEmpty()) return a.key == b.key
        if (!a.uri.isNullOrEmpty() && !b.uri.isNullOrEmpty()) return a.uri == b.uri
        return !a.name.isNullOrEmpty() && a.name.toString() == b.name.toString()
    }

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
