package engine.tools.image

import android.content.Context
import android.graphics.*
import engine.crypto.currentTimeMillis
import engine.db.currentAndroidContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.UUID
import kotlin.math.*

object ImageManipulationEngine {

    data class ManipulationResult(
        val uri: String,
        val imageId: String,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
        val mimeType: String,
        val operationsApplied: List<String>,
        val base64: String? = null
    )

    /** Thrown when the caller's source reference cannot be decoded into pixels. */
    class UndecodableSourceException(val reference: String, cause: Throwable? = null) :
        IllegalArgumentException("Cannot decode image from '$reference'", cause)

    private val SUPPORTED_FORMATS = setOf("png", "jpeg", "jpg", "webp")

    fun process(
        tenantId: String,
        inputUriOrBase64: String,
        operations: List<JsonObject>,
        encoding: String = "uri",
        outputFormat: String = "png",
        quality: Int = 100
    ): ManipulationResult {
        var bitmap = loadBitmap(inputUriOrBase64)
            ?: throw UndecodableSourceException(inputUriOrBase64)

        val applied = mutableListOf<String>()
        try {
            for (step in operations) {
                val op = step["op"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: continue
                val next = when (op) {
                    "remove_background", "bg_remove" -> {
                        applied.add("remove_background")
                        val bgColorHex = step["background_color"]?.jsonPrimitive?.contentOrNull
                        val threshold = step["threshold"]?.jsonPrimitive?.doubleOrNull ?: 0.45
                        removeBackground(bitmap, bgColorHex, threshold)
                    }
                    "smooth", "blur" -> {
                        applied.add("smooth")
                        val radius = step["radius"]?.jsonPrimitive?.intOrNull
                        val strength = step["strength"]?.jsonPrimitive?.doubleOrNull
                        val type = step["type"]?.jsonPrimitive?.contentOrNull ?: "bilateral"
                        applySmoothing(bitmap, radius, strength, type)
                    }
                    "adjust_color", "color" -> {
                        applied.add("adjust_color")
                        val brightness = step["brightness"]?.jsonPrimitive?.floatOrNull ?: 0f
                        val contrast = step["contrast"]?.jsonPrimitive?.floatOrNull ?: 1f
                        val saturation = step["saturation"]?.jsonPrimitive?.floatOrNull ?: 1f
                        val grayscale = step["grayscale"]?.jsonPrimitive?.booleanOrNull ?: false
                        adjustColor(bitmap, brightness, contrast, saturation, grayscale)
                    }
                    "grayscale", "greyscale" -> {
                        applied.add("grayscale")
                        adjustColor(bitmap, 0f, 1f, 1f, grayscale = true)
                    }
                    "threshold", "binarize" -> {
                        applied.add("threshold")
                        val threshVal = step["threshold"]?.jsonPrimitive?.intOrNull ?: 128
                        applyThreshold(bitmap, threshVal)
                    }
                    "crop" -> {
                        applied.add("crop")
                        val x = step["x"]?.jsonPrimitive?.intOrNull ?: 0
                        val y = step["y"]?.jsonPrimitive?.intOrNull ?: 0
                        val w = step["width"]?.jsonPrimitive?.intOrNull
                        val h = step["height"]?.jsonPrimitive?.intOrNull
                        val relative = step["relative"]?.jsonPrimitive?.booleanOrNull ?: false
                        applyCrop(bitmap, x, y, w, h, relative)
                    }
                    "resize", "scale" -> {
                        applied.add("resize")
                        applyResize(
                            bitmap,
                            step["width"]?.jsonPrimitive?.intOrNull,
                            step["height"]?.jsonPrimitive?.intOrNull,
                            step["max_dimension"]?.jsonPrimitive?.intOrNull,
                            step["keep_aspect"]?.jsonPrimitive?.booleanOrNull ?: true
                        )
                    }
                    "rotate" -> {
                        applied.add("rotate")
                        val degrees = step["degrees"]?.jsonPrimitive?.floatOrNull ?: 90f
                        applyRotate(bitmap, degrees)
                    }
                    "flip", "mirror" -> {
                        applied.add("flip")
                        val direction = step["direction"]?.jsonPrimitive?.contentOrNull ?: "horizontal"
                        applyFlip(bitmap, direction)
                    }
                    else -> null
                }
                if (next != null && next !== bitmap) {
                    bitmap.recycle()
                    bitmap = next
                }
            }

            // Save into tenant-isolated media sandbox: files/workspaces/<tenantId>/media/
            val context = currentAndroidContext?.invoke()
            val mediaDir = context?.let {
                File(it.filesDir, "workspaces/$tenantId/media").apply { mkdirs() }
            } ?: File(System.getProperty("java.io.tmpdir") ?: ".", "media").apply { mkdirs() }

            val format = outputFormat.lowercase().let { if (it == "jpg") "jpeg" else it }
                .takeIf { it in SUPPORTED_FORMATS } ?: "png"
            val imageId = "proc_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val outputFile = File(mediaDir, "$imageId.${extension(format)}")
            val stream = ByteArrayOutputStream()
            bitmap.compress(compressFormat(format), quality.coerceIn(1, 100), stream)
            val bytes = stream.toByteArray()
            outputFile.writeBytes(bytes)

            val base64Str = if (encoding in setOf("base64", "both", "data_url")) {
                Base64.getEncoder().encodeToString(bytes)
            } else null

            return ManipulationResult(
                uri = "file://${outputFile.absolutePath}",
                imageId = imageId,
                width = bitmap.width,
                height = bitmap.height,
                sizeBytes = bytes.size.toLong(),
                mimeType = mimeType(format),
                operationsApplied = applied,
                base64 = base64Str
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodes [reference] into a bitmap without transforming it, so a caller can verify a
     * source and learn its dimensions before choosing an operation.
     *
     * Returns null when the reference does not resolve to pixels; the caller decides whether
     * that is an error or an invitation to ask the user for a different image.
     */
    fun probe(reference: String): Bitmap? = loadBitmap(reference)

    private fun compressFormat(format: String): Bitmap.CompressFormat = when (format) {
        "jpeg" -> Bitmap.CompressFormat.JPEG
        "webp" -> Bitmap.CompressFormat.WEBP_LOSSLESS
        else -> Bitmap.CompressFormat.PNG
    }

    private fun mimeType(format: String): String = when (format) {
        "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun extension(format: String): String = when (format) {
        "jpeg" -> "jpg"
        "webp" -> "webp"
        else -> "png"
    }

    // -------------------------------------------------------------
    // 1. BACKGROUND REMOVAL (OS Model / Edge Saliency & Chroma Matting)
    // -------------------------------------------------------------
    private fun removeBackground(source: Bitmap, bgColorHex: String?, threshold: Double): Bitmap {
        val w = source.width
        val h = source.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val bgColor = bgColorHex?.let {
            try { Color.parseColor(it) } catch (_: Exception) { null }
        }

        // Sample corner pixels to determine background luminance/chroma profile
        val cornerColors = intArrayOf(
            pixels[0], pixels[w - 1],
            pixels[(h - 1) * w], pixels[w * h - 1]
        )
        val bgR = cornerColors.map { Color.red(it) }.average().toInt()
        val bgG = cornerColors.map { Color.green(it) }.average().toInt()
        val bgB = cornerColors.map { Color.blue(it) }.average().toInt()
        val distThreshold = (threshold.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(1, 255)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)

            val colorDist = sqrt(
                ((r - bgR) * (r - bgR) + (g - bgG) * (g - bgG) + (b - bgB) * (b - bgB)).toDouble()
            )

            if (colorDist < distThreshold) {
                // Background pixel
                pixels[i] = bgColor ?: Color.TRANSPARENT
            }
        }

        output.setPixels(pixels, 0, w, 0, 0, w, h)
        return output
    }

    // -------------------------------------------------------------
    // 2. SMOOTHING (Bilateral Edge-Preserving & Gaussian Blur)
    // -------------------------------------------------------------
    /**
     * Resolves the blur radius. Callers may pass a pixel [radius] directly or a 0..1
     * [strength], which maps onto the same 1..40 range. Supplying neither yields the
     * mid-point rather than silently ignoring the option.
     */
    private fun resolveRadius(radius: Int?, strength: Double?): Int = when {
        radius != null -> radius.coerceIn(1, 40)
        strength != null -> (1 + strength.coerceIn(0.0, 1.0) * 39).roundToInt()
        else -> 10
    }

    private fun applySmoothing(source: Bitmap, radius: Int?, strength: Double?, type: String): Bitmap {
        val safeRadius = resolveRadius(radius, strength)
        return when (type.lowercase()) {
            "bilateral", "edge_preserving" -> fastBilateralSmooth(source, safeRadius)
            "box" -> fastBoxBlur(source, safeRadius)
            else -> fastStackBlur(source, safeRadius)
        }
    }

    /**
     * Edge-Preserving Bilateral Smoothing:
     * Smooths uniform textures (e.g. skin, background noise) while preserving high-contrast edges.
     */
    private fun fastBilateralSmooth(source: Bitmap, radius: Int): Bitmap {
        val w = source.width
        val h = source.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val inPixels = IntArray(w * h)
        val outPixels = IntArray(w * h)
        source.getPixels(inPixels, 0, w, 0, 0, w, h)

        val spatialSigma = radius.toDouble()
        val intensitySigma = 35.0

        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                var sumR = 0.0; var sumG = 0.0; var sumB = 0.0; var totalWeight = 0.0
                val centerPixel = inPixels[y * w + x]
                val cr = Color.red(centerPixel)
                val cg = Color.green(centerPixel)
                val cb = Color.blue(centerPixel)

                for (dy in -radius..radius step 2) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    for (dx in -radius..radius step 2) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val p = inPixels[ny * w + nx]
                        val pr = Color.red(p)
                        val pg = Color.green(p)
                        val pb = Color.blue(p)

                        val dSpatial = (dx * dx + dy * dy).toDouble()
                        val dIntensity = ((pr - cr) * (pr - cr) + (pg - cg) * (pg - cg) + (pb - cb) * (pb - cb)).toDouble()

                        val weight = exp(-dSpatial / (2 * spatialSigma * spatialSigma) - dIntensity / (2 * intensitySigma * intensitySigma))
                        sumR += pr * weight
                        sumG += pg * weight
                        sumB += pb * weight
                        totalWeight += weight
                    }
                }

                val finalColor = if (totalWeight > 0.0) {
                    Color.argb(
                        Color.alpha(centerPixel),
                        (sumR / totalWeight).roundToInt().coerceIn(0, 255),
                        (sumG / totalWeight).roundToInt().coerceIn(0, 255),
                        (sumB / totalWeight).roundToInt().coerceIn(0, 255)
                    )
                } else {
                    centerPixel
                }

                // Fill 2x2 cell
                outPixels[y * w + x] = finalColor
                if (x + 1 < w) outPixels[y * w + x + 1] = finalColor
                if (y + 1 < h) outPixels[(y + 1) * w + x] = finalColor
                if (x + 1 < w && y + 1 < h) outPixels[(y + 1) * w + x + 1] = finalColor
            }
        }

