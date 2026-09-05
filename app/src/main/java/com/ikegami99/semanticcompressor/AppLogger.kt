package com.ikegami99.semanticcompressor

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant

class AppLogger(private val context: Context) {
    private val lock = Any()
    private val logFile: File = File(context.filesDir, "logs/semantic-compressor.log").apply {
        parentFile?.mkdirs()
        if (!exists()) createNewFile()
    }

    fun i(message: String) = write("I", message, null)
    fun w(message: String) = write("W", message, null)
    fun e(message: String, throwable: Throwable? = null) = write("E", message, throwable)

    private fun write(level: String, message: String, throwable: Throwable?) {
        val line = buildString {
            append(Instant.now().toString())
            append(' ')
            append(level)
            append("  ")
            append(message.replace('\n', ' '))
            if (throwable != null) {
                append(" | ")
                append(throwable::class.java.simpleName)
                append(": ")
                append(throwable.message ?: "unknown")
                throwable.cause?.let { cause ->
                    append(" | cause=")
                    append(cause::class.java.simpleName)
                    append(": ")
                    append(cause.message ?: "unknown")
                }
            }
            append('\n')
        }

        synchronized(lock) {
            logFile.appendText(line, StandardCharsets.UTF_8)
            if (logFile.length() > MAX_LOG_BYTES) rotate()
        }

        when (level) {
            "E" -> Log.e(TAG, message, throwable)
            "W" -> Log.w(TAG, message)
            else -> Log.i(TAG, message)
        }
    }

    private fun rotate() {
        val text = logFile.readText(StandardCharsets.UTF_8)
        val keep = text.takeLast((MAX_LOG_BYTES / 2).toInt())
        logFile.writeText("--- log rotated ---\n$keep", StandardCharsets.UTF_8)
    }

    /**
     * SAFの出力先によって openOutputStream(uri, "wt") が0 byteになる端末があるため、
     * 一度メモリ上にスナップショットを作り、"w" で明示的に書き切ってflushする。
     * 戻り値は実際に書き込んだUTF-8バイト数。
     */
    suspend fun exportTo(uri: Uri): Long = withContext(Dispatchers.IO) {
        val snapshot = synchronized(lock) {
            val currentLog = if (logFile.exists()) {
                runCatching { logFile.readText(StandardCharsets.UTF_8) }.getOrDefault("")
            } else {
                ""
            }

            buildString {
                appendLine("Semantic Compressor diagnostic log")
                appendLine("exported=${Instant.now()}")
                appendLine("appVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("internalLogBytes=${logFile.length()}")
                appendLine("----------------------------------------")
                if (currentLog.isBlank()) {
                    appendLine("(internal log was empty)")
                } else {
                    append(currentLog)
                    if (!currentLog.endsWith('\n')) appendLine()
                }
            }
        }

        val bytes = snapshot.toByteArray(StandardCharsets.UTF_8)
        val resolver = context.contentResolver
        resolver.openOutputStream(uri, "w")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: error("ログの保存先を開けませんでした")

        check(bytes.isNotEmpty()) { "ログデータが空です" }
        i("Log exported successfully: ${bytes.size} bytes")
        bytes.size.toLong()
    }

    fun path(): String = logFile.absolutePath
    fun sizeBytes(): Long = synchronized(lock) { logFile.length() }

    companion object {
        private const val TAG = "SemanticCompressor"
        private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    }
}
