import XCTest
@testable import Engine
import Contract

/// Exercises the native tool suite: URL dispatch, schema resolution, output-variable mapping
/// and the aliasing rules that keep the suite byte-compatible with the Android engine.
final class NativeToolsSuiteTests: XCTestCase {

    override func setUp() {
        super.setUp()
        EngineState.shared.clear()
    }

    func testNormalizesKnownAliasesToCanonicalIds() {
        XCTAssertEqual(NativeToolsSuite.normalizeToolId("scan-qr"), "scan")
        XCTAssertEqual(NativeToolsSuite.normalizeToolId("device.haptics@1"), "haptics")
        XCTAssertEqual(NativeToolsSuite.normalizeToolId("vision.scan_code@1"), "scan")
        XCTAssertEqual(NativeToolsSuite.normalizeToolId("network.transfer"), "net")
        XCTAssertEqual(NativeToolsSuite.normalizeToolId("vision.generate_code@1"), "code-generate")
        XCTAssertEqual(NativeToolsSuite.normalizeToolId("storage.files@1"), "files")
        XCTAssertEqual(NativeToolsSuite.normalizeToolId("fs"), "files")
        XCTAssertEqual(NativeToolsSuite.normalizeToolId("unknown-tool"), "unknown-tool")
    }

    func testEveryRegisteredSchemaIdIsUnique() {
        let ids = NativeToolsSuite.registeredSchemas.map(\.toolId)
        XCTAssertEqual(ids.count, Set(ids).count)
    }

    func testScanToolDecodesAndMapsOutputVariables() async {
        let outcome = await NativeToolsSuite.execute(
            url: "tools://scan/scan",
            payload: .object([("raw_value", .string("9780201633610"))]),
            tenantId: "acme_pos"
        )
        XCTAssertTrue(outcome.success)
        XCTAssertEqual(outcome.toolId, "scan")
        XCTAssertEqual(outcome.outputVariables["user.scanned_code"], .str("9780201633610"))
        XCTAssertEqual(outcome.outputVariables["user.scanned_format"], .str("EAN_13"))
        XCTAssertEqual(EngineState.shared.getVariable("user.scanned_code"), .str("9780201633610"))
    }

    func testScanFormatMismatchIsReportedAsFailure() async {
        let outcome = await NativeToolsSuite.execute(
            url: "tools://scan/scan",
            payload: .object([
                ("raw_value", .string("9780201633610")),
                ("target_format", .string("qr"))
            ]),
            tenantId: "acme_pos"
        )
        XCTAssertFalse(outcome.success)
    }

    func testHapticsToolReportsSuccessWithMetadata() async {
        let outcome = await NativeToolsSuite.execute(
            url: "tools://haptics/click",
            payload: .object([]),
            tenantId: "acme_pos"
        )
        XCTAssertTrue(outcome.success)
        XCTAssertEqual(outcome.outputVariables["user.haptics_status"], .str("SUCCESS"))
    }

    func testBeeperToolStatusReportsCapabilities() async {
        let outcome = await NativeToolsSuite.execute(
            url: "tools://beeper/status",
            payload: .object([]),
            tenantId: "acme_pos"
        )
        XCTAssertTrue(outcome.success)
    }

    func testVectorizeTextProducesEmbeddingAndSimilarityIsOne() async {
        let a = await NativeToolsSuite.execute(url: "tools://vectorize/text", payload: .object([("text", .string("hello world"))]), tenantId: "acme_pos")
        XCTAssertTrue(a.success)
        guard let vectorB64 = a.resultData["vector_b64"]?.stringOrNull else { return XCTFail("missing vector_b64") }
        XCTAssertEqual(a.resultData["dim"]?.longOrNull, 512)

        let similarity = await NativeToolsSuite.execute(
            url: "tools://vectorize/similarity",
            payload: .object([("a", .string(vectorB64)), ("b", .string(vectorB64))]),
            tenantId: "acme_pos"
        )
        XCTAssertTrue(similarity.success)
        let score = similarity.resultData["score"]?.doubleOrNull ?? similarity.resultData["score"]?.longOrNull.map(Double.init) ?? 0
        XCTAssertEqual(score, 1.0, accuracy: 0.0001)
    }

    func testCodeGenerateProducesMatrixAndMetadata() async {
        let outcome = await NativeToolsSuite.execute(
            url: "tools://code-generate/generate",
            payload: .object([("content", .string("https://example.com/pay?id=42")), ("format", .string("qr"))]),
            tenantId: "acme_pos"
        )
        XCTAssertTrue(outcome.success)
        XCTAssertEqual(outcome.resultData["format"]?.stringOrNull, "QR_CODE")
        XCTAssertEqual(outcome.resultData["kind"]?.stringOrNull, "2D")
        XCTAssertGreaterThan(outcome.resultData["matrix_rows"]?.longOrNull ?? 0, 0)
    }

    func testCodeGenerateFormatAliasing() {
        XCTAssertEqual(CodeGenerateTool.resolveFormat("qr"), "QR_CODE")
        XCTAssertEqual(CodeGenerateTool.resolveFormat("DataMatrix"), "DATA_MATRIX")
        XCTAssertEqual(CodeGenerateTool.resolveFormat("ean13"), "EAN_13")
        XCTAssertEqual(CodeGenerateTool.kindOf("CODE_128"), "1D")
        XCTAssertEqual(CodeGenerateTool.kindOf("QR_CODE"), "2D")
    }

