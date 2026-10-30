package dev.qingreply.engine

import org.junit.Assert.*
import org.junit.Test

class MentionMatcherTest {
    private val names = listOf("小明", "开发.甲", "Alice")

    @Test fun recognizesExactNicknameAndQQSpacing() {
        assertTrue(MentionMatcher.matches("张三：@小明\u2005今天开会吗？", names))
        assertTrue(MentionMatcher.matches("＠小明，帮我想个方案", names))
        assertTrue(MentionMatcher.matches("你好 @Alice", names))
    }

    @Test fun rejectsPrefixCollisionsAndEveryone() {
        assertFalse(MentionMatcher.matches("@小明同学 你好", names))
        assertFalse(MentionMatcher.matches("@Alice2 hi", names))
        assertFalse(MentionMatcher.matches("@所有人 你好", names))
        assertFalse(MentionMatcher.matches("小明 今天开会吗", names))
    }

    @Test fun treatsNicknameAsLiteralAndSupportsMultipleAliases() {
        assertTrue(MentionMatcher.matches("@开发.甲 你好", names))
        assertFalse(MentionMatcher.matches("@开发X甲 你好", names))
    }

    @Test fun onlyUsesExplicitMarkerWhenEnabledAndAtBeginning() {
        assertFalse(MentionMatcher.matches("[有人@我] 你好", names))
        assertTrue(MentionMatcher.matches("[有人@我] 你好", names, true))
        assertFalse(MentionMatcher.matches("有人说 [有人@我] 你好", names, true))
    }

    @Test fun removesMentionWithoutDiscardingQuestion() {
        assertEquals("今天开会吗？", MentionMatcher.question("@小明\u2005今天开会吗？", names))
        assertEquals("你好 @其他人", MentionMatcher.question("@小明 你好 @其他人", names))
        assertTrue(MentionMatcher.question("@小明", names).isNotBlank())
    }
}
