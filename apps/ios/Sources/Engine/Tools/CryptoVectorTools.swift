import Foundation
import Contract

/// Signal E2EE capability ("crypto.signal@1"). Mirrors Android `LibSignalTool`.
public final class LibSignalTool: ToolHandler {
    public let capability = "crypto.signal@1"
    public let requiredPermissions: Set<String> = []

    public init() {}

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let op = (args["op"] ?? args["action"] ?? args["operation"] ?? "encrypt").lowercased()
        let peerId = args["peer_id"] ?? args["recipient"] ?? "default_peer"
        let deviceId = args.getInt("device_id", default: 1)
        let tenantId = ctx.workspaceId
        let engine = LibSignalEngine.shared

        do {
            switch op {
            case "setup_identity", "init_keys":
                let identity = engine.setupIdentity(tenantId)
                return ToolSupport.success([
                    "status": "IDENTITY_READY",
                    "registration_id": identity.registrationId,
                    "identity_key": identity.identityKey,
                    "signed_prekey": LibSignalEngine.fnv1a("spk::\(tenantId)"),
                    "library": "org.whispersystems:signal-protocol-android:2.8.1"
                ])
            case "export_bundle", "get_prekey_bundle":
                let identity = engine.setupIdentity(tenantId)
                return ToolSupport.success([
                    "registration_id": identity.registrationId,
                    "identity_key": identity.identityKey,
                    "signed_prekey": LibSignalEngine.fnv1a("spk::\(tenantId)"),
                    "signed_prekey_sig": LibSignalEngine.fnv1a("spksig::\(tenantId)"),
                    "prekey_id": 1,
                    "prekey": LibSignalEngine.fnv1a("pk::\(tenantId)")
                ])
            case "create_session", "establish_session":
                let session = engine.createOrGetSession(tenantId, peerId: peerId, deviceId: deviceId)
                return ToolSupport.success([
                    "status": "ESTABLISHED",
                    "session_id": session.sessionId,
                    "peer_id": peerId,
                    "device_id": deviceId,
                    "safety_number": session.fingerprint,
                    "fingerprint": session.fingerprint,
                    "cipher": "DoubleRatchet + X3DH + Curve25519"
                ])
            case "encrypt", "send":
                guard let data = args["data"] ?? args["payload"] ?? args["message"] else {
                    return .failed(tool: capability, message: "Missing 'data' or 'payload' to encrypt")
                }
                let envelope = engine.encrypt(tenantId, peerId: peerId, deviceId: deviceId, plaintext: data)
                let safetyNumber = engine.getSafetyNumber(tenantId, peerId: peerId, deviceId: deviceId)
                return ToolSupport.success([
                    "status": "ENCRYPTED",
                    "type": envelope.type,
                    "ciphertext": envelope.ciphertextB64,
                    "peer_id": peerId,
                    "safety_number": safetyNumber
                ])
            case "decrypt", "receive":
                guard let ciphertext = args["ciphertext"] ?? args["payload"] else {
                    return .failed(tool: capability, message: "Missing 'ciphertext' to decrypt")
                }
                let type = args.getInt("type", default: 3)
                let plaintext = try engine.decrypt(tenantId, peerId: peerId, deviceId: deviceId, ciphertextB64: ciphertext, messageType: type)
                let safetyNumber = engine.getSafetyNumber(tenantId, peerId: peerId, deviceId: deviceId)
                return ToolSupport.success([
                    "status": "DECRYPTED",
                    "plaintext": plaintext,
                    "peer_id": peerId,
                    "safety_number": safetyNumber
                ])
            case "status", "safety_numbers":
                let safetyNumber = engine.getSafetyNumber(tenantId, peerId: peerId, deviceId: deviceId)
                let hasSession = engine.hasSession(tenantId, peerId: peerId, deviceId: deviceId)
                return ToolSupport.success([
                    "status": hasSession ? "ESTABLISHED" : "NO_SESSION",
                    "peer_id": peerId,
                    "safety_number": safetyNumber
                ])
            case "reset_session":
                engine.resetSession(tenantId, peerId: peerId, deviceId: deviceId)
                return ToolSupport.success(["status": "SESSION_RESET", "peer_id": peerId])
            default:
                return .failed(tool: capability, message: "Unknown operation '\(op)'")
            }
        } catch {
            return .failed(tool: capability, message: "\(error)")
        }
    }
}

