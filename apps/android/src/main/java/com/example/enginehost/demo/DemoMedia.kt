package com.example.enginehost.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import engine.db.currentAndroidContext
import java.io.File

/**
 * Media fixtures for the demo cards.
 *
 * The image tools do real pixel work and refuse to run without a decodable source, so the demo
 * needs a concrete bitmap rather than a placeholder URI. It is generated once per tenant into
 * that tenant's media sandbox and reused, so the demo works offline on any device. The image
 * carries legible text and a high-contrast marker so OCR and segmentation both return real
 * results instead of empty output.
 */
object DemoMedia {

    private const val FILE_NAME = "demo_source.png"

    /** 1x1 PNG used only when no Android context exists (plain-JVM tests). */
    private const val FALLBACK_DATA_URL =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    fun mediaDir(tenantId: String): File? {
        val context = currentAndroidContext?.invoke() ?: return null
        return File(context.filesDir, "workspaces/$tenantId/media").apply { mkdirs() }
    }

    /** `file://` URI of the generated sample image, or a data URL when no context exists. */
    fun sampleImageUri(tenantId: String): String {
        val dir = mediaDir(tenantId) ?: return FALLBACK_DATA_URL
        val file = File(dir, FILE_NAME)
        if (!file.exists() || file.length() == 0L) {
            runCatching { writeSampleImage(file) }
        }
        return if (file.exists() && file.length() > 0L) {
            "file://${file.absolutePath}"
        } else {
            FALLBACK_DATA_URL
        }
    }

    private const val BARCODE_FILE_NAME = "demo_barcode.png"
    private const val QR_FILE_NAME = "demo_qr.png"

    /** `file://` URI of a real EAN-13 barcode bitmap, or null when no Android context exists. */
    fun barcodeImageUri(tenantId: String): String? =
        symbologyUri(tenantId, BARCODE_FILE_NAME, BARCODE_VALUE, BarcodeFormat.EAN_13, 480, 240)

    /** `file://` URI of a real QR bitmap, or null when no Android context exists. */
    fun qrImageUri(tenantId: String): String? =
        symbologyUri(tenantId, QR_FILE_NAME, QR_VALUE, BarcodeFormat.QR_CODE, 480, 480)

    private fun symbologyUri(
        tenantId: String,
        fileName: String,
        content: String,
        format: BarcodeFormat,
        width: Int,
        height: Int
    ): String? {
        val dir = mediaDir(tenantId) ?: return null
        val file = File(dir, fileName)
        if (!file.exists() || file.length() == 0L) {
            runCatching { writeSymbology(file, content, format, width, height) }
        }
        return if (file.exists() && file.length() > 0L) "file://${file.absolutePath}" else null
    }

    /**
     * Encodes [content] into real pixels so the scanner decodes genuine symbology. The demo
     * never injects a `simulated_code`: the card has to produce what a camera frame would.
     */
    private fun writeSymbology(
        file: File,
        content: String,
        format: BarcodeFormat,
        width: Int,
        height: Int
    ) {
        val hints = mapOf(EncodeHintType.MARGIN to 2)
        val matrix = MultiFormatWriter().encode(content, format, width, height, hints)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        try {
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    /** Payload carried by the demo barcode (EAN-13 requires 12 digits + checksum). */
    const val BARCODE_VALUE = "9780201633610"

    /** Payload carried by the demo QR code. */
    const val QR_VALUE = "apexpos://order/pickup?terminal=POS-TERM-01"

    private fun writeSampleImage(file: File) {
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(235, 235, 240))
            canvas.drawCircle(
                480f, 120f, 80f, Paint().apply {
                    color = Color.rgb(180, 60, 40)
                    isAntiAlias = true
                }
            )
            val textPaint = Paint().apply {
                color = Color.rgb(20, 20, 30)
                textSize = 56f
                isAntiAlias = true
            }
            canvas.drawText("APEX POS", 48f, 150f, textPaint)
            canvas.drawText("ORDER #4821", 48f, 240f, textPaint)
            canvas.drawText("TOTAL 12.50", 48f, 330f, textPaint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
