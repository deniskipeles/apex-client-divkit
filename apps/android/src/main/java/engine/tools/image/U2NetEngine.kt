package engine.tools.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Real U-2-Netp salient-object segmentation through TFLite.
 *
 * The 4.4 MB graph is bundled as `assets/u2netp.tflite` (converted from the public
 * U-2-Netp ONNX weights by `onnx2tf`; fetched by `build-apks.sh` when missing). Input
 * is NHWC float32 1x320x320x3 normalised with ImageNet statistics; output 0 is the
 * sigmoid mask 1x320x320x1. The interpreter is cached because loading costs ~1 s.
 */
object U2NetEngine {
    private const val ASSET = "u2netp.tflite"
    private const val SIZE = 320
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    data class Result(
        val cutoutUri: String,
        val maskUri: String,
        val width: Int,
        val height: Int,
        val confidence: Double,
        val imageId: String,
        val inferenceMs: Long
    )

    @Volatile private var interpreter: Interpreter? = null

    fun isAvailable(context: Context): Boolean =
        runCatching { context.assets.open(ASSET).use { it.read() }; true }.getOrDefault(false)

    private fun interpreter(context: Context): Interpreter {
        interpreter?.let { return it }
        synchronized(this) {
            interpreter?.let { return it }
            val created = Interpreter(
                FileUtil.loadMappedFile(context, ASSET),
                Interpreter.Options().apply { setNumThreads(2) }
            )
            interpreter = created
            return created
        }
    }

    fun segment(context: Context, tenantId: String, source: Bitmap): Result? = try {
        val started = System.nanoTime()
        Log.d("U2NetEngine", "segment start ${source.width}x${source.height}")
        val tflite = interpreter(context)
        Log.d("U2NetEngine", "interpreter ready")
        val scaled = Bitmap.createScaledBitmap(source, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== source) scaled.recycle()

        Log.d("U2NetEngine", "pixels ready")
        val input = Array(1) { Array(SIZE) { Array(SIZE) { FloatArray(3) } } }
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val p = pixels[y * SIZE + x]
                val out = input[0][y][x]
                out[0] = (((p shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
                out[1] = (((p shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
                out[2] = ((p and 0xFF) / 255f - MEAN[2]) / STD[2]
            }
        }

        Log.d("U2NetEngine", "input ready; allocating output")
        val output = Array(1) { Array(SIZE) { Array(SIZE) { FloatArray(1) } } }
        Log.d("U2NetEngine", "running")
        tflite.runForMultipleInputsOutputs(arrayOf(input), mapOf(0 to output))
        Log.d("U2NetEngine", "run done")
        val rawMask = output[0]

        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (row in rawMask) for (cell in row) {
            val v = cell[0]
            if (v < min) min = v
            if (v > max) max = v
        }
        val span = (max - min).takeIf { it.isFinite() && it > 1e-6f } ?: 1f
        val maskPixels = IntArray(SIZE * SIZE)
        var sum = 0.0
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val alpha = (((rawMask[y][x][0] - min) / span).coerceIn(0f, 1f) * 255f).roundToInt()
            sum += alpha
            maskPixels[y * SIZE + x] = (alpha shl 24) or 0x00FFFFFF
        }
        val mask = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        mask.setPixels(maskPixels, 0, SIZE, 0, 0, SIZE, SIZE)

        val scaledMask = Bitmap.createScaledBitmap(mask, source.width, source.height, true)
        val sourcePixels = IntArray(source.width * source.height)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        val maskAlphas = IntArray(sourcePixels.size)
        scaledMask.getPixels(maskAlphas, 0, source.width, 0, 0, source.width, source.height)
        for (i in sourcePixels.indices) {
            sourcePixels[i] = (maskAlphas[i] ushr 24 shl 24) or (sourcePixels[i] and 0x00FFFFFF)
        }
        val cutout = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        cutout.setPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)

        val media = File(context.filesDir, "workspaces/$tenantId/media").apply { mkdirs() }
        val id = "u2net_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val cutoutFile = File(media, "${id}_cutout.png")
        val maskFile = File(media, "${id}_mask.png")
        cutoutFile.outputStream().use { cutout.compress(Bitmap.CompressFormat.PNG, 100, it) }
        maskFile.outputStream().use { mask.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val width = source.width
        val height = source.height
        cutout.recycle()
        mask.recycle()
        if (scaledMask !== mask) scaledMask.recycle()

        Result(
            cutoutUri = "file://${cutoutFile.absolutePath}",
            maskUri = "file://${maskFile.absolutePath}",
            width = width,
            height = height,
            confidence = (sum / (SIZE * SIZE * 255.0)).coerceIn(0.0, 1.0),
            imageId = id,
            inferenceMs = (System.nanoTime() - started) / 1_000_000
        )
    } catch (t: Throwable) {
        Log.e("U2NetEngine", "segmentation failed", t)
        null
    }
}
