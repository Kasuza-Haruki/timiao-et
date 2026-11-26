package app.timiao.et.ui.screens

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises the ZXing camera/gallery decode path without Android or a camera. */
class QrDecoderTest {

    private val payload = "timiao://import?v=1&d=QrDecoderTest-0123456789-abcdef"

    private val size = 320
    private val qrSize = 240
    private val margin = (size - qrSize) / 2

    /** White background, dark modules: one row per ByteArray. */
    private fun grayscaleRows(): Array<ByteArray> {
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            qrSize,
            qrSize,
            mapOf(EncodeHintType.MARGIN to 2),
        )
        val rows = Array(size) { ByteArray(size) { 0xFF.toByte() } }
        for (y in 0 until qrSize) {
            for (x in 0 until qrSize) {
                if (matrix.get(x, y)) rows[margin + y][margin + x] = 0
            }
        }
        return rows
    }

    private fun rotateClockwise(rows: Array<ByteArray>): Array<ByteArray> {
        // dest[y'][x'] = src[y][x] with x' = size - 1 - y, y' = x.
        val out = Array(size) { ByteArray(size) }
        for (y in 0 until size) {
            for (x in 0 until size) {
                out[x][size - 1 - y] = rows[y][x]
            }
        }
        return out
    }

    @Test
    fun decodesPaddedRowStride() {
        val rows = grayscaleRows()
        val padding = 37
        val rowStride = size + padding
        // Grey padding: if the decoder wrongly treats stride as width, it will skew.
        val y = ByteArray(rowStride * size) { 0x7F }
        for (row in 0 until size) {
            rows[row].copyInto(y, row * rowStride)
        }
        assertEquals(payload, QrDecoder.decodeYuv(y, size, size, rowStride, 0))
    }

    @Test
    fun decodesRotatedQuarterTurn() {
        val rotated = rotateClockwise(grayscaleRows())
        val y = ByteArray(size * size)
        for (row in 0 until size) {
            rotated[row].copyInto(y, row * size)
        }
        assertEquals(payload, QrDecoder.decodeYuv(y, size, size, size, 90))
    }

    @Test
    fun decodesGalleryPixels() {
        val rows = grayscaleRows()
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = rows[y][x].toInt() and 0xFF
                pixels[y * size + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        assertEquals(payload, QrDecoder.decodePixels(pixels, size, size))
    }
}
