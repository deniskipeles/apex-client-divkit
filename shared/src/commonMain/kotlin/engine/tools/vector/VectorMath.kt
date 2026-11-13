package engine.tools.vector

import kotlin.math.sqrt

object VectorMath {
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dim mismatch ${a.size} vs ${b.size}" }
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i] }
        if (na == 0f || nb == 0f) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).coerceIn(-1f, 1f)
    }

    fun l2(v: FloatArray): FloatArray {
        var n = 0f; for (x in v) n += x*x
        if (n == 0f) return v
        val inv = 1f / sqrt(n)
        return FloatArray(v.size) { v[it] * inv }
    }
}

data class VectorResult(
    val sourceType: String,        // "text" | "image" | "image_text"
    val vector: FloatArray,
    val dim: Int,
    val modelId: String,
    val text: String? = null,      // populated when OCR ran
    val ocrConfidence: Float? = null,
    val latencyMs: Long
)