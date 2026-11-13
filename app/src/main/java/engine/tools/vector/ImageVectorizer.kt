package engine.tools.vector

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil

object ImageVectorizer {
    private const val MODEL = "mobilenetv4_small_int8.tflite"  // ≈ 3.8 MB
    private const val SIZE = 224
    private const val DIM = 1024
    private var interpreter: Interpreter? = null

    @Synchronized
    fun embed(ctx: Context, bmp: Bitmap): FloatArray {
        val i = interpreter ?: Interpreter(FileUtil.loadMappedFile(ctx, MODEL)).also { interpreter = it }
        // Resize + normalize to match MobileNetV4 INT8 preprocessing
        val scaled = Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true)
        val input = Array(1) { Array(SIZE) { y -> Array(SIZE) { x ->
            val p = scaled.getPixel(x, y)
            byteArrayOf(((p shr 16) and 0xFF).toByte(), ((p shr 8) and 0xFF).toByte(), (p and 0xFF).toByte())
        } } }
        val output = Array(1) { FloatArray(DIM) }
        i.run(input, output)
        if (scaled !== bmp) scaled.recycle()
        return VectorMath.l2(output[0])
    }
}