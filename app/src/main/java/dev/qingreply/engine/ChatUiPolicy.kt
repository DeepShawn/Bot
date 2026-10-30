package dev.qingreply.engine

data class UiElement(
    val text: String = "", val className: String = "", val resourceId: String = "",
    val top: Int = 0, val bottom: Int = 0, val left: Int = 0, val right: Int = 0,
    val visible: Boolean = true, val enabled: Boolean = true, val editable: Boolean = false,
    val password: Boolean = false, val underScrollingContainer: Boolean = false,
    val clickable: Boolean = false, val showingHint: Boolean = false,
)

/** The chat header, never a message in the scrolling history, identifies the destination. */
object ChatUiPolicy {
    fun headerMatches(nodes: List<UiElement>, expected: String, top: Int, height: Int): Boolean {
        if (expected.isBlank() || height <= 0) return false
        return nodes.any { node -> node.visible && !node.editable && !node.password &&
            !node.underScrollingContainer && node.top >= top && node.bottom <= top + height / 4 &&
            node.text.trim() == expected.trim() &&
            (node.className.contains("TextView") || node.resourceId.contains("title", true)) }
    }

    fun inputs(nodes: List<UiElement>) = nodes.indices.filter { i -> nodes[i].let {
        it.visible && it.enabled && it.editable && !it.password && !it.resourceId.contains("search", true)
    } }

    fun sendButtons(nodes: List<UiElement>, input: UiElement, height: Int) = nodes.indices.filter { i -> nodes[i].let {
        it.visible && it.enabled && it.clickable && !it.editable && !it.underScrollingContainer &&
            (it.text.trim() == "发送" || it.text.trim().equals("send", true)) &&
            it.left >= input.left && kotlin.math.abs((it.top + it.bottom) / 2 - (input.top + input.bottom) / 2) <= height / 6
    } }

    fun draftIsEmpty(input: UiElement) = input.showingHint || input.text.isBlank()
}