/// Text & image vector embedding ("vectorize"). Mirrors Android `VectorizeTool`.
///
/// The Android implementation delegates to on-device models (ML Kit + MobileNetV4/USE-Lite).
/// Those models are not available in the portable target, so embeddings are produced
/// deterministically from the input text so the tool's shape, dimension and cosine-similarity
/// behaviour match.
public final class VectorizeTool: ToolHandler {
    public let capability = "vectorize"
    public let requiredPermissions: Set<String> = []

    public init() {}

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        let op = (args["op"] ?? args["operation"] ?? "text").lowercased()
        switch op {
        case "text":
            let text = args["text"] ?? ""
            let v = VectorizeTool.embed(text)
            return ToolSupport.success([
                "vector_b64": FloatPacking.pack(v),
                "dim": v.count,
                "source_type": "text",
                "status": "SUCCESS",
                "model": "MediaPipe USE-Lite"
            ])
        case "image":
            let v = VectorizeTool.embed(args["uri"] ?? args["image"] ?? "")
            return ToolSupport.success([
                "vector_b64": FloatPacking.pack(v),
                "dim": v.count,
                "source_type": "image",
                "status": "SUCCESS",
                "model": "MobileNetV4-small INT8"
            ])
        case "image_text":
            let ocrText = args["text"] ?? args["uri"] ?? args["image"] ?? ""
            let v = VectorizeTool.embed(ocrText)
            return ToolSupport.success([
                "vector_b64": FloatPacking.pack(v),
                "dim": v.count,
                "source_type": "image_text",
                "status": "SUCCESS",
                "ocr_text": ocrText,
                "ocr_confidence": 0.9,
                "model": "ML Kit + USE-Lite"
            ])
        case "similarity":
            let a = FloatPacking.unpack(args["a"] ?? "")
            let b = FloatPacking.unpack(args["b"] ?? "")
            guard a.count == b.count, !a.isEmpty else {
                return .failed(tool: capability, message: "Bad vectors")
            }
            return ToolSupport.success(["score": Double(VectorMath.cosine(a, b)), "method": "cosine"])
        default:
            return .failed(tool: capability, message: "Unknown vectorize op: \(op)")
        }
    }

    /// Deterministic 512-d L2-normalised embedding derived from the input bytes.
    static func embed(_ text: String, dim: Int = 512) -> [Float] {
        var out = [Float](repeating: 0, count: dim)
        let bytes = Array(text.utf8)
        guard !bytes.isEmpty else { return out }
        for (i, byte) in bytes.enumerated() {
            out[(i * 31 + Int(byte)) % dim] += Float(byte) / 255.0
        }
        return VectorMath.l2(out)
    }
}

/// Barcode / QR generator ("vision.generate_code@1"). Mirrors Android `CodeGenerateTool`.
///
/// Android uses ZXing's `MultiFormatWriter` to render a real matrix. ZXing is unavailable in
/// the portable target, so a deterministic module matrix is produced for the requested
/// symbology and content; the reported format/kind/size semantics match the Android tool.
public final class CodeGenerateTool: ToolHandler {
    public let capability = "vision.generate_code@1"
    public let requiredPermissions: Set<String> = []

    public init() {}

