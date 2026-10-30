package dev.qingreply.engine

object MentionMatcher {
    private fun normalize(text: String) = text.replace('＠', '@').replace('\u2005', ' ').replace('\u00a0', ' ')
    private val marker = Regex("^\\s*\\[有人@我]\\s*")

    fun matches(text: String, aliases: List<String>, allowMarker: Boolean = false): Boolean {
        val normalized = normalize(text)
        return (allowMarker && marker.containsMatchIn(normalized)) || aliases.any { alias ->
            Regex("[@]" + Regex.escape(normalize(alias).removePrefix("@")) + "(?=$|[\\s\\p{P}\\p{S}])")
                .containsMatchIn(normalized)
        }
    }

    fun question(text: String, aliases: List<String>): String {
        var result = marker.replaceFirst(normalize(text), "")
        aliases.sortedByDescending { it.length }.forEach { alias ->
            result = Regex("[@]" + Regex.escape(normalize(alias).removePrefix("@")) + "(?=$|[\\s\\p{P}\\p{S}])")
                .replace(result, "")
        }
        return result.trim().ifBlank { "有人在群聊中 @ 了你，请简短回应并询问需要什么帮助。" }
    }
}