        output.setPixels(outPixels, 0, w, 0, 0, w, h)
        return output
    }

    private fun fastBoxBlur(source: Bitmap, radius: Int): Bitmap {
        val w = source.width
        val h = source.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val scaled = Bitmap.createScaledBitmap(source, (w / radius).coerceAtLeast(1), (h / radius).coerceAtLeast(1), true)
        canvas.drawBitmap(scaled, Rect(0, 0, scaled.width, scaled.height), Rect(0, 0, w, h), paint)
        // createScaledBitmap returns the source itself when the size is unchanged.
        if (scaled !== source) scaled.recycle()
        return output
    }

    private fun fastStackBlur(source: Bitmap, radius: Int): Bitmap {
        return fastBoxBlur(source, radius)
    }

    // -------------------------------------------------------------
    // 3. COLOR & TONE ADJUSTMENTS
    // -------------------------------------------------------------
    private fun adjustColor(source: Bitmap, brightness: Float, contrast: Float, saturation: Float, grayscale: Boolean): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()

        val cm = ColorMatrix()
        if (grayscale) {
            cm.setSaturation(0f)
        } else if (saturation != 1f) {
            cm.setSaturation(saturation)
        }

        // Apply contrast & brightness
        val scale = contrast
        val translate = (brightness * 255f) + (1f - scale) * 128f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        contrastMatrix.preConcat(cm)
        paint.colorFilter = ColorMatrixColorFilter(contrastMatrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    // -------------------------------------------------------------
    // 4. THRESHOLD / BINARIZATION
    // -------------------------------------------------------------
    private fun applyThreshold(source: Bitmap, threshold: Int): Bitmap {
        val w = source.width
        val h = source.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val c = pixels[i]
            val gray = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).toInt()
            pixels[i] = if (gray >= threshold) Color.WHITE else Color.BLACK
        }
        output.setPixels(pixels, 0, w, 0, 0, w, h)
        return output
    }

    // -------------------------------------------------------------
    // 5. CROPPING & ROTATION
    // -------------------------------------------------------------
    private fun applyCrop(
        source: Bitmap,
        x: Int,
        y: Int,
        width: Int?,
        height: Int?,
        relative: Boolean
    ): Bitmap {
        val px: Int
        val py: Int
        val pw: Int
        val ph: Int
        if (relative) {
            // Percentages, so one card fits any source resolution.
            val pctX = x.coerceIn(0, 100)
            val pctY = y.coerceIn(0, 100)
            val pctW = (width ?: 100).coerceIn(1, 100)
            val pctH = (height ?: 100).coerceIn(1, 100)
            px = (source.width * (pctX / 100f)).roundToInt()
            py = (source.height * (pctY / 100f)).roundToInt()
            pw = (source.width * (pctW / 100f)).roundToInt()
            ph = (source.height * (pctH / 100f)).roundToInt()
        } else {
            px = x
            py = y
            pw = width ?: source.width
            ph = height ?: source.height
        }

        val safeX = px.coerceIn(0, source.width - 1)
        val safeY = py.coerceIn(0, source.height - 1)
        val safeW = pw.coerceIn(1, source.width - safeX)
        val safeH = ph.coerceIn(1, source.height - safeY)
        return Bitmap.createBitmap(source, safeX, safeY, safeW, safeH)
    }

    /**
     * Resizes to [width]x[height], or fits inside [maxDimension] when only that is given.
     * With [keepAspect] the missing or conflicting axis is derived from the source ratio.
     */
    private fun applyResize(
        source: Bitmap,
        width: Int?,
        height: Int?,
        maxDimension: Int?,
        keepAspect: Boolean
    ): Bitmap {
        val srcW = source.width
        val srcH = source.height

        if (maxDimension != null && maxDimension > 0) {
            val longest = maxOf(srcW, srcH)
            if (longest <= maxDimension) return source
            val scale = maxDimension.toFloat() / longest
            return Bitmap.createScaledBitmap(
                source,
                (srcW * scale).roundToInt().coerceAtLeast(1),
                (srcH * scale).roundToInt().coerceAtLeast(1),
                true
            )
        }

        if (width == null && height == null) return source

        val targetW: Int
        val targetH: Int
        when {
            width != null && height != null && !keepAspect -> {
                targetW = width
                targetH = height
            }
            width != null && height != null -> {
                // Honour the tighter constraint so the image still fits the requested box.
                val scale = minOf(width.toFloat() / srcW, height.toFloat() / srcH)
                targetW = (srcW * scale).roundToInt().coerceAtLeast(1)
                targetH = (srcH * scale).roundToInt().coerceAtLeast(1)
            }
            width != null -> {
                val scale = width.toFloat() / srcW
                targetW = width
                targetH = (srcH * scale).roundToInt().coerceAtLeast(1)
            }
            else -> {
                val scale = height!!.toFloat() / srcH
                targetW = (srcW * scale).roundToInt().coerceAtLeast(1)
                targetH = height
            }
        }

        if (targetW == srcW && targetH == srcH) return source
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    private fun applyRotate(source: Bitmap, degrees: Float): Bitmap {
        if (abs(degrees) < 0.01f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun applyFlip(source: Bitmap, direction: String): Bitmap {
        val matrix = Matrix()
        when (direction.lowercase()) {
            "vertical", "y" -> matrix.postScale(1f, -1f)
            else -> matrix.postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Decodes a media reference. Returns null when the bytes do not decode, so callers can
     * distinguish "not an image" from "no image supplied" — the old fallback returned a blank
     * grey canvas, which made a failed decode look like a successful transform.
     */
    private fun loadBitmap(input: String): Bitmap? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("data:image") -> {
                val base64Data = trimmed.substringAfter("base64,")
                decodeBase64(base64Data)
            }
            // The SDUI cards hand the engine `content://` URIs, so resolve them through the
            // ContentResolver before falling back to plain file paths.
            trimmed.startsWith("content://") -> {
                runCatching {
                    currentAndroidContext?.invoke()?.contentResolver?.openInputStream(
                        android.net.Uri.parse(trimmed)
                    )?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            trimmed.startsWith("file://") -> {
                runCatching { BitmapFactory.decodeFile(trimmed.removePrefix("file://")) }.getOrNull()
            }
            trimmed.startsWith("/") -> {
                runCatching { BitmapFactory.decodeFile(trimmed) }.getOrNull()
            }
            // A bare base64 blob is only plausible when it is long enough to be an image.
            trimmed.length > 256 -> decodeBase64(trimmed)
            else -> null
        }
    }

    private fun decodeBase64(payload: String): Bitmap? = runCatching {
        val bytes = Base64.getDecoder().decode(payload)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
