package com.ikegami99.semanticcompressor

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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

    // 2.01GBのGPU版は軽量化のためVision Encoderを含まない構成があるため使用しない。
    // 公式の2.59GBマルチモーダル版を推奨する。
    private val gemmaE2bMultimodalUrl =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"
    private val gemmaE2bModelListUrl =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main"

    private val simgDecodePrompt = """
        添付した .simg はAI再構成用のセマンティック圧縮画像です。
        ZIP互換ファイルとして内容を読み取り、d.txt の復号指示に従ってください。
        p.webp を構図・色・位置関係の視覚参照として、m.json を被写体・配置・色・照明などの意味情報として使用してください。
        元写真に意味的・構図的に近い高解像度画像を再構成してください。
        記録されていない主要な物体は追加せず、低解像度で失われた細部だけを自然に補完してください。
        これは元ピクセルの完全復元ではなく、.simg に保存された情報からの意味的再生成です。
    """.trimIndent()

    private var busy by mutableStateOf(false)
    private var status by mutableStateOf("Gemma 4モデルが読み込まれていません")
    private var modelLoaded by mutableStateOf(false)
    private var selectedPhotoUri by mutableStateOf<Uri?>(null)
    private var targetKb by mutableStateOf(2)
    private var resultFile by mutableStateOf<File?>(null)
    private var resultDescription by mutableStateOf("")
    private var updateStatus by mutableStateOf("未確認")
    private var releaseInfo by mutableStateOf<UpdateManager.ReleaseInfo?>(null)
    private var downloadedUpdate by mutableStateOf<File?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger = AppLogger(this)
        modelManager = GemmaModelManager(this, logger)
        compressor = SemanticCompressor(this, modelManager, logger)
        updateManager = UpdateManager(this, logger)
        logger.i("アプリ起動 version=${BuildConfig.VERSION_NAME}")

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SemanticCompressorScreen()
                }
            }
        }

        handleIncomingImage(intent)

        if (modelManager.hasSavedModel()) {
            lifecycleScope.launch {
                busy = true
                status = "保存済みのGemma 4モデルを確認しています…"
                runCatching { modelManager.loadSaved() }
                    .onSuccess {
                        modelLoaded = true
                        status = "Gemma 4準備完了（${humanBytes(modelManager.savedModelSizeBytes())}）"
                    }
                    .onFailure {
                        modelLoaded = false
                        logger.e("保存済みモデルの読み込みに失敗", it)
                        status = it.message ?: "モデルの読み込みに失敗しました"
                    }
                busy = false
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingImage(intent)
    }

    @Composable
    private fun SemanticCompressorScreen() {
        val modelPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) importModel(uri)
        }

        val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            selectedPhotoUri = uri
            resultFile = null
            resultDescription = ""
            if (uri != null) {
                status = if (modelLoaded) {
                    "写真を選択しました。圧縮できます。"
                } else {
                    "写真を選択しました。先にGemma 4を読み込んでください。"
                }
                logger.i("フォトピッカーから写真を選択: $uri")
            }
        }

        val logExporter = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    runCatching { logger.exportTo(uri) }
                        .onSuccess { bytes -> status = "ログを書き出しました（${humanBytes(bytes)}）" }
                        .onFailure {
                            logger.e("ログの書き出しに失敗", it)
                            status = "ログの書き出しに失敗しました: ${it.message}"
                        }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Semantic Compressor", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Gemma 4が写真を端末内で解析し、AIが再構成するために必要な最小限の画像情報と意味情報だけを保存します。",
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
                        if (modelLoaded) {
                            "画像対応モデルを読み込み済み"
                        } else {
                            "画像入力に対応したGemma 4の .litertlm モデルを読み込みます。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Text(
                        "推奨: gemma-4-E2B-it.litertlm（約2.59GB）。2.01GBのGPU版は画像エンコーダが無い場合があるため使用しません。",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    OutlinedButton(
                        onClick = { openUrl(gemmaE2bMultimodalUrl) },
                        enabled = !busy,
                    ) {
                        Text("画像対応 Gemma 4 E2Bをダウンロード")
                    }

                    OutlinedButton(
                        onClick = { openUrl(gemmaE2bModelListUrl) },
                        enabled = !busy,
                    ) {
                        Text("Gemma 4 E2Bのモデル一覧を開く")
                    }

                    Button(
                        onClick = { modelPicker.launch(arrayOf("*/*")) },
                        enabled = !busy,
                    ) {
                        Text(if (modelLoaded) "モデルを入れ替える" else ".litertlmモデルを選択")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("2. 写真", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (selectedPhotoUri == null) "写真が選択されていません" else "写真を選択済み",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "ボタンを押すとAndroidの写真ギャラリー（フォトピッカー）が開きます。共有メニューから直接送ることもできます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = !busy,
                    ) { Text("ギャラリーから写真を選択") }

                    Text("目標ファイルサイズ", style = MaterialTheme.typography.labelLarge)
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
                    ) { Text("セマンティック圧縮") }

                    if (resultFile != null) {
                        HorizontalDivider()
                        Text(resultDescription, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "共有時にAI向けの復号プロンプトも自動で添付します。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { shareFile(resultFile!!) }) {
                            Text(".simgをChatGPT等へ共有")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("ログ", style = MaterialTheme.typography.titleMedium)
                    Text("モデル読み込み、推論、圧縮サイズ、アップデート処理などを端末内に記録します。")
                    OutlinedButton(
                        onClick = { logExporter.launch("semantic-compressor-log.txt") },
                        enabled = !busy,
                    ) { Text("ログを書き出す") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("アプリのアップデート", style = MaterialTheme.typography.titleMedium)
                    Text("現在: v${BuildConfig.VERSION_NAME}  •  $updateStatus")
                    OutlinedButton(onClick = { checkForUpdate() }, enabled = !busy) {
                        Text("最新版を確認")
                    }
                    releaseInfo?.let { release ->
                        Button(onClick = { downloadUpdate(release) }, enabled = !busy) {
                            Text("v${release.version}をダウンロード")
                        }
                    }
                    downloadedUpdate?.let { apk ->
                        Button(onClick = {
                            if (!updateManager.install(apk)) {
                                updateStatus = "このアプリからのインストールを許可して、もう一度タップしてください"
                            }
                        }) {
                            Text("ダウンロードした更新をインストール")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                ".simgは意味を優先した非可逆圧縮です。元写真のピクセルを完全に復元するものではありません。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            logger.i("外部リンクを開く: $url")
        }.onFailure {
            logger.e("外部リンクを開けませんでした", it)
            status = "ブラウザを開けませんでした: ${it.message}"
        }
    }

    private fun handleIncomingImage(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND) return
        if (incoming.type?.startsWith("image/") != true) return

        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            incoming.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        if (uri != null) {
            selectedPhotoUri = uri
            resultFile = null
            resultDescription = ""
            status = if (modelLoaded) {
                "共有された写真を受け取りました。圧縮できます。"
            } else {
                "共有された写真を受け取りました。先にGemma 4を読み込んでください。"
            }
            logger.i("共有から写真を受信: $uri")
        }
    }

    private fun importModel(uri: Uri) {
        lifecycleScope.launch {
            busy = true
            modelLoaded = false
            status = "Gemma 4モデルを取り込んでいます… 数GBのコピーなので時間がかかります"
            runCatching { modelManager.importAndLoad(uri) }
                .onSuccess {
                    modelLoaded = true
                    status = "Gemma 4準備完了（${humanBytes(modelManager.savedModelSizeBytes())}）"
                }
                .onFailure {
                    logger.e("モデルの取り込み・読み込みに失敗", it)
                    status = it.message ?: "モデルエラー"
                }
            busy = false
        }
    }

    private fun compressSelectedPhoto() {
        val uri = selectedPhotoUri ?: return
        lifecycleScope.launch {
            busy = true
            status = "Gemma 4が写真を解析しています…"
            runCatching { compressor.compress(uri, targetKb) }
                .onSuccess { result ->
                    resultFile = result.file
                    resultDescription =
                        "${result.sizeBytes} バイト（${String.format("%.2f", result.sizeBytes / 1024.0)} KB） • プレビュー ${result.previewPixels}px • WebP 品質 ${result.previewQuality}"
                    status = if (result.sizeBytes <= targetKb * 1024) {
                        "目標サイズに収まりました"
                    } else {
                        "最小化しましたが ${targetKb} KBを超えました"
                    }
                }
                .onFailure {
                    logger.e("圧縮に失敗", it)
                    val message = it.message.orEmpty()
                    if (message.contains("画像エンコーダ") || message.contains("TF_LITE_VISION_ENCODER", ignoreCase = true)) {
                        modelLoaded = false
                        status = "このモデルには画像エンコーダがありません。上の『画像対応 Gemma 4 E2B』をダウンロードして入れ替えてください。"
                    } else {
                        status = "圧縮に失敗しました: ${it.message}"
                    }
                }
            busy = false
        }
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            busy = true
            updateStatus = "確認中…"
            releaseInfo = null
            downloadedUpdate = null
            runCatching { updateManager.checkLatest() }
                .onSuccess { release ->
                    releaseInfo = release
                    updateStatus = if (release == null) {
                        "新しいAPKはありません"
                    } else {
                        "v${release.version}が利用できます"
                    }
                }
                .onFailure {
                    logger.e("アップデート確認に失敗", it)
                    updateStatus = "確認に失敗しました: ${it.message}"
                }
            busy = false
        }
    }

    private fun downloadUpdate(release: UpdateManager.ReleaseInfo) {
        lifecycleScope.launch {
            busy = true
            updateStatus = "v${release.version}をダウンロード中…"
            runCatching { updateManager.download(release) }
                .onSuccess {
                    downloadedUpdate = it
                    updateStatus = "v${release.version}をダウンロードしました"
                }
                .onFailure {
                    logger.e("アップデートのダウンロードに失敗", it)
                    updateStatus = "ダウンロードに失敗しました: ${it.message}"
                }
            busy = false
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, simgDecodePrompt)
            putExtra(Intent.EXTRA_SUBJECT, "Semantic Compressor .simg 復号")
            clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "ChatGPTなどへ共有"))
        logger.i(".simgを復号プロンプト付きで共有: ${file.name} ${file.length()} bytes")
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    override fun onDestroy() {
        Thread { modelManager.close() }.start()
        super.onDestroy()
    }
}
