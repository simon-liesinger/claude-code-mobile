package com.claudecode.mobile

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(
    val role: String,
    val text: String,
    val toolName: String? = null,
    val isToolResult: Boolean = false,
    val isError: Boolean = false
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("claude_code", 0)

    val messages = mutableStateListOf<ChatMessage>()
    val isLoading = mutableStateOf(false)
    val apiKey = mutableStateOf(prefs.getString("api_key", "") ?: "")
    val isSetup = mutableStateOf(apiKey.value.isNotEmpty())
    val tokenCount = mutableStateOf(0 to 0)

    private var client: AnthropicClient? = null
    val toolExecutor = ToolExecutor(application)
    private val conversationHistory = JSONArray()

    private val systemPrompt = """
You are Claude Code running on an Android phone. You have full access to the phone's file system, shell, and system features through the tools provided.

Your workspace is at: ${toolExecutor.workDir.absolutePath}
Use it for projects and temporary files.

## Tools Available
- **bash**: Execute shell commands (ls, cp, mv, mkdir, rm, cat, grep, curl, git, etc.)
- **read_file**: Read any file on the device
- **write_file**: Write/create any file (auto-creates parent directories)
- **list_directory**: List directory contents with types and sizes
- **device_info**: Get device model, Android version, storage info, app paths
- **install_apk**: Install an APK file (opens system installer)
- **http_request**: Make HTTP requests (GET, POST, PUT, DELETE, PATCH)
- **show_notification**: Push a notification to the device
- **clipboard**: Read or write the device clipboard

## Shared Brain Repository
You have a shared GitHub repository for storing skills, memory, and data that persists across sessions and is shared with the desktop Claude Code instance.

**Repository: https://github.com/simon-liesinger/claude-brain**

Key practices:
- At the start of important sessions, pull the latest: `git -C <repo_path> pull`
- When you create or modify important files (skills, scripts, configs, notes), commit and push them
- The desktop Claude Code instance also uses this repo — always pull before editing to avoid conflicts
- Think of it as your shared brain between all Claude Code instances
- Store useful scripts, learned patterns, and reusable tools here

## Building Android Apps
To build Android apps on this device:
1. Generate the full project structure in your workspace
2. Push the project to GitHub
3. Use GitHub Actions to build (the project should include a build workflow)
4. Download the built APK and use install_apk to install it
5. Or if build tools are available locally (check with: which java, which javac), build locally

## Guidelines
- Be resourceful. You have broad system access — use it.
- When asked to do something, just do it. Don't ask for confirmation unless truly ambiguous.
- For long outputs, summarize key findings rather than dumping raw output.
- You can chain multiple tool calls to accomplish complex tasks.
- If a command fails, try alternative approaches before giving up.
""".trimIndent()

    fun saveApiKey(key: String) {
        apiKey.value = key
        prefs.edit().putString("api_key", key).apply()
        client = AnthropicClient(key)
        isSetup.value = true
    }

    fun clearHistory() {
        messages.clear()
        while (conversationHistory.length() > 0) {
            conversationHistory.remove(0)
        }
        tokenCount.value = 0 to 0
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || isLoading.value) return

        messages.add(ChatMessage("user", text))

        conversationHistory.put(JSONObject().apply {
            put("role", "user")
            put("content", text)
        })

        viewModelScope.launch {
            isLoading.value = true
            try {
                withContext(Dispatchers.IO) {
                    runConversationLoop()
                }
            } catch (e: Exception) {
                messages.add(ChatMessage("assistant", "Error: ${e.message}", isError = true))
            } finally {
                isLoading.value = false
            }
        }
    }

    private fun runConversationLoop() {
        val apiClient = client ?: AnthropicClient(apiKey.value).also { client = it }
        val tools = apiClient.buildToolDefinitions()

        var iterations = 0
        val maxIterations = 30

        while (iterations++ < maxIterations) {
            val response = apiClient.sendMessage(conversationHistory, systemPrompt, tools)

            tokenCount.value = tokenCount.value.let { (i, o) ->
                (i + response.inputTokens) to (o + response.outputTokens)
            }

            val assistantContent = JSONArray()
            var hasToolUse = false

            for (block in response.content) {
                when (block.type) {
                    "text" -> {
                        if (!block.text.isNullOrBlank()) {
                            messages.add(ChatMessage("assistant", block.text))
                            assistantContent.put(JSONObject().apply {
                                put("type", "text")
                                put("text", block.text)
                            })
                        }
                    }
                    "tool_use" -> {
                        hasToolUse = true
                        val inputPreview = block.input?.let { inp ->
                            when (block.name) {
                                "bash" -> inp.optString("command", "")
                                "read_file", "write_file" -> inp.optString("path", "")
                                "list_directory" -> inp.optString("path", "")
                                else -> block.name ?: ""
                            }
                        } ?: ""
                        messages.add(ChatMessage(
                            "assistant",
                            "${block.name}: $inputPreview",
                            toolName = block.name
                        ))
                        assistantContent.put(JSONObject().apply {
                            put("type", "tool_use")
                            put("id", block.id)
                            put("name", block.name)
                            put("input", block.input)
                        })
                    }
                }
            }

            conversationHistory.put(JSONObject().apply {
                put("role", "assistant")
                put("content", assistantContent)
            })

            if (!hasToolUse || response.stopReason != "tool_use") {
                break
            }

            // Execute tools and collect results
            val toolResults = JSONArray()
            for (block in response.content) {
                if (block.type == "tool_use" && block.name != null && block.input != null) {
                    val result = toolExecutor.execute(block.name, block.input)
                    val truncated = if (result.length > 80_000) {
                        result.take(80_000) + "\n... [truncated at 80KB]"
                    } else result

                    messages.add(ChatMessage(
                        "tool",
                        truncated,
                        toolName = block.name,
                        isToolResult = true
                    ))

                    toolResults.put(JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", block.id)
                        put("content", truncated)
                    })
                }
            }

            conversationHistory.put(JSONObject().apply {
                put("role", "user")
                put("content", toolResults)
            })
        }
    }
}