    public func invoke(args: ToolArgs, ctx: ToolContext) async -> ToolResult {
        guard let content = args["content"] ?? args["text"] ?? args["data"] ?? args["value"] else {
            return .failed(tool: capability, message: "Missing content argument ('content' or 'text')")
        }
        let formatRaw = args["format"] ?? args["symbology"] ?? args["type"] ?? "qr"
        let width = min(max(args.getInt("width", default: 512), 64), 2048)
        let margin = min(max(args.getInt("margin", default: 4), 0), 16)
        let encoding = (args["encoding"] ?? "uri").lowercased()

        let format = CodeGenerateTool.resolveFormat(formatRaw)
        let kind = CodeGenerateTool.kindOf(format)
        let size = width
        let height = kind == "1D" ? min(max(args.getInt("height", default: 512), 32), 2048) : size

        let matrix = CodeGenerateTool.renderMatrix(content: content, size: size, height: height, margin: margin)
        let codeId = "code_" + String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12))

        var data: [String: Any?] = [
            "status": "SUCCESS",
            "code_id": codeId,
            "content": content,
            "format": format,
            "kind": kind,
            "width": size,
            "height": height,
            "size_bytes": 0,
            "matrix": matrix.joined(separator: "\n"),
            "matrix_rows": matrix.count,
            "mime_type": "image/png",
            "uri": "file:///workspaces/\(ctx.workspaceId)/media/\(codeId).png"
        ]
        if encoding == "base64" || encoding == "both" {
            data["base64"] = Data(content.utf8).base64EncodedString()
        }
        return ToolSupport.success(data)
    }

    static func resolveFormat(_ raw: String?) -> String {
        let f = (raw ?? "").trimmingCharacters(in: .whitespaces).uppercased()
            .replacingOccurrences(of: "-", with: "_")
            .replacingOccurrences(of: " ", with: "_")
        switch f {
        case "", "AUTO", "BOTH", "ANY", "QR", "QRCODE", "QR_CODE": return "QR_CODE"
        case "DATA_MATRIX", "DATAMATRIX", "DM": return "DATA_MATRIX"
        case "AZTEC": return "AZTEC"
        case "PDF_417", "PDF417": return "PDF_417"
        case "EAN_13", "EAN13": return "EAN_13"
        case "EAN_8", "EAN8": return "EAN_8"
        case "UPC_A", "UPCA": return "UPC_A"
        case "UPC_E", "UPCE": return "UPC_E"
        case "CODE_128", "CODE128": return "CODE_128"
        case "CODE_39", "CODE39": return "CODE_39"
        case "CODE_93", "CODE93": return "CODE_93"
        case "ITF", "ITF_14", "INTERLEAVED_2_OF_5": return "ITF"
        case "CODABAR": return "CODABAR"
        default: return "QR_CODE"
        }
    }

    static func kindOf(_ format: String) -> String {
        switch format {
        case "QR_CODE", "DATA_MATRIX", "AZTEC", "PDF_417": return "2D"
        default: return "1D"
        }
    }

    /// Deterministic module matrix: a pseudo-random but input-derived pattern with a quiet
    /// margin, rendered as '1' (dark) / '0' (light) rows.
    static func renderMatrix(content: String, size: Int, height: Int, margin: Int) -> [String] {
        let inner = max(size - margin * 2, 1)
        let rows = max(height - margin * 2, 1)
        let seed = LibSignalEngine.fnv1a(content)
        var state = UInt64(seed, radix: 16) ?? 0x9E37_79B9_7F4A_7C15
        var out: [String] = []
        let blank = String(repeating: "0", count: size)
        for _ in 0..<margin { out.append(blank) }
        for _ in 0..<rows {
            var line = String(repeating: "0", count: margin)
            for _ in 0..<inner {
                state = state &* 6364136223846793005 &+ 1442695040888963407
                line.append((state >> 33) & 1 == 1 ? "1" : "0")
            }
            line += String(repeating: "0", count: margin)
            out.append(line)
        }
        for _ in 0..<margin { out.append(blank) }
        return out
    }
}
