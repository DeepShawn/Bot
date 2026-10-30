package dev.qingreply.engine

data class ReplyCapability(val freeForm: Boolean, val authenticated: Boolean, val semanticReply: Boolean, val title: String)

object ReplyActionPolicy {
    fun choose(actions: List<ReplyCapability>): Int? {
        val usable = actions.indices.filter { actions[it].freeForm && !actions[it].authenticated }
        usable.firstOrNull { actions[it].semanticReply }?.let { return it }
        usable.firstOrNull { actions[it].title.contains("回复") || actions[it].title.contains("reply", true) }?.let { return it }
        // Some QQ/system versions omit the label. A single free-text action is unambiguous.
        return usable.singleOrNull()
    }
}
