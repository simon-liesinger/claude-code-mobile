package com.claudecode.mobile

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JSONObject? = null
)

data class ApiResponse(
    val content: List<ContentBlock>,
    val stopReason: String,
    val inputTokens: Int,
    val outputTokens: Int
)

class AnthropicClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://api.anthropic.com/v1/messages"
    private val model = "claude-sonnet-4-20250514"

    fun sendMessage(
        messages: JSONArray,
        systemPrompt: String,
        tools: JSONArray
    ): ApiResponse {
        val requestBody = JSONObject().apply {
            put("model", model)
            put("max_tokens", 16384)
            put("system", systemPrompt)
            put("messages", messages)
            put("tools", tools)
        }

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw IOException("Empty response body")

        if (!response.isSuccessful) {
            throw IOException("API error ${response.code}: $body")
        }

        return parseResponse(JSONObject(body))
    }

    private fun parseResponse(json: JSONObject): ApiResponse {
        val contentArray = json.getJSONArray("content")
        val blocks = mutableListOf<ContentBlock>()

        for (i in 0 until contentArray.length()) {
            val block = contentArray.getJSONObject(i)
            when (val type = block.getString("type")) {
                "text" -> blocks.add(ContentBlock(type = "text", text = block.getString("text")))
                "tool_use" -> blocks.add(
                    ContentBlock(
                        type = "tool_use",
                        id = block.getString("id"),
                        name = block.getString("name"),
                        input = block.getJSONObject("input")
                    )
                )
                else -> blocks.add(ContentBlock(type = type, text = block.optString("text")))
            }
        }

        val usage = json.getJSONObject("usage")
        return ApiResponse(
            content = blocks,
            stopReason = json.getString("stop_reason"),
            inputTokens = usage.getInt("input_tokens"),
            outputTokens = usage.getInt("output_tokens")
        )
    }

    fun buildToolDefinitions(): JSONArray {
        return JSONArray().apply {
            put(tool(
                "bash",
                "Execute a shell command on the Android device. Use this for system commands, git, file operations, etc. Commands run in the app's workspace directory.",
                props("command" to prop("string", "The shell command to execute")),
                required = listOf("command")
            ))
            put(tool(
                "read_file",
                "Read the contents of a file. Returns the full text content.",
                props("path" to prop("string", "Absolute path to the file to read")),
                required = listOf("path")
            ))
            put(tool(
                "write_file",
                "Write content to a file. Creates the file and any parent directories if they don't exist. Overwrites existing files.",
                props(
                    "path" to prop("string", "Absolute path to write to"),
                    "content" to prop("string", "The content to write")
                ),
                required = listOf("path", "content")
            ))
            put(tool(
                "list_directory",
                "List files and directories at a path. Shows file type (d for directory, - for file) and size.",
                props("path" to prop("string", "Directory path to list")),
                required = listOf("path")
            ))
            put(tool(
                "device_info",
                "Get Android device information including model, OS version, storage, architecture, and app paths.",
                props(),
                required = listOf()
            ))
            put(tool(
                "install_apk",
                "Install an APK file on the device. Opens the system install dialog.",
                props("path" to prop("string", "Absolute path to the APK file")),
                required = listOf("path")
            ))
            put(tool(
                "http_request",
                "Make an HTTP request. Returns the status code and response body.",
                props(
                    "url" to prop("string", "The URL to request"),
                    "method" to prop("string", "HTTP method: GET, POST, PUT, DELETE. Default: GET"),
                    "headers" to prop("string", "JSON object of headers as a string, e.g. {\"Authorization\": \"Bearer xxx\"}"),
                    "body" to prop("string", "Request body for POST/PUT requests")
                ),
                required = listOf("url")
            ))
            put(tool(
                "show_notification",
                "Show a system notification on the device.",
                props(
                    "title" to prop("string", "Notification title"),
                    "message" to prop("string", "Notification body text")
                ),
                required = listOf("title", "message")
            ))
            put(tool(
                "clipboard",
                "Read from or write to the device clipboard.",
                props(
                    "action" to prop("string", "Either 'read' or 'write'"),
                    "text" to prop("string", "Text to write to clipboard (only for write action)")
                ),
                required = listOf("action")
            ))
        }
    }

    private fun tool(name: String, description: String, properties: JSONObject, required: List<String>): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray(required))
            })
        }
    }

    private fun props(vararg pairs: Pair<String, JSONObject>): JSONObject {
        return JSONObject().apply { pairs.forEach { put(it.first, it.second) } }
    }

    private fun prop(type: String, description: String): JSONObject {
        return JSONObject().apply {
            put("type", type)
            put("description", description)
        }
    }
}
