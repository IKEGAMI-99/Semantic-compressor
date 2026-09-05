package com.ikegami99.semanticcompressor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

class SemanticCompressor(
    private val context: Context,
    private val modelManager: GemmaModelManager,
    private val logger: AppLogger,
) {
    data class Result(
        val file: File,
        val sizeBytes: Int,
        val previewPixels: Int,
        val previewQuality: Int,
    )

    suspend fun compress(sourceUri: Uri, targetKb: Int): Result {
        require(targetKb in 1..3)
        val workDir = File(context.cacheDir, "semantic_work").apply { mkdirs() }
        val modelInput = File(workDir, "analysis_${System.currentTimeMillis()}.jpg")

        val source = ImageDecoder.createSource(context.contentResolver, sourceUri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val maxSide = maxOf(w, h)
            if (maxSide > 1600) {
                val scale = 1600f / maxSide
                decoder.setTargetSize(
                    (w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }

        try {
            modelInput.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    "Could not create model input image"
                }
            }

            val rawSemantic = modelManager.analyze(modelInput)
            val targetBytes = targetKb * 1024
            val metadata = compactMetadata(rawSemantic, targetKb)
            val decoderText =
                "AI decode: use p.webp for composition/color and m.json for semantics/layout. Reconstruct realistically; add no major objects."

            val dimensions = intArrayOf(32, 28, 24, 20, 16, 12)
            val qualities = intArrayOf(42, 34, 26, 18, 10, 5)
            var smallest: Triple<ByteArray, Int, Int>? = null

            for (dimension in dimensions) {
                val scaled = scaleToBox(bitmap, dimension)
                try {
                    for (quality in qualities) {
                        val preview = encodeWebp(scaled, quality)
                        val archive = pack(preview, metadata, decoderText)
                        if (smallest == null || archive.size < smallest!!.first.size) {
                            smallest = Triple(archive, dimension, quality)
                        }
                        if (archive.size <= targetBytes) {
                            val output = writeResult(archive)
                            logger.i(
                                "Compressed image to ${archive.size} bytes target=$targetBytes preview=${dimension}px q=$quality"
                            )
                            return Result(output, archive.size, dimension, quality)
                        }
                    }
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
            }

            val best = smallest ?: error("Could not create semantic image")
            val output = writeResult(best.first)
            logger.w("Target $targetBytes bytes not reached; smallest=${best.first.size} bytes")
            return Result(output, best.first.size, best.second, best.third)
        } finally {
            modelInput.delete()
            bitmap.recycle()
        }
    }

    private fun writeResult(bytes: ByteArray): File {
        val dir = File(context.cacheDir, "simg").apply { mkdirs() }
        return File(dir, "semantic_${System.currentTimeMillis()}.simg").apply { writeBytes(bytes) }
    }

    private fun scaleToBox(bitmap: Bitmap, box: Int): Bitmap {
        val ratio = minOf(box.toFloat() / bitmap.width, box.toFloat() / bitmap.height)
        val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun encodeWebp(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= 30) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        check(bitmap.compress(format, quality, out)) { "WebP encoding failed" }
        return out.toByteArray()
    }

    private fun pack(preview: ByteArray, metadata: String, decoder: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(9)
            zip.putNextEntry(ZipEntry("p.webp"))
            zip.write(preview)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("m.json"))
            zip.write(metadata.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("d.txt"))
            zip.write(decoder.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun compactMetadata(raw: String, targetKb: Int): String {
        val firstBrace = raw.indexOf('{')
        val lastBrace = raw.lastIndexOf('}')
        val jsonText = if (firstBrace >= 0 && lastBrace > firstBrace) {
            raw.substring(firstBrace, lastBrace + 1)
        } else {
            ""
        }

        val maxSummary = when (targetKb) {
            1 -> 110
            2 -> 210
            else -> 320
        }
        val maxObjects = when (targetKb) {
            1 -> 3
            2 -> 5
            else -> 6
        }
        val maxColors = when (targetKb) {
            1 -> 3
            2 -> 4
            else -> 5
        }

        return runCatching {
            val input = JSONObject(jsonText)
            val output = JSONObject()
            val summary = input.optString("s").take(maxSummary)
            if (summary.isNotBlank()) output.put("s", summary)

            val sourceObjects = input.optJSONArray("o") ?: JSONArray()
            val objects = JSONArray()
            for (i in 0 until minOf(sourceObjects.length(), maxObjects)) {
                val item = sourceObjects.optJSONArray(i) ?: continue
                val compact = JSONArray()
                compact.put(item.optString(0).take(24))
                for (j in 1..4) compact.put(item.optInt(j).coerceIn(0, 100))
                objects.put(compact)
            }
            if (objects.length() > 0) output.put("o", objects)

            val sourceColors = input.optJSONArray("c") ?: JSONArray()
            val colors = JSONArray()
            for (i in 0 until minOf(sourceColors.length(), maxColors)) {
                colors.put(sourceColors.optString(i).take(7))
            }
            if (colors.length() > 0) output.put("c", colors)

            val lighting = input.optString("l").take(if (targetKb == 1) 60 else 120)
            if (lighting.isNotBlank()) output.put("l", lighting)
            output.toString()
        }.getOrElse {
            val fallback = raw
                .replace("```json", "")
                .replace("```", "")
                .replace('\n', ' ')
                .trim()
            JSONObject().put("s", fallback.take(maxSummary)).toString()
        }
    }
}
