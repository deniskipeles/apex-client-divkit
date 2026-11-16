import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// Ed25519 signing key pair. Mirrors `engine.crypto.SigningKeyPair` on Android: the keys are
/// carried as base64 (raw 32-byte keys or DER) so they round-trip through the same signed
/// envelope flow.
public struct SigningKeyPair: Sendable {
    public let publicKeyDerBase64: String
    public let privateKeyDerBase64: String
    public let algorithmMode: String

    public init(publicKeyDerBase64: String, privateKeyDerBase64: String, algorithmMode: String) {
        self.publicKeyDerBase64 = publicKeyDerBase64
        self.privateKeyDerBase64 = privateKeyDerBase64
        self.algorithmMode = algorithmMode
    }

    /// Generates an Ed25519 key pair. Mirrors `Ed25519.generateKeyPair(seedLabel:)`.
    public static func generate(seedLabel: String = "default-edge-signer") -> SigningKeyPair {
        let key = Curve25519.Signing.PrivateKey()
        return SigningKeyPair(
            publicKeyDerBase64: key.publicKey.rawRepresentation.base64EncodedString(),
            privateKeyDerBase64: key.rawRepresentation.base64EncodedString(),
            algorithmMode: "Curve25519 Ed25519 (raw 32-byte keys)"
        )
    }
}
