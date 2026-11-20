package com.personal.disciplinelock.domain.assistant

import com.personal.disciplinelock.data.SecurePreferences

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class AssistantResponse(
    val replyText: String,
    val isError: Boolean = false,
    val isOffline: Boolean = false,
    val errorMessage: String? = null
)

interface AssistantProvider {
    val providerId: String
    val displayName: String

    suspend fun sendMessage(history: List<ChatMessage>, userMessage: String): AssistantResponse
}

class GeminiAssistantProvider(
    private val securePreferences: SecurePreferences
) : AssistantProvider {
    override val providerId: String = "gemini"
    override val displayName: String = "Google Gemini API"

    override suspend fun sendMessage(history: List<ChatMessage>, userMessage: String): AssistantResponse {
        val apiKey = securePreferences.llmApiKey?.trim()
        if (apiKey.isNullOrEmpty()) {
            return AssistantResponse(
                replyText = "",
                isError = true,
                errorMessage = "LLM API Key is not configured. Set your Gemini API key in Settings."
            )
        }
        // Stub implementation for feature 5: ready for direct HTTP call or SDK hook
        return AssistantResponse(
            replyText = "Assistant active. Hooked to $displayName. (Stub ready for live requests).",
            isError = false
        )
    }
}

class OpenAiAssistantProvider(
    private val securePreferences: SecurePreferences
) : AssistantProvider {
    override val providerId: String = "openai"
    override val displayName: String = "OpenAI API"

    override suspend fun sendMessage(history: List<ChatMessage>, userMessage: String): AssistantResponse {
        val apiKey = securePreferences.llmApiKey?.trim()
        if (apiKey.isNullOrEmpty()) {
            return AssistantResponse(
                replyText = "",
                isError = true,
                errorMessage = "LLM API Key is not configured. Set your OpenAI API key in Settings."
            )
        }
        return AssistantResponse(
            replyText = "Assistant active. Hooked to $displayName. (Stub ready for live requests).",
            isError = false
        )
    }
}
