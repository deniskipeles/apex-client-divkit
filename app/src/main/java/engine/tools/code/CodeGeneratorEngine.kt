package engine.tools.code

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import engine.db.currentAndroidContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * On-device 1D barcode / 2D matrix (QR, DataMatrix, Aztec, PDF417) renderer built
 * on ZXing's [MultiFormatWriter]. Produces a module matrix plus an optional PNG,
 * written into the tenant-isolated media sandbox. Pure JVM (no Play Services),
 * so it also runs headless in unit tests.
 */
object CodeGeneratorEngine {

    data class CodeResult(
        val uri: String?,
        val codeId: String,
        val content: String,
        val format: String,
        val kind: String,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
        val mimeType: String?,
        val base64: String?,
        val rowMatrix: List<String>
    )

    /** Maps a user-supplied format label to a ZXing symbology. */
    fun resolveFormat(raw: String?): BarcodeFormat {
        val f = raw?.trim()?.uppercase()?.replace('-', '_')?.replace(' ', '_').orEmpty()
        return when (f) {
            "", "AUTO", "BOTH", "ANY" -> BarcodeFormat.QR_CODE
            "QR", "QRCODE", "QR_CODE" -> BarcodeFormat.QR_CODE
            "DATA_MATRIX", "DATAMATRIX", "DM" -> BarcodeFormat.DATA_MATRIX
            "AZTEC" -> BarcodeFormat.AZTEC
            "PDF_417", "PDF417" -> BarcodeFormat.PDF_417
            "EAN_13", "EAN13" -> BarcodeFormat.EAN_13
            "EAN_8", "EAN8" -> BarcodeFormat.EAN_8
            "UPC_A", "UPCA" -> BarcodeFormat.UPC_A
            "UPC_E", "UPCE" -> BarcodeFormat.UPC_E
            "CODE_128", "CODE128" -> BarcodeFormat.CODE_128
            "CODE_39", "CODE39" -> BarcodeFormat.CODE_39
            "CODE_93", "CODE93" -> BarcodeFormat.CODE_93
            "ITF", "ITF_14", "INTERLEAVED_2_OF_5" -> BarcodeFormat.ITF
            "CODABAR" -> BarcodeFormat.CODABAR
            else -> BarcodeFormat.QR_CODE
        }
    }

    /** Classifies a symbology as 1D (linear) or 2D (matrix). */
    fun kindOf(format: BarcodeFormat): String = when (format) {
        BarcodeFormat.QR_CODE,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.AZTEC,
        BarcodeFormat.PDF_417 -> "2D"
        else -> "1D"
    }

    fun generate(
        tenantId: String,
        content: String,
        formatLabel: String?,
        width: Int,
        height: Int,
        margin: Int,
        ecLevel: String?,
        fgColorHex: String?,
        bgColorHex: String?,
        encoding: String
    ): CodeResult {
        require(content.isNotEmpty()) { "code-generate requires non-empty content" }

        val format = resolveFormat(formatLabel)
        val kind = kindOf(format)
        val size = width.coerceIn(64, 2048)
        val barHeight = if (kind == "1D") height.coerceIn(32, 2048) else size

        val hints = mutableMapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to margin.coerceIn(0, 16),
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        if (format == BarcodeFormat.QR_CODE || format == BarcodeFormat.AZTEC) {
            hints[EncodeHintType.ERROR_CORRECTION] = resolveEcLevel(ecLevel)
        }

        val matrix: BitMatrix = MultiFormatWriter().encode(content, format, size, barHeight, hints)
        val fg = parseColor(fgColorHex, Color.BLACK)
        val bg = parseColor(bgColorHex, Color.WHITE)
        val bitmap = render(matrix, fg, bg)

        val codeId = "code_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val context = currentAndroidContext?.invoke()
        val mediaDir = context?.let {
            File(it.filesDir, "workspaces/$tenantId/media").apply { mkdirs() }
        } ?: File(System.getProperty("java.io.tmpdir") ?: ".", "media").apply { mkdirs() }

        val outputFile = File(mediaDir, "$codeId.png")
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val bytes = stream.toByteArray()
        outputFile.writeBytes(bytes)
        bitmap.recycle()

        val base64Str = if (encoding.lowercase() in setOf("base64", "both")) {
            Base64.getEncoder().encodeToString(bytes)
        } else null

        return CodeResult(
            uri = "file://${outputFile.absolutePath}",
            codeId = codeId,
            content = content,
            format = format.name,
            kind = kind,
            width = matrix.width,
            height = matrix.height,
            sizeBytes = bytes.size.toLong(),
            mimeType = "image/png",
            base64 = base64Str,
            rowMatrix = toRowMatrix(matrix)
        )
    }

    private fun render(matrix: BitMatrix, fg: Int, bg: Int): Bitmap {
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = if (matrix.get(x, y)) fg else bg
            }
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    /** Human-readable matrix using '1' for a dark module and '0' for a light one. */
    private fun toRowMatrix(matrix: BitMatrix): List<String> =
        (0 until matrix.height).map { y ->
            buildString(matrix.width) {
                for (x in 0 until matrix.width) append(if (matrix.get(x, y)) '1' else '0')
            }
        }

    private fun resolveEcLevel(raw: String?): ErrorCorrectionLevel =
        when (raw?.trim()?.uppercase()) {
            "L" -> ErrorCorrectionLevel.L
            "Q" -> ErrorCorrectionLevel.Q
            "H" -> ErrorCorrectionLevel.H
            else -> ErrorCorrectionLevel.M
        }

    private fun parseColor(hex: String?, fallback: Int): Int =
        try {
            hex?.let { Color.parseColor(it) } ?: fallback
        } catch (_: Exception) {
            fallback
        }
}
