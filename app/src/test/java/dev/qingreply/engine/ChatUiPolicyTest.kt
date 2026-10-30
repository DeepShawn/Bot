package dev.qingreply.engine

import org.junit.Assert.*
import org.junit.Test

class ChatUiPolicyTest {
    private val header = UiElement("测试群", "android.widget.TextView", "qq:id/title", 40, 90, 40, 300)
    private val input = UiElement("", "android.widget.EditText", "qq:id/input", 800, 860, 20, 350, editable = true)
    private val send = UiElement("发送", "android.widget.Button", "qq:id/send", 800, 860, 360, 440, clickable = true)

    @Test fun exactChatHeaderIsRequired() {
        assertTrue(ChatUiPolicy.headerMatches(listOf(header), "测试群", 0, 1000))
        assertFalse(ChatUiPolicy.headerMatches(listOf(header), "测试", 0, 1000))
        assertFalse(ChatUiPolicy.headerMatches(listOf(header), "另一个群", 0, 1000))
    }
    @Test fun aChatMessageContainingTheGroupNameIsNotAHeader() {
        assertFalse(ChatUiPolicy.headerMatches(listOf(header.copy(underScrollingContainer = true)), "测试群", 0, 1000))
        assertFalse(ChatUiPolicy.headerMatches(listOf(header.copy(top = 500, bottom = 550)), "测试群", 0, 1000))
    }
    @Test fun invisibleOrEditableHeaderCannotAuthorizeSending() {
        assertFalse(ChatUiPolicy.headerMatches(listOf(header.copy(visible = false)), "测试群", 0, 1000))
        assertFalse(ChatUiPolicy.headerMatches(listOf(header.copy(editable = true)), "测试群", 0, 1000))
        assertFalse(ChatUiPolicy.headerMatches(listOf(header), "", 0, 1000))
    }
    @Test fun existingDraftIsProtectedAndHintIsNotTreatedAsDraft() {
        assertTrue(ChatUiPolicy.draftIsEmpty(input))
        assertFalse(ChatUiPolicy.draftIsEmpty(input.copy(text = "用户未发送的草稿")))
        assertTrue(ChatUiPolicy.draftIsEmpty(input.copy(text = "输入消息", showingHint = true)))
    }
    @Test fun passwordSearchAndDisabledFieldsAreNotChatInputs() {
        assertEquals(listOf(0), ChatUiPolicy.inputs(listOf(input, input.copy(password = true), input.copy(resourceId = "qq:id/search"), input.copy(enabled = false))))
    }
    @Test fun sendButtonMustBeEnabledClickableAndNearTheInput() {
        assertEquals(listOf(0), ChatUiPolicy.sendButtons(listOf(send, send.copy(top = 50, bottom = 90), send.copy(enabled = false), send.copy(clickable = false)), input, 1000))
    }
    @Test fun unrelatedActionsAreNeverClicked() {
        assertTrue(ChatUiPolicy.sendButtons(listOf(send.copy(text = "转发"), send.copy(text = "删除"), send.copy(text = "发送给朋友"),
            send.copy(underScrollingContainer = true)), input, 1000).isEmpty())
        assertEquals(listOf(0), ChatUiPolicy.sendButtons(listOf(send.copy(text = "Send")), input, 1000))
    }
}
