package app.timiao.et.share

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.math.floor
import kotlin.math.min

/** Raw QR module matrix. `true` means a dark module. */
class QrModules internal constructor(
    val width: Int,
    val height: Int,
    private val cells: BooleanArray,
) {
    fun isDark(x: Int, y: Int): Boolean = cells[y * width + x]
}

/**
 * QR encoder/backed by ZXing core only (about 0.5 MB; no Play Services).
 * Rendering is done on a Compose Canvas, so the app does not pull in an image library.
 */
object QrCode {
    private const val QUIET_ZONE = 2

    fun modules(text: String): QrModules? {
        if (text.length > ShareCodec.MAX_QR_CHARS) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 0,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            )
            val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, hints).matrix
            val width = matrix.width
            val height = matrix.height
            val cells = BooleanArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    cells[y * width + x] = matrix.get(x, y).toInt() == 1
                }
            }
            QrModules(width, height, cells)
        }.getOrNull()
    }

    /**
     * QR codes always use dark modules on a white field, in both themes. Scanners need
     * that contrast; tinting the code with theme colours breaks some decoders.
     */
    @Composable
    fun QrCanvas(text: String, modifier: Modifier = Modifier) {
        val modules = modules(text)
        Canvas(modifier) {
            drawRect(Color.White)
            if (modules == null) return@Canvas
            val total = modules.width + QUIET_ZONE * 2
            val cell = floor(min(size.width, size.height) / total)
            if (cell < 1f) return@Canvas
            val originX = (size.width - total * cell) / 2f
            val originY = (size.height - total * cell) / 2f
            val dark = Color(0xFF141414)
            for (y in 0 until modules.height) {
                for (x in 0 until modules.width) {
                    if (!modules.isDark(x, y)) continue
                    drawRect(
                        color = dark,
                        topLeft = Offset(originX + (x + QUIET_ZONE) * cell, originY + (y + QUIET_ZONE) * cell),
                        // Half-pixel bleed prevents hairline seams between modules.
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}
