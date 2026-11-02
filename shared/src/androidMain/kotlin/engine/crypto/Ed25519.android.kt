package engine.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

actual object Ed25519 {
    private val cache = mutableMapOf<String, PublicKey>()

    actual fun verify(publicKeyDer: String, message: ByteArray, signature: String): Boolean {
        val key = cache.getOrPut(publicKeyDer) { parseKey(publicKeyDer) }
        val sig = Signature.getInstance("Ed25519")
        sig.initVerify(key)
        sig.update(message)
        return sig.verify(Base64.decode(signature, Base64.NO_WRAP))
    }

    actual fun loadPublicKey(publicKeyDer: String): Any =
        cache.getOrPut(publicKeyDer) { parseKey(publicKeyDer) }

    private fun parseKey(der: String): PublicKey {
        val bytes = Base64.decode(der, Base64.NO_WRAP)
        // If the caller supplied a raw 32-byte key, wrap it in Ed25519 SPKI DER.
        val spki = if (bytes.size == 32) {
            val header = byteArrayOf(
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
            )
            header + bytes
        } else bytes
        return KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(spki))
    }
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
