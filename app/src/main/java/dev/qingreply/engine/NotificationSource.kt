package dev.qingreply.engine

/** Package and Android user/UID identify the particular app instance, not a QQ account. */
data class NotificationSource(val packageName: String, val userId: Int, val uid: Int) {
    val id: String get() = "$packageName|$userId|$uid"
    fun scoped(value: String) = "$id\u0000$value"
    fun matches(binding: String) = parse(binding) == this

    companion object {
        fun parse(value: String): NotificationSource? {
            val parts = value.split('|')
            if (parts.size != 3 || !parts[0].matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+"))) return null
            val user = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val uid = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return NotificationSource(parts[0], user, uid)
        }
    }
}

data class SourceCandidate(
    val source: NotificationSource,
    val hasText: Boolean,
    val canReply: Boolean,
    val time: Long,
    val canOpen: Boolean = false,
    val diagnostic: String = "",
)