    func testLibSignalEncryptDecryptRoundTrip() async {
        let encrypted = await NativeToolsSuite.execute(
            url: "tools://libsignal/encrypt",
            payload: .object([("data", .string("secret payload"))]),
            tenantId: "acme_pos"
        )
        XCTAssertTrue(encrypted.success)
        guard let ciphertext = encrypted.resultData["ciphertext"]?.stringOrNull else { return XCTFail("missing ciphertext") }

        let decrypted = await NativeToolsSuite.execute(
            url: "tools://libsignal/decrypt",
            payload: .object([("ciphertext", .string(ciphertext))]),
            tenantId: "acme_pos"
        )
        XCTAssertTrue(decrypted.success)
        XCTAssertEqual(decrypted.resultData["plaintext"]?.stringOrNull, "secret payload")
    }

    func testPrinterEscPosPayloadContainsInitAndCut() {
        let payload = PrintTool.buildEscPos(ToolArgs(["text": "Hello", "cut": "true", "align": "center"]))
        XCTAssertEqual(Array(payload.prefix(2)), [0x1B, 0x40])
        XCTAssertEqual(Array(payload.suffix(3)), [0x1D, 0x56, 0x00])
    }

    func testDivActionRouterBindsResultVariables() async {
        let router = DivActionRouter(registry: TerminalApp.registry)
        var captured: [String: String] = [:]
        let ctx = ToolContext(workspaceId: "acme_pos") { name, value in captured[name] = value }
        let result = await router.handle(
            raw: "div-action://terminal.tool.invoke?tool=device.haptics%401&result_var=user.scan",
            provenanceType: .publisher,
            ctx: ctx
        )
        XCTAssertNotNil(result)
        // ResultBinder publishes the tool's `state` (falling back to "ok") plus per-field vars.
        XCTAssertEqual(captured["user.scan"], "ok")
        XCTAssertEqual(captured["user.scan_status"], "SUCCESS")
    }

    func testProvenanceGateBlocksUnknownPublisherCapability() {
        let checker = ProvenanceChecker()
        XCTAssertTrue(checker.allows(.agency, "anything@1"))
        XCTAssertTrue(checker.allows(.publisher, "device.haptics@1"))
        XCTAssertFalse(checker.allows(.publisher, "not.allowlisted@1"))
    }

    func testTerminalRegistryManifestCoversCanonicalCapabilities() {
        let manifest = TerminalApp.registry.manifest()
        XCTAssertTrue(manifest.has("hardware.haptics@1"))
        XCTAssertTrue(manifest.has("vision.scan_code@1"))
        XCTAssertTrue(manifest.has("network.transfer@1"))
        XCTAssertTrue(manifest.has("vectorize"))
        XCTAssertTrue(manifest.has("storage.files@1"))
    }

    func testFilesToolRoundTripsBytesInsideItsSandbox() async throws {
        let root = NSTemporaryDirectory() + "files-tool-test-" + UUID().uuidString
        defer { try? FileManager.default.removeItem(atPath: root) }
        let tool = FilesTool(rootOverride: root)

        let written = await tool.invoke(
            args: ToolArgs(["op": "write", "path": "notes/hello.txt", "content": "hello"]),
            ctx: ToolContext(workspaceId: "tenant_a")
        )
        guard case let .success(writeData) = written else {
            return XCTFail("write failed: \(written)")
        }
        XCTAssertEqual(writeData["status"] as? String, "SUCCESS")

        let read = await tool.invoke(
            args: ToolArgs(["op": "read", "path": "notes/hello.txt"]),
            ctx: ToolContext(workspaceId: "tenant_a")
        )
        guard case let .success(readData) = read else {
            return XCTFail("read failed: \(read)")
        }
        XCTAssertEqual(readData["content"] as? String, "hello")

        // Another tenant resolves the same relative path to its own sandbox.
        let otherTenant = await tool.invoke(
            args: ToolArgs(["op": "exists", "path": "notes/hello.txt"]),
            ctx: ToolContext(workspaceId: "tenant_b")
        )
        guard case let .success(otherData) = otherTenant else {
            return XCTFail("exists failed: \(otherTenant)")
        }
        XCTAssertEqual(otherData["exists"] as? Bool, false)
    }

    func testFilesToolRejectsPathTraversal() async {
        let root = NSTemporaryDirectory() + "files-tool-escape-" + UUID().uuidString
        defer { try? FileManager.default.removeItem(atPath: root) }
        let tool = FilesTool(rootOverride: root)

        let escaped = await tool.invoke(
            args: ToolArgs(["op": "write", "path": "../../etc/passwd", "content": "x"]),
            ctx: ToolContext(workspaceId: "tenant_a")
        )
        guard case let .failed(_, message) = escaped else {
            return XCTFail("traversal should be rejected, got \(escaped)")
        }
        XCTAssertTrue(message.contains("escapes"), "unexpected message: \(message)")
    }
}
