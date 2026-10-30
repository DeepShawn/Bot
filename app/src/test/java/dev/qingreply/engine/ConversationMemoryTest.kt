package dev.qingreply.engine

import org.junit.Assert.*
import org.junit.Test

class ConversationMemoryTest {
    @Test fun contextsNeverMixAcrossGroupsOrQQInstances() {
        val memory = ConversationMemory()
        memory.remember("main:group-a", "1", "大号空间", 1000, "甲")
        memory.remember("clone:group-a", "2", "小号空间", 1001, "乙")
        memory.remember("clone:group-b", "3", "另一个群", 1002, "丙")
        assertEquals(listOf(ChatTurn("user", "乙：小号空间")), memory.before("clone:group-a", "current", 10, 2000))
    }

    @Test fun duplicateNotificationsAndCurrentQuestionAreNotRepeatedInHistory() {
        val memory = ConversationMemory()
        memory.remember("group", "1", "前一个话题", 1000, "甲")
        memory.remember("group", "1", "前一个话题", 1001, "甲")
        memory.remember("group", "2", "@小号 现在的问题", 1002, "乙")
        val history = memory.before("group", "2", 10, 2000)
        assertEquals(1, history.size)
        assertEquals("甲：前一个话题", history.single().content)
    }

    @Test fun keepsRecentTurnsInOrderAndMarksActualBotReplies() {
        val memory = ConversationMemory()
        repeat(15) { memory.remember("group", "$it", "消息$it", 1000L + it) }
        memory.remember("group", "bot", "AI 回复", 2000, fromBot = true)
        val history = memory.before("group", "current", 5, 3000)
        assertEquals(5, history.size)
        assertTrue(history.first().content.endsWith("消息11"))
        assertEquals(ChatTurn("assistant", "AI 回复"), history.last())
    }

    @Test fun expiresOldMessagesAndCapsTotalPromptCharacters() {
        val memory = ConversationMemory()
        repeat(30) { memory.remember("group", "$it", "长".repeat(1000), 1000L + it) }
        val history = memory.before("group", "current", 20, 2000)
        assertTrue(history.sumOf { it.content.length } <= 5000)
        assertTrue(history.size <= 20)
        assertEquals(emptyList<ChatTurn>(), memory.before("group", "current", 20, 1_802_000))
    }

    @Test fun clearRemovesEveryConversation() {
        val memory = ConversationMemory()
        memory.remember("group", "1", "消息", 1000)
        memory.clear()
        assertTrue(memory.before("group", "current", 10, 2000).isEmpty())
    }
}
