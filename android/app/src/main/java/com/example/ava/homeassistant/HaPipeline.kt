package com.example.ava.homeassistant

data class HaEngineOption(
    val id: String,
    val name: String,
    val languages: List<String> = emptyList(),
) {
    fun matchLanguage(preferred: String): String? {
        if (languages.isEmpty()) return preferred.takeIf { it.isNotBlank() }
        languages.find { it.equals(preferred, ignoreCase = true) }?.let { return it }
        val prefix = preferred.substringBefore('-').substringBefore('_')
        if (prefix.isNotBlank()) {
            languages.find {
                it.substringBefore('-').substringBefore('_').equals(prefix, ignoreCase = true)
            }?.let { return it }
        }
        return languages.firstOrNull()
    }

    fun supports(language: String): Boolean {
        if (languages.isEmpty() || language.isBlank()) return true
        return matchLanguage(language) != null
    }
}

data class HaPipelineCatalog(
    val languages: List<String> = emptyList(),
    val conversationAgents: List<HaEngineOption> = emptyList(),
    val sttEngines: List<HaEngineOption> = emptyList(),
    val ttsEngines: List<HaEngineOption> = emptyList(),
)

data class HaPipeline(
    val id: String,
    val name: String,
    val language: String,
    val sttEngine: String?,
    val sttLanguage: String?,
    val ttsEngine: String?,
    val ttsLanguage: String?,
    val ttsVoice: String?,
    val conversationEngine: String?,
    val conversationLanguage: String?,
    val wakeWordEntity: String?,
    val wakeWordId: String?,
)
