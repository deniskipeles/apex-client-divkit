package engine.tools.codec

/**
 * RFC 9285 Base45 codec. The alphabet is fixed by the RFC and includes a space, so input
 * whitespace is not stripped except for CR/LF that scanners sometimes insert between chunks.
 */
object Base45 {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"
    private val VALUES = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size / 2 + 1) * 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            if (i + 1 < bytes.size) {
                val b1 = bytes[i + 1].toInt() and 0xff
                val value = b0 * 256 + b1
                out.append(ALPHABET[value % 45])
                out.append(ALPHABET[(value / 45) % 45])
                out.append(ALPHABET[value / 2025])
                i += 2
            } else {
                out.append(ALPHABET[b0 % 45])
                out.append(ALPHABET[b0 / 45])
                i += 1
            }
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val cleaned = text.filterNot { it == '\n' || it == '\r' }
        if (cleaned.isEmpty()) return ByteArray(0)
        if (cleaned.length % 3 == 1) error("Invalid Base45 length")
        val out = ArrayList<Byte>(cleaned.length / 3 * 2 + 1)
        var i = 0
        while (i < cleaned.length) {
            val remaining = cleaned.length - i
            if (remaining >= 3) {
                val c0 = value(cleaned[i])
                val c1 = value(cleaned[i + 1])
                val c2 = value(cleaned[i + 2])
                val value = c0 + c1 * 45 + c2 * 2025
                if (value > 0xffff) error("Invalid Base45 triplet")
                out.add((value / 256).toByte())
                out.add((value % 256).toByte())
                i += 3
            } else {
                val c0 = value(cleaned[i])
                val c1 = value(cleaned[i + 1])
                val value = c0 + c1 * 45
                if (value > 0xff) error("Invalid Base45 pair")
                out.add(value.toByte())
                i += 2
            }
        }
        return out.toByteArray()
    }

    private fun value(c: Char): Int {
        val code = c.code
        if (code >= VALUES.size) error("Invalid Base45 character '$c'")
        val value = VALUES[code]
        if (value < 0) error("Invalid Base45 character '$c'")
        return value
    }
}
