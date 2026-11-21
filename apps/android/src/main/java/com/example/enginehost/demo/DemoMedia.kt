package com.example.enginehost.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
