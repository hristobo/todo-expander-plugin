package org.jetbrains.plugins.template.api

import java.net.HttpURLConnection
import java.net.URL

object OpenRouterClient {

    private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val MODEL = "nvidia/nemotron-3-super-120b-a12b:free"
    private const val MAX_TOKENS = 1024

    private const val SYSTEM_PROMPT =
        "You are a coding assistant. The user has a TODO comment. Generate a concrete code implementation " +
        "that fulfills the TODO. Return only clean code with brief inline comments, no markdown, no backticks, no explanations."

    fun complete(apiKey: String, todoLine: String, contextCode: String): String {
        val userMessage = "TODO comment: $todoLine\n\nSurrounding code:\n$contextCode"
        val requestBody = buildChatRequestJson(userMessage)

        return try {
            val connection = URL(API_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode == 200) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "Unknown error"
            }

            if (responseCode == 200) extractAssistantContent(responseBody)
            else "API Error ($responseCode):\n$responseBody"
        } catch (e: Exception) {
            "Request failed: ${e.message}"
        }
    }

    private fun buildChatRequestJson(userMessage: String): String {
        val escapedSystem = SYSTEM_PROMPT.escapeJson()
        val escapedUser = userMessage.escapeJson()
        return """{"model":"$MODEL","max_tokens":$MAX_TOKENS,"messages":[{"role":"system","content":"$escapedSystem"},{"role":"user","content":"$escapedUser"}]}"""
    }

    /**
     * Extracts the assistant's reply from an OpenAI-compatible response.
     * Expected shape: {"choices":[{"message":{"role":"assistant","content":"..."}}], ...}
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
