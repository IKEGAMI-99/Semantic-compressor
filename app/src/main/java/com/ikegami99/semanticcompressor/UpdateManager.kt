package com.ikegami99.semanticcompressor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(
    private val context: Context,
    private val logger: AppLogger,
) {
    data class ReleaseInfo(
        val version: String,
        val apkUrl: String,
        val assetName: String,
    )

    suspend fun checkLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        logger.i("Checking GitHub Releases for app update")
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Semantic-Compressor/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            if (code == 404) {
                logger.i("No GitHub Release exists yet")
                return@withContext null
            }
            check(code in 200..299) { "GitHub returned HTTP $code" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl = ""
            var assetName = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    assetName = name
                    break
                }
            }
            if (apkUrl.isBlank()) {
                logger.w("Latest release has no APK asset")
                return@withContext null
            }
            if (!isNewer(tag, BuildConfig.VERSION_NAME)) {
                logger.i("App is current: ${BuildConfig.VERSION_NAME}, latest=$tag")
                return@withContext null
            }
            logger.i("Update available: $tag asset=$assetName")
            ReleaseInfo(tag, apkUrl, assetName)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(release: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val output = File(dir, "semantic-compressor-${release.version}.apk")
        logger.i("Downloading update ${release.version}")
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Semantic-Compressor/${BuildConfig.VERSION_NAME}")
        }
        try {
            check(connection.responseCode in 200..299) { "APK download failed: HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                output.outputStream().buffered().use { out -> input.copyTo(out, 1024 * 1024) }
            }
        } finally {
            connection.disconnect()
        }
        check(output.length() > 100_000) { "Downloaded APK looks invalid" }
        logger.i("Update downloaded: ${output.length()} bytes")
        output
    }

    fun install(apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            logger.i("Requesting permission to install unknown apps")
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        logger.i("APK installer launched")
        return true
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val l = local.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val size = maxOf(r.size, l.size)
        for (i in 0 until size) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    companion object {
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/IKEGAMI-99/Semantic-compressor/releases/latest"
    }
}
