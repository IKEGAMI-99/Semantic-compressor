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

        // 画像エンコーダを持たない軽量GPU版でもEngine.initialize()までは成功する場合がある。
        // createConversation()時点でTF_LITE_VISION_ENCODER欠落が判明するため、ロード直後に検証する。
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

    suspend fun analyze(imageFile: File): String = withContext(Dispatchers.IO) {
        val current = engine ?: error("Gemma 4モデルが読み込まれていません")
        val prompt = """
            Analyze this photo for semantic image compression. Return ONLY one compact JSON object, no markdown.
            Schema: {"s":"short scene summary","o":[["label",x,y,w,h]],"c":["#RRGGBB"],"l":"lighting/style"}
            Coordinates are integers 0-100 relative to image width/height. Keep at most 6 important objects and 5 colors.
            Preserve composition, object positions, dominant colors, lighting and camera viewpoint. Do not invent hidden details.
            Keep the full JSON under 700 UTF-8 bytes if possible.
        """.trimIndent()

        logger.i("Running Gemma 4 semantic analysis")
        try {
            val response = current.createConversation(
                ConversationConfig(
                    maxOutputToken = 256,
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
            logger.i("Gemma 4 semantic analysis complete: ${text.toByteArray().size} bytes")
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
