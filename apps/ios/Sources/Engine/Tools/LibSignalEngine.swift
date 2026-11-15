import Foundation

/// Deterministic, dependency-free mirror of the observable `engine.tools.libsignal.LibSignalEngine`
/// contract.
///
/// Android binds the official Signal Foundation library (Double Ratchet + X3DH). That library
/// is not available on Linux CI and cannot be compiled into the portable `Engine` target, so
/// this mirror reproduces the same *observable* surface: stable per-(workspace, peer) session
/// identities, a deterministic safety number, and a reversible encrypt/decrypt round trip.
/// The iOS app target wires the same protocol operations to its platform crypto.
public final class LibSignalEngine: @unchecked Sendable {
    public static let shared = LibSignalEngine()

    private let lock = NSRecursiveLock()
    private var identities: [String: (registrationId: Int, identityKey: String)] = [:]
    private var sessions: Set<String> = []
    private var verified: Set<String> = []
    private var counters: [String: (send: Int64, recv: Int64)] = [:]

    public init() {}

    private func sessionKey(_ workspaceId: String, _ peerId: String, _ deviceId: Int) -> String {
        "\(workspaceId)|\(peerId)|\(deviceId)"
    }

    /// FNV-1a hash rendered as lowercase hex (deterministic across platforms).
    static func fnv1a(_ value: String) -> String {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in value.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01b3
        }
        return String(hash, radix: 16)
    }

    /// 6-block displayable safety number derived from the identity pair.
    public static func fingerprint(workspaceId: String, peerId: String) -> String {
        let digest = fnv1a("\(workspaceId)|\(peerId)")
        var padded = digest
        while padded.count < 12 { padded += "0" }
        let chars = Array(padded)
        var blocks: [String] = []
        var i = 0
        while i < 12 {
            blocks.append(String(chars[i..<(i + 2)]).uppercased())
            i += 2
        }
        return blocks.joined(separator: "-")
    }

    @discardableResult
    public func setupIdentity(_ workspaceId: String) -> (registrationId: Int, identityKey: String) {
        lock.lock(); defer { lock.unlock() }
        if let existing = identities[workspaceId] { return existing }
        let regId = Int.random(in: 1000...16380)
        let key = LibSignalEngine.fnv1a("identity::\(workspaceId)")
        let identity = (registrationId: regId, identityKey: key)
        identities[workspaceId] = identity
        return identity
    }

    public func hasSession(_ workspaceId: String, peerId: String, deviceId: Int = 1) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return sessions.contains(sessionKey(workspaceId, peerId, deviceId))
    }

    public func createOrGetSession(_ workspaceId: String, peerId: String, deviceId: Int = 1) -> SignalSession {
        setupIdentity(workspaceId)
        lock.lock()
        let key = sessionKey(workspaceId, peerId, deviceId)
        sessions.insert(key)
        let counter = counters[key] ?? (send: 0, recv: 0)
        lock.unlock()
        let sessionId = "sig_sess_" + String(LibSignalEngine.fnv1a("\(workspaceId):\(peerId):\(deviceId)").prefix(12))
        return SignalSession(
            sessionId: sessionId,
            peerId: peerId,
            deviceId: deviceId,
            fingerprint: getSafetyNumber(workspaceId, peerId: peerId, deviceId: deviceId),
            isVerified: isSessionVerified(workspaceId, peerId: peerId, deviceId: deviceId),
            sendCounter: counter.send,
            recvCounter: counter.recv
        )
    }

    /// Reversible symmetric transform standing in for the Double Ratchet ciphertext.
    public func encrypt(_ workspaceId: String, peerId: String, deviceId: Int = 1, plaintext: String) -> EncryptedEnvelope {
        setupIdentity(workspaceId)
        _ = createOrGetSession(workspaceId, peerId: peerId, deviceId: deviceId)
        let key = sessionKey(workspaceId, peerId, deviceId)
        lock.lock()
        let counter = (counters[key]?.send ?? 0) + 1
        counters[key] = (send: counter, recv: counters[key]?.recv ?? 0)
        lock.unlock()

        let nonce = LibSignalEngine.fnv1a("\(key)#\(counter)")
        let body = Data(plaintext.utf8).base64EncodedString()
        let payload = "\(nonce):\(body)"
        return EncryptedEnvelope(type: 3, address: peerId, deviceId: deviceId, ciphertextB64: Data(payload.utf8).base64EncodedString())
    }

    public func decrypt(_ workspaceId: String, peerId: String, deviceId: Int = 1, ciphertextB64: String, messageType: Int = 3) throws -> String {
        setupIdentity(workspaceId)
        _ = createOrGetSession(workspaceId, peerId: peerId, deviceId: deviceId)
        let key = sessionKey(workspaceId, peerId, deviceId)
        lock.lock()
        counters[key] = (send: counters[key]?.send ?? 0, recv: (counters[key]?.recv ?? 0) + 1)
        lock.unlock()

        guard let outer = Data(base64Encoded: ciphertextB64), let inner = String(data: outer, encoding: .utf8) else {
            throw EngineError.validation("Malformed ciphertext")
        }
        let parts = inner.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2, let decoded = Data(base64Encoded: String(parts[1])), let plaintext = String(data: decoded, encoding: .utf8) else {
            throw EngineError.validation("Malformed ciphertext payload")
        }
        return plaintext
    }

    public func getSafetyNumber(_ workspaceId: String, peerId: String, deviceId: Int = 1) -> String {
        setupIdentity(workspaceId)
        return LibSignalEngine.fingerprint(workspaceId: workspaceId, peerId: peerId)
    }

    public func setSessionVerified(_ workspaceId: String, peerId: String, verified isVerified: Bool, deviceId: Int = 1) {
        lock.lock(); defer { lock.unlock() }
        let key = sessionKey(workspaceId, peerId, deviceId)
        if isVerified { verified.insert(key) } else { verified.remove(key); sessions.remove(key) }
    }

    public func isSessionVerified(_ workspaceId: String, peerId: String, deviceId: Int = 1) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return verified.contains(sessionKey(workspaceId, peerId, deviceId))
    }

    public func resetSession(_ workspaceId: String, peerId: String, deviceId: Int = 1) {
        lock.lock(); defer { lock.unlock() }
        let key = sessionKey(workspaceId, peerId, deviceId)
        sessions.remove(key)
        verified.remove(key)
        counters[key] = nil
    }
}

public struct SignalSession: Sendable {
    public let sessionId: String
    public let peerId: String
    public let deviceId: Int
    public let fingerprint: String
    public let isVerified: Bool
    public let sendCounter: Int64
    public let recvCounter: Int64
}

public struct EncryptedEnvelope: Sendable {
    public let type: Int
    public let address: String
    public let deviceId: Int
    public let ciphertextB64: String
}
