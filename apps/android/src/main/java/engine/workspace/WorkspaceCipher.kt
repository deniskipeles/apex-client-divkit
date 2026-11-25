package engine.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts a workspace bundle for production builds.
 *
 * Layout: `APXW` | version (1 byte) | nonce (12 bytes) | AES-256-GCM(gzip(json)).
 * The key never lives in the repository: the build injects it through
 * `-PapexWorkspaceKey` / `APEX_WORKSPACE_KEY`, and the app only receives it in `prod`.
 */
object WorkspaceCipher {

    private const val MAGIC = "APXW"
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    fun keyFrom(text: String): ByteArray {
        val trimmed = text.trim()
        return when {
            trimmed.matches(Regex("[0-9a-fA-F]{64}")) -> hex(trimmed)
            trimmed.matches(Regex("[A-Za-z0-9+/]{43}=")) ->
                java.util.Base64.getDecoder().decode(trimmed)
            trimmed.isNotBlank() ->
                MessageDigest.getInstance("SHA-256").digest(trimmed.toByteArray(Charsets.UTF_8))
            else -> throw IllegalArgumentException("workspace key is empty")
        }
    }

    fun encrypt(plain: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "workspace key must be 32 bytes, was ${key.size}" }
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val sealed = cipher.doFinal(gzip(plain))
        return MAGIC.toByteArray(Charsets.US_ASCII) + byteArrayOf(VERSION) + nonce + sealed
    }

    fun decrypt(blob: ByteArray, key: ByteArray): ByteArray {
        require(isEncrypted(blob)) { "not an encrypted workspace bundle" }
        require(blob.size > 4 + 1 + NONCE_BYTES) { "encrypted workspace bundle is truncated" }
        val nonce = blob.copyOfRange(5, 5 + NONCE_BYTES)
        val body = blob.copyOfRange(5 + NONCE_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return gunzip(cipher.doFinal(body))
    }

    fun isEncrypted(blob: ByteArray): Boolean =
        blob.size > 5 && String(blob, 0, 4, Charsets.US_ASCII) == MAGIC && blob[4] == VERSION

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
