package engine.tools.vector

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil

/**
 * On-device INT8 image classifier backed by `mobilenetv4_small_int8.tflite`.
 *
 * The bundled graph is a standard ImageNet-1k classifier: UINT8 `1x224x224x3` input and
 * UINT8 `1x1000` class logits, so predictions are ImageNet categories (e.g. "espresso"),
 * not retail SKUs. Mapping a category to a store product is the caller's job.
 */
object ImageClassifier {
    private const val MODEL = "mobilenetv4_small_int8.tflite"
    private const val SIZE = 224
    private const val CLASSES = 1000

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    private fun interpreter(ctx: Context): Interpreter =
        interpreter ?: Interpreter(FileUtil.loadMappedFile(ctx, MODEL)).also { interpreter = it }

    /** ImageNet-1k labels, loaded from assets; falls back to class indices when absent. */
    private fun labels(ctx: Context): List<String> {
        if (labels.isNotEmpty()) return labels
        labels = runCatching {
            ctx.assets.open("imagenet_labels.txt").bufferedReader().use { it.readLines() }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        return labels
    }

    data class Prediction(val index: Int, val label: String, val confidence: Double)

    /**
     * Dequantizes the graph's UINT8 output and keeps the top [topK] above [minConfidence].
     *
     * The tensor holds **logits**, not probabilities (this model's dequantized range is roughly
     * -17..+6), so they go through a softmax first; reporting raw logits as "confidence" would
     * show values above 1 and negative scores. Split out from inference so the scoring path is
     * testable without the native runtime.
     */
    fun rank(
        scores: ByteArray,
        scale: Float,
        zeroPoint: Int,
        labels: List<String>,
        topK: Int,
        minConfidence: Double
    ): List<Prediction> {
        val step = if (scale.isFinite() && scale > 0f) scale else 1f / 255f
        val logits = DoubleArray(scores.size) { i ->
            val v = ((scores[i].toInt() and 0xFF) - zeroPoint) * step.toDouble()
            if (v.isFinite()) v else Double.NEGATIVE_INFINITY
        }
        val max = logits.max()
        if (max == Double.NEGATIVE_INFINITY) return emptyList()
        val exps = DoubleArray(logits.size) { kotlin.math.exp(logits[it] - max) }
        val sum = exps.sum()
        if (sum <= 0.0 || !sum.isFinite()) return emptyList()
        return scores.indices
            .map { i ->
                Prediction(i, labels.getOrElse(i) { "class_$i" }, exps[i] / sum)
            }
            .sortedByDescending { it.confidence }
            .take(topK.coerceIn(1, scores.size))
            .filter { it.confidence >= minConfidence }
    }

    /**
     * Runs inference on [bmp] and returns the top [topK] classes above [minConfidence].
     * Returns an empty list when nothing clears the threshold, so callers can report
     * "no confident match" instead of inventing a result.
     */
    fun classify(
        ctx: Context,
        bmp: Bitmap,
        topK: Int = 3,
        minConfidence: Double = 0.0
    ): List<Prediction> {
        val model = interpreter(ctx)
        val scaled = Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true)
        val input = Array(1) { Array(SIZE) { y -> Array(SIZE) { x ->
            val p = scaled.getPixel(x, y)
            byteArrayOf(((p shr 16) and 0xFF).toByte(), ((p shr 8) and 0xFF).toByte(), (p and 0xFF).toByte())
        } } }
        val output = Array(1) { ByteArray(CLASSES) }
        model.run(input, output)
        if (scaled !== bmp) scaled.recycle()

        val names = labels(ctx)
        val quant = model.getOutputTensor(0).quantizationParams()
        return rank(output[0], quant.scale, quant.zeroPoint, names, topK, minConfidence)
    }
}
