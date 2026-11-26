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
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * QR / link / file scanner.
 *
 * Camera scanning decodes the CameraX Y plane with ZXing core (Apache-2.0), so no
 * closed-source ML Kit model or Google telemetry is bundled. When there is no camera
 * or no permission the screen still works: pick an image from the gallery or paste a
 * share link.
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
    val executor = remember { Executors.newSingleThreadExecutor() }
    val decodeDone = remember { AtomicBoolean(false) }
    val gotFrame = remember { AtomicBoolean(false) }
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
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                gotFrame.set(true)
                try {
                    if (decodeDone.get()) return@setAnalyzer
                    val raw = decodeProxy(proxy)
                    if (raw != null && decodeDone.compareAndSet(false, true)) {
                        runCatching { analysis.clearAnalyzer() }
                        watchdog.post { currentOnQr(raw) }
                    }
                } finally {
                    proxy.close()
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
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Copies the Y plane of a CameraX frame and decodes it as a QR code. */
private fun decodeProxy(proxy: ImageProxy): String? {
    val plane = proxy.planes.firstOrNull() ?: return null
    val width = proxy.width
    val height = proxy.height
    if (width <= 0 || height <= 0) return null
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    if (rowStride < width) return null
    val rotation = proxy.imageInfo.rotationDegrees
    return if (pixelStride == 1) {
        val y = ByteArray(rowStride * height)
        val buffer = plane.buffer.duplicate()
        buffer.position(0)
        val count = minOf(buffer.remaining(), y.size)
        if (count > 0) buffer.get(y, 0, count)
        QrDecoder.decodeYuv(y, width, height, rowStride, rotation)
    } else {
        val y = ByteArray(width * height)
        val buffer = plane.buffer
        for (row in 0 until height) {
            val base = row * rowStride
            for (col in 0 until width) {
                val index = base + col * pixelStride
                if (index >= buffer.limit()) return null
                y[row * width + col] = buffer.get(index)
            }
        }
        QrDecoder.decodeYuv(y, width, height, width, rotation)
    }
}

/** ZXing-only QR decoding shared by the camera and gallery paths. */
internal object QrDecoder {
    private val HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /** Gallery path: ARGB pixels already in memory. */
    fun decodePixels(pixels: IntArray, width: Int, height: Int): String? =
        decodeWithInversion(RGBLuminanceSource(width, height, pixels))

    /**
     * Camera path: [y] is the luma plane; [rowStride] can be larger than [width].
     * The central viewfinder area is cropped first, then the frame is rotated upright
     * according to [rotationDegrees].
     */
    fun decodeYuv(
        y: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        rotationDegrees: Int,
    ): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        if (y.size < rowStride.toLong() * height) return null
        val side = minOf(width, height)
        val crop = (side * 0.88f).toInt().coerceAtLeast(32).coerceAtMost(side)
        val left = (width - crop) / 2
        val top = (height - crop) / 2
        val turns = (((rotationDegrees % 360) + 360) % 360) / 90
        // PlanarYUVLuminanceSource cannot rotate; crop to a square, rotate that, then decode.
        val source: LuminanceSource = if (turns == 0) {
            PlanarYUVLuminanceSource(y, rowStride, height, left, top, crop, crop, false)
        } else {
            val square = ByteArray(crop * crop)
            for (row in 0 until crop) {
                System.arraycopy(y, (top + row) * rowStride + left, square, row * crop, crop)
            }
            var rotated = square
            repeat(turns) { rotated = rotateCounterClockwise(rotated, crop) }
            PlanarYUVLuminanceSource(rotated, crop, crop, 0, 0, crop, crop, false)
        }
        return decodeWithInversion(source)
    }

    /** Rotates a square 8-bit luminance buffer 90° counter-clockwise. */
    private fun rotateCounterClockwise(src: ByteArray, n: Int): ByteArray {
        val out = ByteArray(n * n)
        for (row in 0 until n) {
            val srcBase = row * n
            for (col in 0 until n) {
                // src(col,row) -> dst(x'=row, y'=n-1-col)
                out[(n - 1 - col) * n + row] = src[srcBase + col]
            }
        }
        return out
    }

    private fun decodeWithInversion(source: LuminanceSource): String? {
        // Some screens show inverted codes; try both polarities. The inverted pass only
        // runs when the normal one fails.
        for (candidate in arrayOf(source, InvertedLuminanceSource(source))) {
            val reader = MultiFormatReader()
            try {
                reader.setHints(HINTS)
                val text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(candidate)))?.text
                if (!text.isNullOrBlank()) return text
            } catch (ignored: Exception) {
                // NotFoundException is the common case: keep looking.
            } finally {
                reader.reset()
            }
        }
        return null
    }
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
        QrDecoder.decodePixels(pixels, width, height)
    } finally {
        bitmap.recycle()
    }
}
