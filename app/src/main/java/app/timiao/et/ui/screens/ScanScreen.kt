package app.timiao.et.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.timiao.et.R
import app.timiao.et.share.ImportSink
import app.timiao.et.share.ShareCodec
import app.timiao.et.ui.components.DockInset
import app.timiao.et.ui.components.EmptyGlassCard
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.GlassTextField
import app.timiao.et.ui.components.PillButton
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.theme.LocalGlassColors
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * QR / link / file scanner.
 *
 * Camera scanning uses the bundled ML Kit barcode model (no Play Services needed).
 * When there is no camera or no permission the screen still works: pick an image
 * from the gallery or paste a share link.
 */
@Composable
fun ScanScreen(
    importSink: ImportSink,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = LocalGlassColors.current
    val hasCamera = remember(context) {
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val hasIds = runCatching {
            cameraManager?.cameraIdList?.isNotEmpty() == true
        }.getOrDefault(false)
        hasIds || context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var status by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var missingSecretToml by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (!granted) status = context.getString(R.string.scan_no_permission)
    }

    fun performImport(toml: String, hasSecret: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                importSink.import(toml, hasSecret)
                success = true
                status = if (hasSecret) {
                    context.getString(R.string.scan_success)
                } else {
                    context.getString(R.string.scan_success_no_secret)
                }
            } catch (e: Exception) {
                success = false
                status = context.getString(R.string.scan_failed, e.message ?: context.getString(R.string.scan_unknown_error))
            } finally {
                busy = false
            }
        }
    }

    fun submit(raw: String) {
        if (raw.isBlank()) return
        when (val decoded = ShareCodec.parseImportText(raw)) {
            is ShareCodec.DecodeResult.Invalid -> {
                success = false
                status = ShareCodec.message(context, decoded)
            }
            is ShareCodec.DecodeResult.Success -> {
                if (decoded.hasSecret) {
                    performImport(decoded.toml, true)
                } else {
                    missingSecretToml = decoded.toml
                }
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = context.getString(R.string.scan_recognizing)
            success = false
            val raw = decodeQrImage(context, uri)
            if (raw == null) {
                status = context.getString(R.string.scan_no_qr)
            } else {
                submit(raw)
            }
        }
    }

    if (missingSecretToml != null) {
        AlertDialog(
            onDismissRequest = { missingSecretToml = null },
            title = { Text(stringResource(R.string.scan_missing_secret_title)) },
            text = {
                Text(stringResource(R.string.scan_missing_secret_text))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toml = missingSecretToml
                        missingSecretToml = null
                        if (toml != null) performImport(toml, false)
                    },
                ) { Text(stringResource(R.string.scan_import_anyway), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { missingSecretToml = null }) {
                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.scan_title),
                subtitle = stringResource(R.string.scan_subtitle),
                onBack = onBack,
            )
        }

        if (status != null) {
            item {
                val color = if (success) g.good else MaterialTheme.colorScheme.error
                Text(
                    text = status ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(16.dp), GlassLevel.Subtle)
                        .padding(12.dp),
                )
            }
        }

        item {
            if (hasCamera && cameraGranted && !cameraFailed) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .glass(RoundedCornerShape(24.dp), GlassLevel.Regular),
                ) {
                    CameraPreview(
                        onQr = { raw ->
                            if (!busy && !success && missingSecretToml == null) submit(raw)
                        },
                        onUnavailable = { cameraFailed = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                    ScanFrame(modifier = Modifier.fillMaxSize())
                }
            } else {
                EmptyGlassCard(
                    text = when {
                        cameraFailed -> stringResource(R.string.scan_camera_failed)
                        !hasCamera -> stringResource(R.string.scan_no_camera)
                        else -> stringResource(R.string.scan_camera_not_granted)
                    },
                    actionText = if (hasCamera && !cameraGranted) stringResource(R.string.scan_grant_camera) else null,
                    onAction = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                )
            }
        }

        item {
            Text(
                text = if (hasCamera && cameraGranted && !cameraFailed) {
                    stringResource(R.string.scan_hint_camera)
                } else {
                    stringResource(R.string.scan_hint_fallback)
                },
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        item {
            PillButton(
                text = stringResource(R.string.scan_pick_image),
                onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                secondary = true,
            )
        }

        item {
            GlassTextField(
                value = pasted,
                onValueChange = { pasted = it },
                label = stringResource(R.string.scan_paste_label),
                placeholder = "timiao://import?v=1&d=…",
                singleLine = false,
                minLines = 2,
                maxLines = 5,
                mono = true,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    text = if (busy) stringResource(R.string.scan_importing) else stringResource(R.string.action_import),
                    onClick = { submit(pasted) },
                    enabled = !busy && pasted.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = stringResource(R.string.scan_clear),
                    onClick = {
                        pasted = ""
                        status = null
                        success = false
                    },
                    modifier = Modifier.weight(1f),
                    secondary = true,
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.scan_footer),
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun CameraPreview(
    onQr: (String) -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnQr by rememberUpdatedState(onQr)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val scanner = remember { BarcodeScanning.getClient() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val lastScannedAt = remember { AtomicLong(0) }
    val gotFrame = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val watchdog = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val provider = runCatching { future.get() }.getOrElse {
                currentOnUnavailable()
                return@Runnable
            }
            val hasBackCamera = runCatching {
                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            }.getOrDefault(false)
            if (!hasBackCamera) {
                currentOnUnavailable()
                return@Runnable
            }
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                gotFrame.set(true)
                processProxy(proxy, scanner) { raw ->
                    val now = System.currentTimeMillis()
                    val previous = lastScannedAt.get()
                    if (now - previous < 1_200) return@processProxy
                    if (lastScannedAt.compareAndSet(previous, now)) currentOnQr(raw)
                }
            }
            val bound = runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
            if (bound.isFailure) {
                currentOnUnavailable()
                return@Runnable
            }
            // Some devices report a camera but never deliver frames (broken HAL or
            // emulator without a working camera). Fall back instead of showing black.
            watchdog.postDelayed({
                if (!gotFrame.get()) currentOnUnavailable()
            }, 7_000)
        }
        future.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            watchdog.removeCallbacksAndMessages(null)
            runCatching { future.get().unbindAll() }
            runCatching { scanner.close() }
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

@OptIn(ExperimentalGetImage::class)
private fun processProxy(
    proxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onRaw: (String) -> Unit,
) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull {
                it.format == Barcode.FORMAT_QR_CODE && !it.rawValue.isNullOrBlank()
            }?.rawValue?.let(onRaw)
        }
        .addOnCompleteListener { proxy.close() }
}

@Composable
private fun ScanFrame(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val side = minOf(size.width, size.height) * 0.62f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val arm = side * 0.16f
        val stroke = Stroke(width = 3.dp.toPx())
        val c = color.copy(alpha = 0.95f)
        // Four corner marks, no full border: keeps the preview visible.
        drawLine(c, Offset(left, top), Offset(left + arm, top), stroke.width)
        drawLine(c, Offset(left, top), Offset(left, top + arm), stroke.width)
        drawLine(c, Offset(left + side, top), Offset(left + side - arm, top), stroke.width)
        drawLine(c, Offset(left + side, top), Offset(left + side, top + arm), stroke.width)
        drawLine(c, Offset(left, top + side), Offset(left + arm, top + side), stroke.width)
        drawLine(c, Offset(left, top + side), Offset(left, top + side - arm), stroke.width)
        drawLine(c, Offset(left + side, top + side), Offset(left + side - arm, top + side), stroke.width)
        drawLine(c, Offset(left + side, top + side), Offset(left + side, top + side - arm), stroke.width)
    }
}

private suspend fun decodeQrImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    val bitmap = runCatching {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull() ?: return@withContext null
    if (bitmap.width * bitmap.height > 24_000_000) {
        bitmap.recycle()
        return@withContext null
    }
    try {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
        runCatching { reader.decodeWithState(binary)?.text }.getOrNull()
    } finally {
        bitmap.recycle()
    }
}
