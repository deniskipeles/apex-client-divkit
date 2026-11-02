package engine.crypto

/**
 * Platform Ed25519 verification.
 *
 * Android uses JCA `Signature.getInstance("Ed25519")` (API 33+) or a fallback
 * BouncyCastle-backed implementation. iOS uses CryptoKit `Curve25519.Signing.PublicKey`
 * bridged via Kotlin/Native interop.
 */
expect object Ed25519 {
    /**
     * @param publicKeyDer Base64-encoded DER SubjectPublicKeyInfo (X.509) or raw 32-byte key.
     * @param message      Canonical envelope bytes.
     * @param signature    Base64-encoded 64-byte Ed25519 signature.
     */
    fun verify(publicKeyDer: String, message: ByteArray, signature: String): Boolean

    /** Loads a public key once for reuse. Implementations may cache. */
    fun loadPublicKey(publicKeyDer: String): Any
}
