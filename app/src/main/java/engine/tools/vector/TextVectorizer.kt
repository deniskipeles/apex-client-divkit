package engine.tools.vector

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder

object TextVectorizer {
    private const val MODEL = "universal_sentence_encoder_lite.tflite"  // ≈ 5.5 MB
    private var embedder: TextEmbedder? = null

    @Synchronized
    fun embed(ctx: Context, text: String): FloatArray {
        val e = embedder ?: TextEmbedder.createFromOptions(
            ctx,
            TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .build()
        ).also { embedder = it }
        val r = e.embed(text)
        val emb = r.embeddingResult().embeddings().first().floatEmbedding()
        val v = FloatArray(emb.size) { emb[it] }
        return VectorMath.l2(v)
    }
}
