package dev.qingreply.engine

import org.junit.Assert.*
import org.junit.Test

class ReplyActionPolicyTest {
    @Test fun unlabeledSingleFreeTextActionIsUsable() {
        assertEquals(0, ReplyActionPolicy.choose(listOf(ReplyCapability(true, false, false, ""))))
    }
    @Test fun explicitReplyTakesPrecedenceOverUnrelatedTextActions() {
        assertEquals(1, ReplyActionPolicy.choose(listOf(ReplyCapability(true, false, false, "备注"), ReplyCapability(true, false, true, ""))))
    }
    @Test fun authenticatedAndDataOnlyActionsCannotBeUsed() {
        assertNull(ReplyActionPolicy.choose(listOf(ReplyCapability(true, true, true, "回复"), ReplyCapability(false, false, true, "回复"))))
    }
    @Test fun ambiguousUnlabeledActionsAreNotGuessed() {
        assertNull(ReplyActionPolicy.choose(listOf(ReplyCapability(true, false, false, ""), ReplyCapability(true, false, false, "未知"))))
        assertNull(ReplyActionPolicy.choose(emptyList()))
    }
    @Test fun chineseAndEnglishReplyLabelsWorkWithoutSemanticMetadata() {
        assertEquals(1, ReplyActionPolicy.choose(listOf(ReplyCapability(true, false, false, "未知"), ReplyCapability(true, false, false, "快速回复"))))
        assertEquals(0, ReplyActionPolicy.choose(listOf(ReplyCapability(true, false, false, "Reply"))))
    }
}
