package com.arkanefans.mnn_engine.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class MnnChatRequest(
    val model: String?,
    val messages: List<Map<String, String>>,
    val stream: Boolean,
    val temperature: Double?,
    val topP: Double?,
    val maxTokens: Int,
)

internal object MnnChatRequestParser {
    const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
    private const val DEFAULT_MAX_TOKENS = 512
    private const val MAX_TOKENS = 8192
    private val allowedRoles = setOf("system", "user", "assistant")
    private val unsupportedFields = setOf(
        "tools",
        "tool_choice",
        "parallel_tool_calls",
        "response_format",
        "logprobs",
        "top_logprobs",
    )

    fun parse(body: String): MnnChatRequest {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) {
            "Request body exceeds 2 MiB."
        }
        val root = runCatching { JsonParser.parseString(body).asJsonObject }
            .getOrElse { throw IllegalArgumentException("Request body must be a JSON object.") }
        val messagesElement = root.get("messages")
        require(messagesElement != null && messagesElement.isJsonArray && messagesElement.asJsonArray.size() > 0) {
            "messages must be a non-empty array."
        }
        val messages = messagesElement.asJsonArray.map { element ->
            require(element.isJsonObject) { "Each message must be an object." }
            val item = element.asJsonObject
            val role = item.string("role") ?: throw IllegalArgumentException("Message role is required.")
            require(role in allowedRoles) { "Unsupported message role: $role" }
            val content = item.get("content")
            require(content != null && content.isJsonPrimitive && content.asJsonPrimitive.isString) {
                "Message content must be a string."
            }
            val contentText = content.asString
            require(role == "assistant" || contentText.isNotEmpty()) {
                "System and user message content must not be empty."
            }
            mapOf("role" to role, "content" to contentText)
        }
        val temperature = root.double("temperature")
        val topP = root.double("top_p")
        val maxTokens = root.int("max_tokens") ?: DEFAULT_MAX_TOKENS
        require(temperature == null || temperature in 0.0..2.0) { "temperature must be between 0 and 2." }
        require(topP == null || topP in 0.0..1.0) { "top_p must be between 0 and 1." }
        require(maxTokens in 1..MAX_TOKENS) { "max_tokens must be between 1 and $MAX_TOKENS." }
        val frequencyPenalty = root.double("frequency_penalty") ?: 0.0
        val presencePenalty = root.double("presence_penalty") ?: 0.0
        require(frequencyPenalty == 0.0 && presencePenalty == 0.0) {
            "frequency_penalty and presence_penalty are not supported in phase 1."
        }
        val n = root.int("n") ?: 1
        require(n == 1) { "n must be 1 in phase 1." }
        unsupportedFields.forEach { key ->
            val value = root.get(key)
            require(value == null || value.isJsonNull) { "$key is not supported in phase 1." }
        }
        return MnnChatRequest(
            model = root.string("model"),
            messages = messages,
            stream = root.bool("stream") ?: false,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
        )
    }

    private fun JsonObject.string(key: String): String? {
        val value = get(key) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$key must be a string." }
        return value.asString
    }

    private fun JsonObject.double(key: String): Double? {
        val value = get(key) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$key must be a number." }
        return value.asDouble
    }

    private fun JsonObject.int(key: String): Int? {
        val value = get(key) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$key must be an integer." }
        return value.asString.toIntOrNull()
            ?: throw IllegalArgumentException("$key must be an integer.")
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val value = get(key) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$key must be a boolean." }
        return value.asBoolean
    }
}
