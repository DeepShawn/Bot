package dev.qingreply.notification

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import dev.qingreply.engine.NotificationSource
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

    fun replyAction(notification: Notification): Notification.Action? = notification.actions
        ?.filter { action -> action.actionIntent != null && action.remoteInputs?.any { it.allowFreeFormInput } == true }
        ?.firstOrNull { action ->
            val semanticReply = Build.VERSION.SDK_INT >= 28 && action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
            semanticReply || action.title?.toString()?.let { it.contains("回复") || it.contains("reply", ignoreCase = true) } == true
        }?.takeUnless { Build.VERSION.SDK_INT >= 31 && it.isAuthenticationRequired }

    private fun samePerson(a: Person?, b: Person?): Boolean {
        if (a == null || b == null) return false
        if (!a.key.isNullOrEmpty() && !b.key.isNullOrEmpty()) return a.key == b.key
        if (!a.uri.isNullOrEmpty() && !b.uri.isNullOrEmpty()) return a.uri == b.uri
        return !a.name.isNullOrEmpty() && a.name.toString() == b.name.toString()
    }

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
