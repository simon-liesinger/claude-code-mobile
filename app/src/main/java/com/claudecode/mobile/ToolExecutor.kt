package com.claudecode.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ToolExecutor(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val workDir = File(context.filesDir, "workspace").also { it.mkdirs() }

    fun execute(toolName: String, input: JSONObject): String {
        return try {
            when (toolName) {
                "bash" -> executeBash(input.getString("command"))
                "read_file" -> readFile(input.getString("path"))
                "write_file" -> writeFile(input.getString("path"), input.getString("content"))
                "list_directory" -> listDirectory(input.getString("path"))
                "device_info" -> deviceInfo()
                "install_apk" -> installApk(input.getString("path"))
                "http_request" -> httpRequest(input)
                "show_notification" -> showNotification(input.getString("title"), input.getString("message"))
                "clipboard" -> clipboard(input)
                else -> "Error: Unknown tool '$toolName'"
            }
        } catch (e: Exception) {
            "Error executing $toolName: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun executeBash(command: String): String {
        val env = mapOf(
            "HOME" to context.filesDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "PATH" to "/system/bin:/system/xbin:/sbin:/vendor/bin",
            "WORKSPACE" to workDir.absolutePath
        )

        val processBuilder = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workDir)
            .redirectErrorStream(true)

        processBuilder.environment().putAll(env)

        val process = processBuilder.start()

        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            output.appendLine(line)
            if (output.length > 200_000) {
                output.append("\n... [output truncated at 200KB]")
                process.destroy()
                break
            }
        }

        val exitCode = process.waitFor()
        val result = output.toString().trimEnd()

        return if (exitCode == 0) result
        else "$result\n[Exit code: $exitCode]"
    }

    private fun readFile(path: String): String {
        val file = File(path)
        if (!file.exists()) return "Error: File not found: $path"
        if (!file.canRead()) return "Error: Permission denied reading: $path"
        if (file.isDirectory) return "Error: Path is a directory, not a file: $path"

        val size = file.length()
        if (size > 1_000_000) {
            val content = file.readText().take(500_000)
            return "$content\n\n... [truncated - file is ${size / 1024}KB]"
        }
        return file.readText()
    }

    private fun writeFile(path: String, content: String): String {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Written ${content.length} chars to $path"
    }

    private fun listDirectory(path: String): String {
        val dir = File(path)
        if (!dir.exists()) return "Error: Directory not found: $path"
        if (!dir.isDirectory) return "Error: Not a directory: $path"

        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ?: return "Error: Cannot list directory (permission denied?)"

        if (entries.isEmpty()) return "(empty directory)"

        return entries.joinToString("\n") { f ->
            val type = if (f.isDirectory) "d" else "-"
            val size = if (f.isFile) {
                val kb = f.length() / 1024
                if (kb > 0) " (${kb}KB)" else " (${f.length()}B)"
            } else ""
            "$type ${f.name}$size"
        }
    }

    private fun deviceInfo(): String {
        val dataStat = StatFs(Environment.getDataDirectory().path)
        val freeSpace = dataStat.availableBytes / (1024 * 1024)
        val totalSpace = dataStat.totalBytes / (1024 * 1024)

        return buildString {
            appendLine("== Device ==")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Architecture: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            appendLine("== Storage ==")
            appendLine("Internal: ${freeSpace}MB free / ${totalSpace}MB total")
            try {
                val extStat = StatFs(Environment.getExternalStorageDirectory().path)
                val extFree = extStat.availableBytes / (1024 * 1024)
                val extTotal = extStat.totalBytes / (1024 * 1024)
                appendLine("External: ${extFree}MB free / ${extTotal}MB total")
            } catch (_: Exception) {
                appendLine("External: unavailable")
            }
            appendLine()
            appendLine("== Paths ==")
            appendLine("App files: ${context.filesDir.absolutePath}")
            appendLine("App cache: ${context.cacheDir.absolutePath}")
            appendLine("Workspace: ${workDir.absolutePath}")
            appendLine("External storage: ${Environment.getExternalStorageDirectory().absolutePath}")
            appendLine("Downloads: ${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath}")
        }.trimEnd()
    }

    private fun installApk(path: String): String {
        val file = File(path)
        if (!file.exists()) return "Error: APK not found: $path"
        if (!file.name.endsWith(".apk")) return "Error: File is not an APK: $path"

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
        return "APK install dialog opened for ${file.name}"
    }

    private fun httpRequest(input: JSONObject): String {
        val url = input.getString("url")
        val method = input.optString("method", "GET").uppercase()
        val body = input.optString("body", "")
        val headersStr = input.optString("headers", "")

        val requestBuilder = Request.Builder().url(url)

        if (headersStr.isNotBlank()) {
            try {
                val headers = JSONObject(headersStr)
                for (key in headers.keys()) {
                    requestBuilder.addHeader(key, headers.getString(key))
                }
            } catch (_: Exception) { }
        }

        when (method) {
            "POST" -> requestBuilder.post(body.toRequestBody("application/json".toMediaType()))
            "PUT" -> requestBuilder.put(body.toRequestBody("application/json".toMediaType()))
            "DELETE" -> if (body.isNotBlank()) {
                requestBuilder.delete(body.toRequestBody("application/json".toMediaType()))
            } else {
                requestBuilder.delete()
            }
            "PATCH" -> requestBuilder.patch(body.toRequestBody("application/json".toMediaType()))
            else -> requestBuilder.get()
        }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        val responseBody = response.body?.string() ?: ""

        val truncated = if (responseBody.length > 100_000) {
            responseBody.take(100_000) + "\n... [truncated]"
        } else responseBody

        return "HTTP ${response.code} ${response.message}\n\n$truncated"
    }

    private fun showNotification(title: String, message: String): String {
        val channelId = "claude_code_notifications"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Claude Code",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications from Claude Code"
            }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notifId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        manager.notify(notifId, notification)
        return "Notification shown: $title"
    }

    private fun clipboard(input: JSONObject): String {
        val action = input.getString("action")

        return when (action) {
            "read" -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).text?.toString() ?: "(clipboard is empty or contains non-text data)"
                } else {
                    "(clipboard is empty)"
                }
            }
            "write" -> {
                val text = input.optString("text", "")
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Claude Code", text)
                clipboard.setPrimaryClip(clip)
                "Copied ${text.length} chars to clipboard"
            }
            else -> "Error: Unknown clipboard action '$action'. Use 'read' or 'write'."
        }
    }
}
