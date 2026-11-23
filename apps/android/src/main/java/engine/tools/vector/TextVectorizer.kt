package engine.tools.vector

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder

data class TextEmbedding(val vector: FloatArray, val model: String)

object TextVectorizer {
    private const val MODEL = "universal_sentence_encoder_lite.tflite"  // ≈ 5.5 MB
    private var embedder: TextEmbedder? = null
    private var nativeUnavailable = false

    @Synchronized
    fun embed(ctx: Context, text: String): TextEmbedding {
        require(text.isNotBlank()) { "text must not be blank" }
        if (!nativeUnavailable) {
            try {
                val e = embedder ?: TextEmbedder.createFromOptions(
                    ctx,
                    TextEmbedder.TextEmbedderOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                        .build()
                ).also { embedder = it }
                val r = e.embed(text)
                val emb = r.embeddingResult().embeddings().first().floatEmbedding()
                val v = FloatArray(emb.size) { emb[it] }
                return TextEmbedding(VectorMath.l2(v), "MediaPipe USE-Lite")
            } catch (t: Throwable) {
                // MediaPipe 0.10.14 ships no x86_64 text JNI; a missing .so must not turn
                // every embedding call into a failure. Retry never succeeds, so latch it.
                nativeUnavailable = true
                runCatching { embedder?.close() }
                embedder = null
            }
        }
        return TextEmbedding(
            HashedTextEmbedding.embed(text),
            "Hashed lexical fallback (${HashedTextEmbedding.DIM}-d FNV-1a)"
        )
    }
}
