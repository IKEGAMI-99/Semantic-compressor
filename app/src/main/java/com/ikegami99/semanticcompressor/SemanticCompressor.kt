package com.ikegami99.semanticcompressor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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

    data class ShareReferences(
        val preview: File,
        val heads: File?,
    )

    suspend fun compress(sourceUri: Uri, targetKb: Int): Result = withContext(Dispatchers.IO) {
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
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    "Could not create model input image"
                }
            }

            val rawSemantic = modelManager.analyze(modelInput, targetKb)
            val targetBytes = targetKb * 1024
            val metadataLevels = when (targetKb) {
                1 -> intArrayOf(0, -1)
                else -> intArrayOf(2, 1, 0, -1)
            }
            val metadataVariants = metadataLevels
                .map { compactMetadata(rawSemantic, targetKb, it) }
                .distinct()

            val headReference = if (targetKb >= 2) {
                buildHeadReference(bitmap, rawSemantic, targetKb)
            } else {
                null
            }
            val headOptions: List<ByteArray?> = if (headReference != null) {
                listOf(headReference, null)
            } else {
                listOf(null)
            }

            val decoderText =
                "AI decode v3: p.webp=global composition/color. m.json=authoritative subject/layout/camera/OCR data. m.json.n contains hard exact counts for recorded subject categories; any category recorded as 0 must not appear. h.webp, if present, is a left-to-right head reference strip for the first p[] subjects. Preserve subject count, apparent age, build, hair, eyewear/headwear, facial hair, clothing, accessories, pose/hand positions, spacing and visible text. Do not infer names or identities. Do not add unrecorded subjects or major objects."

            val dimensions = intArrayOf(32, 28, 24, 20, 16, 12)
            val qualities = intArrayOf(46, 38, 30, 22, 14, 7)
            var smallest: Triple<ByteArray, Int, Int>? = null

            for (head in headOptions) {
                for ((metadataIndex, metadata) in metadataVariants.withIndex()) {
                    for (dimension in dimensions) {
                        val scaled = scaleToBox(bitmap, dimension)
                        try {
                            for (quality in qualities) {
                                val preview = encodeWebp(scaled, quality)
                                val archive = pack(preview, metadata, decoderText, head)
                                if (smallest == null || archive.size < smallest!!.first.size) {
                                    smallest = Triple(archive, dimension, quality)
                                }
                                if (archive.size <= targetBytes) {
                                    val output = writeResult(archive)
                                    logger.i(
                                        "Compressed image to ${archive.size} bytes target=$targetBytes preview=${dimension}px q=$quality metadataVariant=$metadataIndex headRef=${head != null}"
                                    )
                                    return@withContext Result(output, archive.size, dimension, quality)
                                }
                            }
                        } finally {
                            if (scaled !== bitmap) scaled.recycle()
                        }
                    }
                }
            }

            val best = smallest ?: error("Could not create semantic image")
            val output = writeResult(best.first)
            logger.w("Target $targetBytes bytes not reached; smallest=${best.first.size} bytes")
            Result(output, best.first.size, best.second, best.third)
        } finally {
            modelInput.delete()
            bitmap.recycle()
        }
    }

    fun extractShareReferences(simg: File): ShareReferences {
        val shareDir = File(context.cacheDir, "simg_share_refs")
        if (shareDir.exists()) shareDir.deleteRecursively()
        check(shareDir.mkdirs() || shareDir.isDirectory) { "共有用一時フォルダを作成できませんでした" }

        ZipFile(simg).use { zip ->
            val previewEntry = zip.getEntry("p.webp") ?: error(".simg に p.webp がありません")
            val preview = File(shareDir, "p.webp")
            zip.getInputStream(previewEntry).use { input ->
                preview.outputStream().buffered().use { output -> input.copyTo(output) }
            }

            val headEntry = zip.getEntry("h.webp")
            val heads = if (headEntry != null) {
                File(shareDir, "h.webp").also { file ->
                    zip.getInputStream(headEntry).use { input ->
                        file.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                }
            } else {
                null
            }

            logger.i(
                "Share references extracted: preview=${preview.length()} bytes heads=${heads?.length() ?: 0} bytes"
            )
            return ShareReferences(preview = preview, heads = heads)
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

    private fun pack(
        preview: ByteArray,
        metadata: String,
        decoder: String,
        headReference: ByteArray?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(9)
            zip.putNextEntry(ZipEntry("p.webp"))
            zip.write(preview)
            zip.closeEntry()

            if (headReference != null) {
                zip.putNextEntry(ZipEntry("h.webp"))
                zip.write(headReference)
                zip.closeEntry()
            }

            zip.putNextEntry(ZipEntry("m.json"))
            zip.write(metadata.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("d.txt"))
            zip.write(decoder.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun extractJson(raw: String): JSONObject? {
        val firstBrace = raw.indexOf('{')
        val lastBrace = raw.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) return null
        return runCatching { JSONObject(raw.substring(firstBrace, lastBrace + 1)) }.getOrNull()
    }

    private fun compactMetadata(raw: String, targetKb: Int, level: Int): String {
        val input = extractJson(raw)
        if (input == null) {
            val fallback = raw
                .replace("```json", "")
                .replace("```", "")
                .replace('\n', ' ')
                .trim()
            val max = when (targetKb) {
                1 -> 160
                2 -> 320
                else -> 520
            }
            return JSONObject().put("s", fallback.take(max)).toString()
        }

        val output = JSONObject()
        val summaryMax = when {
            level >= 2 -> if (targetKb == 3) 520 else 360
            level == 1 -> if (targetKb == 1) 160 else 280
            level == 0 -> if (targetKb == 1) 130 else 210
            else -> if (targetKb == 1) 90 else 140
        }
        putString(output, "s", input.optString("s"), summaryMax)

        val sourceCounts = input.optJSONObject("n")
        if (sourceCounts != null) {
            val counts = JSONObject()
            counts.put("people", sourceCounts.optInt("people", 0).coerceIn(0, 99))
            counts.put("animals", sourceCounts.optInt("animals", 0).coerceIn(0, 99))
            counts.put("vehicles", sourceCounts.optInt("vehicles", 0).coerceIn(0, 99))
            output.put("n", counts)
        } else {
            val sourcePeople = input.optJSONArray("p")
            if (sourcePeople != null) {
                output.put("n", JSONObject().put("people", sourcePeople.length().coerceIn(0, 99)))
            }
        }

        val camera = input.optJSONObject("cam")
        if (camera != null && level >= 0) {
            val camOut = JSONObject()
            putString(camOut, "shot", camera.optString("shot"), 42)
            putString(camOut, "angle", camera.optString("angle"), 42)
            if (level >= 1) {
                putString(camOut, "view", camera.optString("view"), 42)
                putString(camOut, "fov", camera.optString("fov"), 36)
            }
            if (camOut.length() > 0) output.put("cam", camOut)
        }

        val sourcePeople = input.optJSONArray("p") ?: JSONArray()
        val people = JSONArray()
        val maxPeople = when {
            level >= 2 -> if (targetKb == 3) 6 else 4
            level == 1 -> if (targetKb == 3) 4 else 3
            else -> if (targetKb == 1) 2 else 3
        }
        for (i in 0 until minOf(sourcePeople.length(), maxPeople)) {
            val person = sourcePeople.optJSONObject(i) ?: continue
            val p = JSONObject()
            copyBox(person.optJSONArray("b"))?.let { p.put("b", it) }
            copyBox(person.optJSONArray("hb"))?.let { p.put("hb", it) }
            putString(p, "age", person.optString("age"), if (level >= 1) 36 else 24)
            if (level >= 1) putString(p, "build", person.optString("build"), 48)
            putString(p, "hair", person.optString("hair"), if (level >= 1) 70 else 42)
            if (level >= 1) putString(p, "face", person.optString("face"), if (level >= 2) 120 else 70)

            val clothingLimit = when {
                level >= 2 -> 5
                level == 1 -> 4
                level == 0 -> 3
                else -> 2
            }
            copyStrings(person.optJSONArray("cl"), clothingLimit, if (level >= 1) 86 else 52)
                .takeIf { it.length() > 0 }
                ?.let { p.put("cl", it) }

            val accessoryLimit = when {
                level >= 2 -> 4
                level == 1 -> 3
                else -> 2
            }
            copyStrings(person.optJSONArray("acc"), accessoryLimit, if (level >= 1) 80 else 48)
                .takeIf { it.length() > 0 }
                ?.let { p.put("acc", it) }

            putString(p, "pose", person.optString("pose"), when {
                level >= 2 -> 130
                level == 1 -> 90
                else -> 58
            })
            if (level >= 1) {
                putString(p, "expr", person.optString("expr"), 50)
                putString(p, "dir", person.optString("dir"), 52)
            }
            if (p.length() > 0) people.put(p)
        }
        if (people.length() > 0) output.put("p", people)

        val sourceObjects = input.optJSONArray("o") ?: JSONArray()
        val objects = JSONArray()
        val maxObjects = when {
            level >= 2 -> 8
            level == 1 -> 5
            level == 0 -> 3
            else -> 1
        }
        for (i in 0 until minOf(sourceObjects.length(), maxObjects)) {
            val sourceObject = sourceObjects.optJSONObject(i) ?: continue
            val obj = JSONObject()
            putString(obj, "t", sourceObject.optString("t"), 40)
            copyBox(sourceObject.optJSONArray("b"))?.let { obj.put("b", it) }
            if (level >= 1) putString(obj, "a", sourceObject.optString("a"), if (level >= 2) 90 else 60)
            if (obj.length() > 0) objects.put(obj)
        }
        if (objects.length() > 0) output.put("o", objects)

        if (level >= 0) {
            val backgroundLimit = when {
                level >= 2 -> 4
                level == 1 -> 3
                else -> 1
            }
            copyStrings(input.optJSONArray("bg"), backgroundLimit, if (level >= 1) 100 else 70)
                .takeIf { it.length() > 0 }
                ?.let { output.put("bg", it) }
        }

        val colorLimit = when {
            level >= 2 -> 6
            level == 1 -> 5
            level == 0 -> 3
            else -> 2
        }
        copyStrings(input.optJSONArray("c"), colorLimit, 9)
            .takeIf { it.length() > 0 }
            ?.let { output.put("c", it) }

        putString(output, "l", input.optString("l"), when {
            level >= 2 -> 180
            level == 1 -> 120
            level == 0 -> 70
            else -> 45
        })

        val sourceText = input.optJSONArray("txt") ?: JSONArray()
        val texts = JSONArray()
        val textLimit = when {
            level >= 2 -> 4
            level == 1 -> 2
            else -> 1
        }
        for (i in 0 until minOf(sourceText.length(), textLimit)) {
            val source = sourceText.optJSONObject(i) ?: continue
            val item = JSONObject()
            putString(item, "v", source.optString("v"), if (level >= 1) 80 else 40)
            copyBox(source.optJSONArray("b"))?.let { item.put("b", it) }
            if (item.length() > 0) texts.put(item)
        }
        if (texts.length() > 0) output.put("txt", texts)

        return output.toString()
    }

    private fun putString(target: JSONObject, key: String, value: String, maxChars: Int) {
        val text = value.trim()
        if (text.isNotEmpty()) target.put(key, text.take(maxChars))
    }

    private fun copyStrings(source: JSONArray?, limit: Int, maxChars: Int): JSONArray {
        val output = JSONArray()
        if (source == null) return output
        for (i in 0 until minOf(source.length(), limit)) {
            val value = source.optString(i).trim()
            if (value.isNotEmpty()) output.put(value.take(maxChars))
        }
        return output
    }

    private fun copyBox(source: JSONArray?): JSONArray? {
        if (source == null || source.length() < 4) return null
        val output = JSONArray()
        for (i in 0..3) output.put(source.optInt(i).coerceIn(0, 100))
        return output
    }

    private fun buildHeadReference(bitmap: Bitmap, rawSemantic: String, targetKb: Int): ByteArray? {
        val input = extractJson(rawSemantic) ?: return null
        val people = input.optJSONArray("p") ?: return null
        val maxHeads = if (targetKb >= 3) 3 else 2
        val headSize = if (targetKb >= 3) 28 else 22
        val heads = mutableListOf<Bitmap>()

        try {
            for (i in 0 until minOf(people.length(), maxHeads)) {
                val person = people.optJSONObject(i) ?: continue
                val headBox = person.optJSONArray("hb") ?: approximateHeadBox(person.optJSONArray("b")) ?: continue
                cropNormalized(bitmap, headBox, 0.16f)?.let { crop ->
                    val scaled = Bitmap.createScaledBitmap(crop, headSize, headSize, true)
                    if (scaled !== crop) crop.recycle()
                    heads += scaled
                }
            }

            if (heads.isEmpty()) return null
            val sheet = Bitmap.createBitmap(headSize * heads.size, headSize, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(sheet)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                heads.forEachIndexed { index, head ->
                    canvas.drawBitmap(head, (index * headSize).toFloat(), 0f, paint)
                }
                val bytes = encodeWebp(sheet, if (targetKb >= 3) 44 else 34)
                logger.i("Head reference created: heads=${heads.size} ${bytes.size} bytes")
                return bytes
            } finally {
                sheet.recycle()
            }
        } finally {
            heads.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun approximateHeadBox(personBox: JSONArray?): JSONArray? {
        if (personBox == null || personBox.length() < 4) return null
        val x = personBox.optInt(0).coerceIn(0, 100)
        val y = personBox.optInt(1).coerceIn(0, 100)
        val w = personBox.optInt(2).coerceIn(1, 100)
        val h = (personBox.optInt(3).coerceIn(1, 100) * 0.34f).roundToInt().coerceAtLeast(1)
        return JSONArray().put(x).put(y).put(w).put(h)
    }

    private fun cropNormalized(bitmap: Bitmap, box: JSONArray, margin: Float): Bitmap? {
        if (box.length() < 4) return null
        val x = box.optInt(0).coerceIn(0, 100) / 100f
        val y = box.optInt(1).coerceIn(0, 100) / 100f
        val w = box.optInt(2).coerceIn(1, 100) / 100f
        val h = box.optInt(3).coerceIn(1, 100) / 100f

        val leftNorm = (x - w * margin).coerceIn(0f, 1f)
        val topNorm = (y - h * margin).coerceIn(0f, 1f)
        val rightNorm = (x + w * (1f + margin)).coerceIn(0f, 1f)
        val bottomNorm = (y + h * (1f + margin)).coerceIn(0f, 1f)

        val left = (leftNorm * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (topNorm * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (rightNorm * bitmap.width).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bottomNorm * bitmap.height).roundToInt().coerceIn(top + 1, bitmap.height)

        return runCatching {
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        }.getOrNull()
    }
}
