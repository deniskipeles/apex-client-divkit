package engine.tools.vector

import kotlin.math.sqrt

/**
 * Deterministic lexical fallback for devices without MediaPipe's text JNI.
 *
 * It hashes lower-cased word unigrams and character trigrams into a fixed-size signed
 * vector, then L2-normalizes it. The result is a real feature vector (shared vocabulary
 * raises cosine similarity) but it is not a semantic model; callers see the model name in
 * the tool output.
 */
object HashedTextEmbedding {
    const val DIM = 512
    private const val FNV_OFFSET = 2166136261u
    private const val FNV_PRIME = 16777619u
    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    fun embed(text: String, dim: Int = DIM): FloatArray {
        require(dim > 0) { "dim must be positive" }
        val vector = FloatArray(dim)
        val normalized = text.lowercase()
        val tokens = TOKEN.findAll(normalized).map { it.value }.toList()
        if (tokens.isEmpty()) {
            addFeature(vector, normalized, 1f)
        } else {
            tokens.forEach { token ->
                addFeature(vector, "w:$token", 1f)
                if (token.length >= 3) {
                    for (i in 0..token.length - 3) {
                        addFeature(vector, "c:${token.substring(i, i + 3)}", 0.5f)
                    }
                }
            }
        }
        var norm = 0f
        vector.forEach { norm += it * it }
        if (norm == 0f) return vector
        val inv = 1f / sqrt(norm)
        for (i in vector.indices) vector[i] *= inv
        return vector
    }

    private fun addFeature(vector: FloatArray, feature: String, weight: Float) {
        val hash = fnv1a(feature)
        val index = (hash and Int.MAX_VALUE) % vector.size
        val sign = if ((hash ushr 31) == 0) 1f else -1f
        vector[index] += sign * weight
    }

    private fun fnv1a(value: String): Int {
        var hash = FNV_OFFSET
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xFF).toUInt()
            hash *= FNV_PRIME
        }
        return hash.toInt()
    }
}
