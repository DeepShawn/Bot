package dev.qingreply.engine

data class ChatTurn(val role: String, val content: String)
private data class Remembered(val fingerprint: String, val turn: ChatTurn, val time: Long)

/** Notification-visible context only. Never persisted; each key already includes its app instance. */
class ConversationMemory {
    private val conversations = linkedMapOf<String, MutableList<Remembered>>()

    @Synchronized
    fun remember(conversation: String, fingerprint: String, text: String, time: Long,
        sender: String = "群成员", fromBot: Boolean = false) {
        expire(time)
        val history = conversations.getOrPut(conversation) { mutableListOf() }
        if (history.any { it.fingerprint == fingerprint }) return
        val turn = ChatTurn(if (fromBot) "assistant" else "user",
            if (fromBot) text.take(600) else "${sender.take(40)}：${text.take(600)}")
        history.add(Remembered(fingerprint, turn, time))
        while (history.size > 30) history.removeAt(0)
        while (conversations.size > 20) conversations.remove(conversations.minBy { it.value.lastOrNull()?.time ?: 0L }.key)
    }

    @Synchronized
    fun before(conversation: String, currentFingerprint: String, maxMessages: Int, now: Long): List<ChatTurn> {
        expire(now)
        val history = conversations[conversation].orEmpty().filter { it.fingerprint != currentFingerprint }.takeLast(maxMessages.coerceIn(1, 20))
        var characters = 0
        return history.asReversed().takeWhile { item ->
            characters += item.turn.content.length
            characters <= 5000
        }.asReversed().map { it.turn }
    }

    @Synchronized fun clear() = conversations.clear()

    private fun expire(now: Long) {
        conversations.values.forEach { it.removeAll { item -> now - item.time > 30 * 60_000L } }
        conversations.entries.removeAll { it.value.isEmpty() }
    }
}
