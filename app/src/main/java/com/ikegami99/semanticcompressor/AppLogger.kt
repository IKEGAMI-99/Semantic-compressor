package com.ikegami99.semanticcompressor

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.time.Instant

class AppLogger(private val context: Context) {
    private val lock = Any()
    private val logFile: File = File(context.filesDir, "logs/semantic-compressor.log").apply {
        parentFile?.mkdirs()
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
            }
            append('\n')
        }
        synchronized(lock) {
            logFile.appendText(line)
            if (logFile.length() > MAX_LOG_BYTES) rotate()
        }
        when (level) {
            "E" -> Log.e(TAG, message, throwable)
            "W" -> Log.w(TAG, message)
            else -> Log.i(TAG, message)
        }
    }

    private fun rotate() {
        val text = logFile.readText()
        val keep = text.takeLast((MAX_LOG_BYTES / 2).toInt())
        logFile.writeText("--- log rotated ---\n$keep")
    }

    fun exportTo(uri: Uri) {
        synchronized(lock) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                logFile.inputStream().use { it.copyTo(output) }
            } ?: error("Could not open export destination")
        }
        i("Log exported")
    }

    fun path(): String = logFile.absolutePath

    companion object {
        private const val TAG = "SemanticCompressor"
        private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    }
}
