package com.ikegami99.semanticcompressor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var logger: AppLogger
    private lateinit var modelManager: GemmaModelManager
    private lateinit var compressor: SemanticCompressor
    private lateinit var updateManager: UpdateManager

    private var busy by mutableStateOf(false)
    private var status by mutableStateOf("Gemma 4 model is not loaded")
    private var modelLoaded by mutableStateOf(false)
    private var selectedPhotoUri by mutableStateOf<Uri?>(null)
    private var targetKb by mutableStateOf(2)
    private var resultFile by mutableStateOf<File?>(null)
    private var resultDescription by mutableStateOf("")
    private var updateStatus by mutableStateOf("Not checked")
    private var releaseInfo by mutableStateOf<UpdateManager.ReleaseInfo?>(null)
    private var downloadedUpdate by mutableStateOf<File?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger = AppLogger(this)
        modelManager = GemmaModelManager(this, logger)
        compressor = SemanticCompressor(this, modelManager, logger)
        updateManager = UpdateManager(this, logger)
        logger.i("App started version=${BuildConfig.VERSION_NAME}")

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SemanticCompressorScreen()
                }
            }
        }

        if (modelManager.hasSavedModel()) {
            lifecycleScope.launch {
                busy = true
                status = "Loading saved Gemma 4 model…"
                runCatching { modelManager.loadSaved() }
                    .onSuccess {
                        modelLoaded = true
                        status = "Gemma 4 ready (${humanBytes(modelManager.savedModelSizeBytes())})"
                    }
                    .onFailure {
                        logger.e("Saved model load failed", it)
                        status = "Model load failed: ${it.message}"
                    }
                busy = false
            }
        }
    }

    @Composable
    private fun SemanticCompressorScreen() {
        val modelPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) importModel(uri)
        }
        val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            selectedPhotoUri = uri
            resultFile = null
            resultDescription = ""
            if (uri != null) logger.i("Photo selected: $uri")
        }
        val logExporter = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri ->
            if (uri != null) {
                runCatching { logger.exportTo(uri) }
                    .onSuccess { status = "Log exported" }
                    .onFailure { status = "Log export failed: ${it.message}" }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Semantic Compressor", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Gemma 4 analyzes the photo locally, then preserves only a tiny visual reference + semantics for AI reconstruction.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodySmall)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("1. Gemma 4", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (modelLoaded) "Model loaded" else "Import a Gemma 4 .litertlm model from device storage.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = { modelPicker.launch(arrayOf("*/*")) },
                        enabled = !busy,
                    ) {
                        Text(if (modelLoaded) "Replace model" else "Select .litertlm model")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("2. Photo", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (selectedPhotoUri == null) "No photo selected" else "Photo selected",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { imagePicker.launch(arrayOf("image/*")) },
                        enabled = !busy,
                    ) { Text("Choose photo") }

                    Text("Target size", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..3).forEach { kb ->
                            FilterChip(
                                selected = targetKb == kb,
                                onClick = { targetKb = kb },
                                label = { Text("$kb KB") },
                            )
                        }
                    }

                    Button(
                        onClick = { compressSelectedPhoto() },
                        enabled = !busy && modelLoaded && selectedPhotoUri != null,
                    ) { Text("Semantic Compress") }

                    if (resultFile != null) {
                        HorizontalDivider()
                        Text(resultDescription, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { shareFile(resultFile!!) }) {
                            Text("Share .simg")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Logs", style = MaterialTheme.typography.titleMedium)
                    Text("Model loading, inference, compression size and update events are recorded locally.")
                    OutlinedButton(
                        onClick = { logExporter.launch("semantic-compressor-log.txt") },
                        enabled = !busy,
                    ) { Text("Export log") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("App update", style = MaterialTheme.typography.titleMedium)
                    Text("Current: v${BuildConfig.VERSION_NAME}  •  $updateStatus")
                    OutlinedButton(onClick = { checkForUpdate() }, enabled = !busy) {
                        Text("Check GitHub Releases")
                    }
                    releaseInfo?.let { release ->
                        Button(onClick = { downloadUpdate(release) }, enabled = !busy) {
                            Text("Download v${release.version}")
                        }
                    }
                    downloadedUpdate?.let { apk ->
                        Button(onClick = {
                            if (!updateManager.install(apk)) {
                                updateStatus = "Allow app installs, then tap Install again"
                            }
                        }) {
                            Text("Install downloaded update")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                ".simg is lossy semantic compression. It cannot reproduce the original pixels exactly.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    private fun importModel(uri: Uri) {
        lifecycleScope.launch {
            busy = true
            modelLoaded = false
            status = "Importing Gemma 4 model… this is a multi-GB copy"
            runCatching { modelManager.importAndLoad(uri) }
                .onSuccess {
                    modelLoaded = true
                    status = "Gemma 4 ready (${humanBytes(modelManager.savedModelSizeBytes())})"
                }
                .onFailure {
                    logger.e("Model import/load failed", it)
                    status = "Model error: ${it.message}"
                }
            busy = false
        }
    }

    private fun compressSelectedPhoto() {
        val uri = selectedPhotoUri ?: return
        lifecycleScope.launch {
            busy = true
            status = "Gemma 4 is analyzing the photo…"
            runCatching { compressor.compress(uri, targetKb) }
                .onSuccess { result ->
                    resultFile = result.file
                    resultDescription =
                        "${result.sizeBytes} bytes (${String.format("%.2f", result.sizeBytes / 1024.0)} KB) • preview ${result.previewPixels}px • WebP q${result.previewQuality}"
                    status = if (result.sizeBytes <= targetKb * 1024) {
                        "Target reached"
                    } else {
                        "Best effort result exceeded ${targetKb} KB"
                    }
                }
                .onFailure {
                    logger.e("Compression failed", it)
                    status = "Compression failed: ${it.message}"
                }
            busy = false
        }
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            busy = true
            updateStatus = "Checking…"
            releaseInfo = null
            downloadedUpdate = null
            runCatching { updateManager.checkLatest() }
                .onSuccess { release ->
                    releaseInfo = release
                    updateStatus = if (release == null) "No newer APK release found" else "v${release.version} available"
                }
                .onFailure {
                    logger.e("Update check failed", it)
                    updateStatus = "Check failed: ${it.message}"
                }
            busy = false
        }
    }

    private fun downloadUpdate(release: UpdateManager.ReleaseInfo) {
        lifecycleScope.launch {
            busy = true
            updateStatus = "Downloading v${release.version}…"
            runCatching { updateManager.download(release) }
                .onSuccess {
                    downloadedUpdate = it
                    updateStatus = "Downloaded v${release.version}"
                }
                .onFailure {
                    logger.e("Update download failed", it)
                    updateStatus = "Download failed: ${it.message}"
                }
            busy = false
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share semantic image"))
        logger.i("Sharing .simg: ${file.name} ${file.length()} bytes")
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> "$bytes B"
    }

    override fun onDestroy() {
        Thread { modelManager.close() }.start()
        super.onDestroy()
    }
}
