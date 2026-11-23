package engine.tools.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import engine.db.currentAndroidContext
import java.io.File

/** A decoded symbology plus the payload it carried. */
data class ScanResult(
    val rawValue: String,
    val format: String,
    val type: String
)

/**
 * Decodes 1D barcodes and 2D QR codes from real pixels with ZXing.
 *
 * Every path here decodes actual image data: a tenant media file, an absolute path, a
 * `content://` URI, an inline base64/data URL, or a luminance frame handed over by the camera
 * analyzer. Nothing is inferred from string length, so a reported format always reflects the
 * symbology that was physically present in the image.
 */
object BarcodeDecoder {

    private val formats = listOf(
        BarcodeFormat.QR_CODE,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.PDF_417,
        BarcodeFormat.AZTEC,
        BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8,
        BarcodeFormat.UPC_A,
        BarcodeFormat.UPC_E,
        BarcodeFormat.CODE_128,
        BarcodeFormat.CODE_39,
        BarcodeFormat.CODE_93,
        BarcodeFormat.ITF,
        BarcodeFormat.CODABAR
    )

    private val liveHints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to formats,
        DecodeHintType.CHARACTER_SET to "UTF-8"
    )

    private val stillHints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to formats,
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8"
    )

    private val threadLocalReader = ThreadLocal.withInitial { MultiFormatReader() }

    fun formatName(mlKitFormat: Int): String = when (mlKitFormat) {
        1 -> "CODE_128"
        2 -> "CODE_39"
        4 -> "CODE_93"
        8 -> "CODABAR"
        16 -> "DATA_MATRIX"
        32 -> "EAN_13"
        64 -> "EAN_8"
        128 -> "ITF"
        256 -> "QR_CODE"
        512 -> "UPC_A"
        1024 -> "UPC_E"
        2048 -> "PDF_417"
        4096 -> "AZTEC"
        else -> "UNKNOWN"
    }

    /** Decodes [bitmap], trying the full frame first and then cropped quadrants. */
    fun decode(bitmap: Bitmap): ScanResult? {
        decodeBitmapOnce(bitmap, tryHarder = false)?.let { return it }
        decodeBitmapOnce(bitmap, tryHarder = true)?.let { return it }
        // Small or off-centre codes often fail on the full frame; retry on crops.
        val halfW = bitmap.width / 2
        val halfH = bitmap.height / 2
        if (halfW < 16 || halfH < 16) return null
        val crops = listOf(0 to 0, halfW to 0, 0 to halfH, halfW to halfH)
        for ((x, y) in crops) {
            val w = minOf(halfW, bitmap.width - x)
            val h = minOf(halfH, bitmap.height - y)
            if (w <= 0 || h <= 0) continue
            val crop = Bitmap.createBitmap(bitmap, x, y, w, h)
            val hit = decodeBitmapOnce(crop, tryHarder = true)
            crop.recycle()
            if (hit != null) return hit
        }
        return null
    }

    private fun decodeBitmapOnce(bitmap: Bitmap, tryHarder: Boolean = false): ScanResult? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        return runReader(BinaryBitmap(HybridBinarizer(source)), tryHarder)
    }

    /**
     * Decodes a camera frame already in luminance (Y) plane form.
     *
     * [rotationDegrees] is the sensor orientation reported by CameraX. The buffer arrives in
     * sensor order, which on a phone is landscape even when the device is upright. Rotating it
     * back into the user's frame matters most for 1D symbologies: a barcode is a single scan
     * line, and ZXing's linear readers only search horizontally and vertically over the image as
     * given, so an unrotated frame leaves the code running the wrong way.
     */
    fun decodeLuminance(
        data: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int = 0
    ): ScanResult? {
        if (width <= 0 || height <= 0 || data.size < width * height) return null
        val d = ((rotationDegrees % 360) + 360) % 360

        // If sensor orientation is non-zero (typical on handheld phones), try rotated first
        if (d != 0) {
            val rotated = rotateLuminance(data, width, height, d)
            val rotatedSource = PlanarYUVLuminanceSource(
                rotated.data, rotated.width, rotated.height, 0, 0, rotated.width, rotated.height, false
            )
            val hit = runReader(BinaryBitmap(HybridBinarizer(rotatedSource)), tryHarder = false)
            if (hit != null) return hit
        }

        // Unrotated pass (for 0 degrees or if rotated didn't match)
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        return runReader(BinaryBitmap(HybridBinarizer(source)), tryHarder = false)
    }

    internal class RotatedLuminance(val data: ByteArray, val width: Int, val height: Int)

    /** Rotates a dense luminance buffer clockwise by [degrees] (90, 180 or 270). */
    internal fun rotateLuminance(
        data: ByteArray,
        width: Int,
        height: Int,
        degrees: Int
    ): RotatedLuminance {
        val d = ((degrees % 360) + 360) % 360
        if (d == 180) {
            // Only the luma region is rotated: a YUV frame carries chroma past `width * height`,
            // and folding those bytes in would corrupt every pixel.
            val luma = width * height
            val out = ByteArray(data.size)
            for (i in 0 until luma) out[i] = data[luma - 1 - i]
            return RotatedLuminance(out, width, height)
        }
        val out = ByteArray(data.size)
        if (d == 90) {
            // New width is the old height.
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val nx = height - 1 - y
                    val ny = x
                    out[ny * height + nx] = data[y * width + x]
                }
            }
            return RotatedLuminance(out, height, width)
        }
        // 270
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx = y
                val ny = width - 1 - x
                out[ny * height + nx] = data[y * width + x]
            }
        }
        return RotatedLuminance(out, height, width)
    }

    private fun runReader(image: BinaryBitmap, tryHarder: Boolean = false): ScanResult? = try {
        val reader = threadLocalReader.get()!!.apply {
            setHints(if (tryHarder) stillHints else liveHints)
            reset()
        }
        val result = reader.decodeWithState(image)
        result?.let { ScanResult(it.text, it.barcodeFormat.name, classify(it.barcodeFormat)) }
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        null
    }

    /** Loads a bitmap from a media reference: `file://`, absolute path, `content://`, base64. */
    fun loadBitmap(reference: String): Bitmap? {
        val ref = reference.trim()
        if (ref.isEmpty()) return null
        if (ref.startsWith("data:image/")) {
            val encoded = ref.substringAfter("base64,", "")
            if (encoded.isEmpty()) return null
            val bytes = runCatching { android.util.Base64.decode(encoded, android.util.Base64.DEFAULT) }
                .getOrNull() ?: return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        val context = currentAndroidContext?.invoke()
        if (ref.startsWith("content://")) {
            val uri = android.net.Uri.parse(ref)
            val fromResolver = runCatching {
                context?.contentResolver?.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (fromResolver != null) return fromResolver
        }
        val file = File(ref.removePrefix("file://"))
        if (file.exists()) return BitmapFactory.decodeFile(file.absolutePath)
        val raw = runCatching { android.util.Base64.decode(ref, android.util.Base64.DEFAULT) }.getOrNull()
            ?: return null
        return BitmapFactory.decodeByteArray(raw, 0, raw.size)
    }

    fun classify(format: BarcodeFormat): String = when (format) {
        BarcodeFormat.QR_CODE,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.PDF_417,
        BarcodeFormat.AZTEC -> "2D"
        else -> "1D"
    }

    /** True when a decoded [format] satisfies the requested target (`both`, `qr`, `barcode`, ...). */
    fun matchesTarget(format: String, target: String): Boolean {
        val t = target.trim().lowercase()
        if (t in setOf("both", "all", "*", "")) return true
        return when (t) {
            "barcode", "1d", "linear" -> !is2D(format)
            "qr", "2d", "matrix" -> is2D(format)
            else -> format.equals(t, ignoreCase = true) ||
                format.replace("_", "").equals(t.replace("_", ""), ignoreCase = true)
        }
    }

    private fun is2D(format: String): Boolean = format in setOf(
        "QR_CODE", "DATA_MATRIX", "PDF_417", "AZTEC", "MAXICODE"
    )
}
