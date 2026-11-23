package engine.tools.vector

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MobileNetV4-small INT8 descriptor.
 *
 * The bundled graph exposes a single `class_logits` tensor (1000 UINT8 values). It is a
 * class-score descriptor, not a trained metric-learning embedding; it is still a stable
 * L2-normalized feature vector for near-duplicate and coarse visual similarity.
 */
object ImageVectorizer {
    private const val MODEL = "mobilenetv4_small_int8.tflite"
    private const val SIZE = 224
    private const val DIM = 1000
    private var interpreter: Interpreter? = null

    @Synchronized
    fun embed(ctx: Context, bmp: Bitmap): FloatArray {
        require(!bmp.isRecycled) { "bitmap is recycled" }
        val i = interpreter ?: Interpreter(FileUtil.loadMappedFile(ctx, MODEL)).also { interpreter = it }
        val scaled = Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true)
        try {
            val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
            for (y in 0 until SIZE) {
                for (x in 0 until SIZE) {
                    val p = scaled.getPixel(x, y)
                    input.put(((p shr 16) and 0xFF).toByte())
                    input.put(((p shr 8) and 0xFF).toByte())
                    input.put((p and 0xFF).toByte())
                }
            }
            input.rewind()
            val output = Array(1) { ByteArray(DIM) }
            i.run(input, output)
            return VectorMath.l2(FloatArray(DIM) { (output[0][it].toInt() and 0xFF).toFloat() })
        } finally {
            if (scaled !== bmp) scaled.recycle()
        }
    }
}
