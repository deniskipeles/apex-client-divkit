import XCTest
import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
@testable import Contract

/// Minimal handler so registry tests do not depend on the Xcode app target.
private final class StubHapticsTool: ToolHandler {
    let capability = "hardware.haptics@1"
    let requiredPermissions: Set<String> = []
    func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult { .success([:]) }
}

final class ContractTests: XCTestCase {

    private var contractDir: URL {
        // Tests run from apps/ios; the contract lives at the repo root.
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // ContractTests
            .deletingLastPathComponent() // Tests
            .deletingLastPathComponent() // ios
            .deletingLastPathComponent() // apps
            .deletingLastPathComponent() // repo root
            .appendingPathComponent("packages/contract")
    }

    func testCanonicalFixturesMatchExpectedBytes() throws {
        let dir = contractDir.appendingPathComponent("fixtures/canonical/v1")
        let inputs = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        XCTAssertEqual(inputs.count, 3)

        for input in inputs {
            let expected = dir.appendingPathComponent(input.deletingPathExtension().lastPathComponent + ".expected.bin")
            let raw = try JSONSerialization.jsonObject(with: Data(contentsOf: input))
            let normalized = CanonicalJson.normalize(raw)
            let actual = CanonicalJson.canonicalize(normalized)
            XCTAssertEqual(try String(contentsOf: expected), actual, input.lastPathComponent)
            XCTAssertEqual(try Data(contentsOf: expected).count, actual.data(using: .utf8)!.count)
        }
    }

    func testWholeDoublesCollapseToIntegers() {
        XCTAssertEqual("2", CanonicalJson.canonicalize(2.0))
        XCTAssertEqual("2.5", CanonicalJson.canonicalize(2.5))
    }

    func testVariableNamespaceRejectsNonUserPrefix() {
        EngineStateBridge.shared.pushUpdate(name: "user.probe", value: "ok")
        XCTAssertEqual(EngineStateBridge.shared.getVariable("user.probe") as? String, "ok")
    }

    func testSyncRetryClassification() {
        XCTAssertTrue(SyncEngine.isRetryable(reason: "503"))
        XCTAssertTrue(SyncEngine.isRetryable(reason: "timeout"))
        XCTAssertTrue(SyncEngine.isRetryable(reason: "rate_limited"))
        XCTAssertFalse(SyncEngine.isRetryable(reason: "validation_error"))
    }

    func testSignedEnvelopeRoundTrips() throws {
        let key = Curve25519.Signing.PrivateKey()
        let pub = key.publicKey.rawRepresentation.base64EncodedString()
        let priv = key.rawRepresentation.base64EncodedString()

        let envelope = try PayloadVerifier.createSignedEnvelope(
            tenant: "acme",
            payloadJson: "{\"type\":\"text\",\"text\":\"hi\"}",
            privateKeyBase64: priv,
            jti: "jti-1"
        )
        let verifier = try PayloadVerifier(tenant: "acme", publicKeyBase64: pub)
        let verified = try verifier.verify(rawEnvelope: envelope)
        XCTAssertTrue(verified.contains("\"type\""))
    }

    func testWrongTenantIsRejected() throws {
        let key = Curve25519.Signing.PrivateKey()
        let envelope = try PayloadVerifier.createSignedEnvelope(
            tenant: "acme", payloadJson: "{}", privateKeyBase64: key.rawRepresentation.base64EncodedString(), jti: "jti-2"
        )
        let verifier = try PayloadVerifier(tenant: "other", publicKeyBase64: key.publicKey.rawRepresentation.base64EncodedString())
        XCTAssertThrowsError(try verifier.verify(rawEnvelope: envelope))
    }

    func testRegistryResolvesAliases() {
        let registry = TerminalRegistry()
        registry.register(StubHapticsTool())
        XCTAssertNotNil(registry.resolve("hardware.haptics@1"))
        XCTAssertNotNil(registry.resolve("haptics"))
        XCTAssertNotNil(registry.resolve("device.haptics@1"))
    }

    func testRegistryRejectsUnknownCapability() {
        let registry = TerminalRegistry()
        registry.register(StubHapticsTool())
        XCTAssertNil(registry.resolve("does.not.exist@1"))
    }

    func testAliasesMatchToolsJson() throws {
        let url = contractDir.appendingPathComponent("tools.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        let caps = try XCTUnwrap(json?["capabilities"] as? [[String: Any]])

        var expected: [String: [String]] = [:]
        for cap in caps {
            let id = try XCTUnwrap(cap["id"] as? String)
            expected[id] = (cap["aliases"] as? [String]) ?? []
        }
        XCTAssertEqual(TerminalRegistry.aliases, expected)
    }
}
