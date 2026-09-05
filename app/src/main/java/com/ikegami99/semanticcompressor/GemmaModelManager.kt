package com.ikegami99.semanticcompressor

import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GemmaModelManager(
    private val context: Context,
    private val logger: AppLogger,
) {
    private var engine: Engine? = null
    private val modelDir = File(context.filesDir, "models").apply { mkdirs() }
    private val modelFile = File(modelDir, "gemma4.litertlm")

    fun hasSavedModel(): Boolean = modelFile.exists() && modelFile.length() > 10_000_000
    fun savedModelSizeBytes(): Long = if (modelFile.exists()) modelFile.length() else 0L

    suspend fun importAndLoad(uri: Uri) = withContext(Dispatchers.IO) {
        logger.i("Importing Gemma 4 model from SAF")
        val temp = File(modelDir, "gemma4.litertlm.part")
        temp.delete()

        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().buffered().use { output -> input.copyTo(output, 1024 * 1024) }
        } ?: error("選択したモデルファイルを開けませんでした")

        if (temp.length() < 10_000_000) {
            temp.delete()
            error("選択したファイルが小さすぎます。.litertlmモデルを選択してください")
        }

        if (modelFile.exists()) modelFile.delete()
        check(temp.renameTo(modelFile)) { "モデルファイルの保存に失敗しました" }
        logger.i("Model imported: ${modelFile.length()} bytes")

        try {
            loadInternal()
        } catch (t: Throwable) {
            if (isVisionEncoderMissing(t)) {
                logger.e("Imported model has no vision encoder", t)
                close()
                modelFile.delete()
                throw IllegalStateException(
                    "このGemma 4モデルには画像エンコーダが入っていません。約2.59GBの「gemma-4-E2B-it.litertlm」を使用してください。",
                    t,
                )
            }
            throw t
        }
    }

    suspend fun loadSaved() = withContext(Dispatchers.IO) {
        check(hasSavedModel()) { "保存済みのGemma 4モデルがありません" }
        try {
            loadInternal()
        } catch (t: Throwable) {
            if (isVisionEncoderMissing(t)) {
                logger.e("Saved model has no vision encoder; deleting incompatible model", t)
                close()
                modelFile.delete()
                throw IllegalStateException(
                    "保存済みモデルは画像入力に対応していません。約2.59GBの「gemma-4-E2B-it.litertlm」に入れ替えてください。",
                    t,
                )
            }
            throw t
        }
    }

    private fun loadInternal() {
        close()
        logger.i("Loading LiteRT-LM model: ${modelFile.absolutePath}")

        val gpuResult = runCatching {
            Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                    cacheDir = context.cacheDir.absolutePath,
                )
            ).also { it.initialize() }
        }

        engine = gpuResult.getOrElse { gpuError ->
            logger.w("GPU initialization failed, falling back to CPU: ${gpuError.message}")
            Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    cacheDir = context.cacheDir.absolutePath,
                )
            ).also { it.initialize() }
        }

        val current = engine ?: error("Gemma 4エンジンを初期化できませんでした")

        runCatching {
            current.createConversation(
                ConversationConfig(
                    maxOutputToken = 8,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                )
            ).use { }
        }.getOrElse { validationError ->
            close()
            throw validationError
        }

        logger.i("Gemma 4 multimodal engine initialized and conversation validated")
    }

    suspend fun analyze(imageFile: File, targetKb: Int): String = withContext(Dispatchers.IO) {
        val current = engine ?: error("Gemma 4モデルが読み込まれていません")
        val maxTokens = when (targetKb) {
            1 -> 420
            2 -> 700
            else -> 1000
        }
        val detailHint = when (targetKb) {
            1 -> "Be compact, but still describe every visible person and distinctive accessory."
            2 -> "Use medium-high detail. Prioritize people, clothing, pose, accessories and camera geometry."
            else -> "Use high detail. Preserve reconstruction-critical visible details even if the JSON becomes longer."
        }

        val prompt = """
            Analyze this image as a reconstruction record, not as a caption. Return ONLY one valid compact JSON object, no markdown.
            Do not identify or guess the names of real people. Describe only visible appearance.

            Use this schema exactly:
            {
              "s":"scene summary",
              "cam":{"shot":"","angle":"","view":"","fov":""},
              "p":[
                {
                  "b":[x,y,w,h],
                  "hb":[x,y,w,h],
                  "age":"apparent age group",
                  "build":"body/build description",
                  "hair":"hair description",
                  "face":"visible facial features/facial hair",
                  "cl":["garment with color/material/detail"],
                  "acc":["hat/glasses/tie/jewelry/bag/etc"],
                  "pose":"body posture plus exact arm/hand positions",
                  "expr":"visible expression",
                  "dir":"body/head facing direction"
                }
              ],
              "o":[{"t":"object","b":[x,y,w,h],"a":"appearance/detail"}],
              "bg":["background structure/detail"],
              "c":["#RRGGBB"],
              "l":"lighting, shadows, time/atmosphere",
              "txt":[{"v":"visible text exactly if legible","b":[x,y,w,h]}]
            }

            All b/hb coordinates are integer percentages 0-100: x,y,width,height relative to the full image.
            For every person, hb is the visible head including hair and headwear. Keep people in left-to-right order.
            Describe distinctive reconstruction-critical details explicitly: apparent age group, build, hairstyle, facial hair, eyewear, hats and their colors/text, jacket/shirt/suit/tie colors and materials, logos or visible text, hand positions, stance/walking state, overlap and spacing.
            Record camera framing and angle carefully. Record major background geometry and dominant colors.
            OCR short visible text when legible. Do not invent unreadable text or hidden details.
            ${detailHint}
        """.trimIndent()

        logger.i("Running Gemma 4 detailed semantic analysis target=${targetKb}KB maxTokens=$maxTokens")
        try {
            val response = current.createConversation(
                ConversationConfig(
                    maxOutputToken = maxTokens,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                )
            ).use { conversation ->
                conversation.sendMessage(
                    Contents.of(
                        Content.ImageFile(imageFile.absolutePath),
                        Content.Text(prompt),
                    )
                )
            }
            val text = response.toString().trim()
            logger.i("Gemma 4 detailed semantic analysis complete: ${text.toByteArray().size} bytes")
            text
        } catch (t: Throwable) {
            if (isVisionEncoderMissing(t)) {
                logger.e("Vision encoder missing during analysis", t)
                throw IllegalStateException(
                    "このモデルには画像エンコーダがありません。約2.59GBの「gemma-4-E2B-it.litertlm」に入れ替えてください。",
                    t,
                )
            }
            throw t
        }
    }

    private fun isVisionEncoderMissing(t: Throwable): Boolean {
        var current: Throwable? = t
        while (current != null) {
            val message = current.message.orEmpty()
            if (
                message.contains("TF_LITE_VISION_ENCODER", ignoreCase = true) ||
                message.contains("vision encoder", ignoreCase = true) && message.contains("not found", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    fun close() {
        val current = engine ?: return
        runCatching { current.close() }
            .onFailure { logger.w("Engine close failed: ${it.message}") }
        engine = null
    }
}
