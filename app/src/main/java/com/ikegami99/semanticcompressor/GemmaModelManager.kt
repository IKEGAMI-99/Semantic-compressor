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
        } ?: error("Could not open selected model")
        if (temp.length() < 10_000_000) {
            temp.delete()
            error("Selected file is too small to be a LiteRT-LM model")
        }
        if (modelFile.exists()) modelFile.delete()
        check(temp.renameTo(modelFile)) { "Could not finalize imported model" }
        logger.i("Model imported: ${modelFile.length()} bytes")
        loadInternal()
    }

    suspend fun loadSaved() = withContext(Dispatchers.IO) {
        check(hasSavedModel()) { "No saved Gemma 4 model found" }
        loadInternal()
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
        logger.i("Gemma 4 engine initialized")
    }

    suspend fun analyze(imageFile: File): String = withContext(Dispatchers.IO) {
        val current = engine ?: error("Gemma 4 model is not loaded")
        val prompt = """
            Analyze this photo for semantic image compression. Return ONLY one compact JSON object, no markdown.
            Schema: {"s":"short scene summary","o":[["label",x,y,w,h]],"c":["#RRGGBB"],"l":"lighting/style"}
            Coordinates are integers 0-100 relative to image width/height. Keep at most 6 important objects and 5 colors.
            Preserve composition, object positions, dominant colors, lighting and camera viewpoint. Do not invent hidden details.
            Keep the full JSON under 700 UTF-8 bytes if possible.
        """.trimIndent()

        logger.i("Running Gemma 4 semantic analysis")
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
    }

    fun close() {
        val current = engine ?: return
        runCatching { current.close() }
            .onFailure { logger.w("Engine close failed: ${it.message}") }
        engine = null
    }
}
