import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// Swift mirror of `engine.crypto.PayloadVerifier` (packages/contract/docs/envelope.md).
/// Verifies remote DivKit envelopes before they reach the renderer.
public final class PayloadVerifier: @unchecked Sendable {

    public struct Error: Swift.Error, CustomStringConvertible {
        public let message: String
        public var description: String { message }
    }

    private let expectedTenant: String
    private let publicKey: Curve25519.Signing.PublicKey
    private let maxSkewMs: Int64
    private let maxSeenJti: Int
    private var seenJti: [String] = []
    private let lock = NSLock()

    public init(tenant: String, publicKeyBase64: String, maxSkewMs: Int64 = 300_000, maxSeenJti: Int = 10_000) throws {
        self.expectedTenant = tenant
        self.maxSkewMs = maxSkewMs
        self.maxSeenJti = maxSeenJti

        guard let der = Data(base64Encoded: publicKeyBase64) else {
            throw Error(message: "Invalid public key base64")
        }
        let raw = PayloadVerifier.rawPublicKey(from: der)
        self.publicKey = try Curve25519.Signing.PublicKey(rawRepresentation: raw)
    }

    /// Accepts a raw 32-byte key or an Ed25519 SubjectPublicKeyInfo (DER) whose trailing
    /// 32 bytes are the raw key, matching the Android Ed25519 loader.
    static func rawPublicKey(from der: Data) -> Data {
        if der.count == 32 { return der }
        return der.suffix(32)
    }

    public func verify(rawEnvelope: String) throws -> String {
        guard let data = rawEnvelope.data(using: .utf8),
              let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw Error(message: "Malformed envelope")
        }
        guard let tenant = obj["tenant"] as? String,
              let schemaVersion = obj["schema_version"] as? String,
              let iatNum = obj["iat"] as? NSNumber,
              let jti = obj["jti"] as? String,
              let signatureB64 = obj["signature"] as? String else {
            throw Error(message: "Malformed envelope: missing fields")
        }
        if tenant != expectedTenant {
            throw Error(message: "Tenant mismatch: expected \(expectedTenant), got \(tenant)")
        }

        let canonicalMap: [String: Any?] = [
            "tenant": tenant,
            "schema_version": schemaVersion,
            "iat": iatNum.int64Value,
            "jti": jti,
            "payload": CanonicalJson.normalize(obj["payload"] ?? NSNull()),
        ]
        let message = Data(CanonicalJson.canonicalize(canonicalMap).utf8)

        guard let signature = Data(base64Encoded: signatureB64),
              publicKey.isValidSignature(signature, for: message) else {
            throw Error(message: "Signature verification failed")
        }

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if abs(now - iatNum.int64Value) > maxSkewMs {
            throw Error(message: "Envelope expired (iat=\(iatNum.int64Value), now=\(now))")
        }

        lock.lock(); defer { lock.unlock() }
        if seenJti.contains(jti) {
            throw Error(message: "Replay detected: jti=\(jti)")
        }
        seenJti.append(jti)
        if seenJti.count > maxSeenJti { seenJti.removeFirst(seenJti.count - maxSeenJti) }

        let payload = obj["payload"] ?? NSNull()
        let payloadData = try JSONSerialization.data(withJSONObject: payload)
        return String(data: payloadData, encoding: .utf8) ?? "{}"
    }

    /// Edge-signer equivalent (`signing.ts`).
    public static func createSignedEnvelope(
        tenant: String,
        schemaVersion: String = "1.0",
        payloadJson: String,
        privateKeyBase64: String,
        iat: Int64? = nil,
        jti: String = UUID().uuidString
    ) throws -> String {
        let payloadData = Data(payloadJson.utf8)
        let payload = try JSONSerialization.jsonObject(with: payloadData)
        let issuedAt = iat ?? Int64(Date().timeIntervalSince1970 * 1000)

        let canonicalMap: [String: Any?] = [
            "tenant": tenant,
            "schema_version": schemaVersion,
            "iat": issuedAt,
            "jti": jti,
            "payload": CanonicalJson.normalize(payload),
        ]
        let message = Data(CanonicalJson.canonicalize(canonicalMap).utf8)

        let keyData = PayloadVerifier.rawPrivateKey(from: Data(base64Encoded: privateKeyBase64)!)
        let signingKey = try Curve25519.Signing.PrivateKey(rawRepresentation: keyData)
        let signature = try signingKey.signature(for: message)

        let envelope: [String: Any] = [
            "tenant": tenant,
            "schema_version": schemaVersion,
            "iat": issuedAt,
            "jti": jti,
            "payload": payload,
            "signature": signature.base64EncodedString(),
        ]
        let out = try JSONSerialization.data(withJSONObject: envelope)
        return String(data: out, encoding: .utf8)!
    }

    static func rawPrivateKey(from der: Data) -> Data {
        if der.count == 32 { return der }
        return der.suffix(32)
    }
}
