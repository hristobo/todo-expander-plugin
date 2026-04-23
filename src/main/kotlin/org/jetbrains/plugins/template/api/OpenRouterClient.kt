package org.jetbrains.plugins.template.api

import java.net.HttpURLConnection
import java.net.URI

/**
 * Synchronous, blocking HTTP client for the OpenRouter chat completions API.
 *
 * **Threading:** All methods block the calling thread for the duration of the network request.
 * Always call from a background thread — never from the EDT.
 */
object OpenRouterClient {

    private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val MODEL = "nvidia/nemotron-3-super-120b-a12b:free"
    private const val MAX_TOKENS = 1024

    /**
     * Sends [todoLine] and [contextCode] to the OpenRouter API and returns the generated code.
     *
     * @param apiKey      Bearer token for the OpenRouter API.
     * @param todoLine    The trimmed TODO comment text.
     * @param contextCode Surrounding source code provided as context for the model.
     * @param language    Human-readable language name (e.g. "Kotlin", "Python"), or blank to
     *                    use a language-neutral system prompt.
     * @return The generated code on success. On an HTTP error, returns a string beginning with
     *         `"API Error (code):"`. On a network or I/O failure, returns a string beginning
     *         with `"Request failed:"`.
     */
    fun complete(apiKey: String, todoLine: String, contextCode: String, language: String): String {
        val systemPrompt = buildSystemPrompt(language)
        val userMessage = "TODO comment: $todoLine\n\nSurrounding code:\n$contextCode"
        val requestBody = buildChatRequestJson(systemPrompt, userMessage)

        var connection: HttpURLConnection? = null
        return try {
            connection = (URI.create(API_URL).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 60_000
            }

            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode == 200) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "Unknown error"
            }

            if (responseCode == 200) extractAssistantContent(responseBody)
            else "API Error ($responseCode):\n$responseBody"
        } catch (e: Exception) {
            "Request failed: ${e.message}"
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Builds a system prompt that instructs the model to generate [language] code.
     * When [language] is blank, returns a language-neutral fallback prompt.
     */
    private fun buildSystemPrompt(language: String): String =
        if (language.isNotBlank()) {
            "You are a coding assistant. The user has a TODO comment in a $language file. " +
            "Generate a concrete $language implementation that fulfills the TODO. " +
            "Return only clean code with brief inline comments, no markdown, no backticks, no explanations."
        } else {
            "You are a coding assistant. The user has a TODO comment. Generate a concrete code implementation " +
            "that fulfills the TODO. Return only clean code with brief inline comments, no markdown, no backticks, no explanations."
        }

    private fun buildChatRequestJson(systemPrompt: String, userMessage: String): String {
        val escapedSystem = systemPrompt.escapeJson()
        val escapedUser = userMessage.escapeJson()
        return """{"model":"$MODEL","max_tokens":$MAX_TOKENS,"messages":[{"role":"system","content":"$escapedSystem"},{"role":"user","content":"$escapedUser"}]}"""
    }

    /**
     * Extracts the assistant's reply from an OpenAI-compatible chat completion response.
     *
     * Expected shape: `{"choices":[{"message":{"role":"assistant","content":"..."}}], ...}`
     *
     * Uses a minimal character-level parser that handles the standard JSON escape sequences
     * (`\"`, `\n`, `\t`, `\r`, `\\`). Returns a descriptive failure string if the `"content"`
     * field is absent or the response is blank.
     */
    private fun extractAssistantContent(responseJson: String): String {
        val marker = "\"content\":\""
        val contentStart = responseJson.indexOf(marker).takeIf { it != -1 }
            ?: return "Could not parse API response."

        val sb = StringBuilder()
        var i = contentStart + marker.length
        while (i < responseJson.length) {
            when {
                responseJson[i] == '\\' && i + 1 < responseJson.length -> {
                    sb.append(
                        when (responseJson[i + 1]) {
                            '"'  -> '"'
                            'n'  -> '\n'
                            't'  -> '\t'
                            'r'  -> '\r'
                            '\\' -> '\\'
                            else -> responseJson[i + 1]
                        }
                    )
                    i += 2
                }
                responseJson[i] == '"' -> break
                else -> { sb.append(responseJson[i]); i++ }
            }
        }
        return sb.toString().ifBlank { "The API returned an empty response." }
    }

    private fun String.escapeJson() = replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
