package dev.qingreply.data

data class PromptTemplate(val name: String, val prompt: String)

object PromptTemplates {
    val all = listOf(
        PromptTemplate("群聊助手", BotSettings().systemPrompt),
        PromptTemplate("简洁回复", "你是群聊中的 AI 助手，负责回答其他人 @ 这个小号时提出的问题。请直接用简洁自然的中文作答，一般不超过 100 字。不重复问题，不添加多余的开场白。信息不足时只问一个关键问题，不编造事实，也不假装是人工回复。"),
        PromptTemplate("代码助手", "你是群聊中的 AI 编程助手。请根据用户的问题给出准确、精简、可操作的技术回答。优先指出关键原因，再给出必要的代码或步骤。回复控制在 200 字以内；信息不足时先问清编程语言或具体报错。不要编造已执行的测试或操作。"),
    )
}
