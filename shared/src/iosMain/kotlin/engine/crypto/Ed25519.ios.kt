package engine.crypto

import kotlinx.cinterop.*
import platform.CoreCrypto.CC_SHA256
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.*
import platform.darwin.NSObject

/**
 * iOS Ed25519 verification.
 *
 * CryptoKit is exposed to Kotlin/Native through Obj-C interop. The exact symbol
 * names vary between Xcode versions; we use Security.framework's SecKey APIs
 * which are stable and available from iOS 13.
 */
actual object Ed25519 {
    actual fun verify(publicKeyDer: String, message: ByteArray, signature: String): Boolean {
        val key = loadPublicKey(publicKeyDer) as? SecKeyRef ?: return false
        val sigBytes = kotlin.io.encoding.Base64.decode(signature)
        return message.usePinned { msgPin ->
            sigBytes.usePinned { sigPin ->
                SecKeyVerifySignature(
                    key = key,
                    algorithm = kSecKeyAlgorithmEdDSA,
                    signedData = msgPin.addressOf(0).toNSData(message.size),
                    signature = sigPin.addressOf(0).toNSData(sigBytes.size),
                    error = null
                )
            }
        }
    }

    actual fun loadPublicKey(publicKeyDer: String): Any {
        val der = kotlin.io.encoding.Base64.decode(publicKeyDer)
        val attrs = mapOf<Any?, Any?>(
            kSecAttrKeyType to kSecAttrKeyTypeEd25519,
            kSecAttrKeyClass to kSecAttrKeyClassPublic
        )
        return SecKeyCreateWithData(der.toNSData(der.size), attrs as CFDictionaryRef, null)
            ?: error("Invalid Ed25519 public key")
    }
}

actual fun currentTimeMillis(): Long =
    (platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong()

@OptIn(ExperimentalForeignApi::class)
private fun CPointer<ByteVar>.toNSData(size: Int): NSData =
    NSData.create(bytes = this, length = size.toULong())
