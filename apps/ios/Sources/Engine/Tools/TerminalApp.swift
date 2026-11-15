import Foundation
import Contract

/// Composition root for the iOS Terminal Tool Engine. Mirrors `engine.tools.TerminalApp`.
///
/// Single point of registration for all versioned capabilities. The canonical capability ids
/// and the shorthand aliases registered here are kept in lockstep with the Android
/// `TerminalApp` and with packages/contract/tools.json.
public enum TerminalApp {

    public static let registry: ToolRegistry = buildRegistry()
    public static let router: DivActionRouter = DivActionRouter(registry: registry)

    public static func buildRegistry() -> ToolRegistry {
        let handlers: [ToolHandler] = [
            HapticsTool(),
            BeeperTool(),
            ScanCodeTool(),
            CapturePhotoTool(),
            EmbedImageTool(),
            RemoveBackgroundTool(),
            ProductClassifierTool(),
            SecureSendTool(),
            SecureStatusTool(),
            SecureVerifyIdentityTool(),
            BiometricsTool(),
            ClipboardTool(),
            PushAlertTool(),
            OfflineCacheTool(),
            RealtimeWsTool(),
            NetTool(),
            PrintTool(),
            CodeGenerateTool(),
            LibSignalTool(),
            VectorizeTool()
        ]

        var handlerMap: [String: ToolHandler] = [:]
        for h in handlers {
            handlerMap[h.capability] = h
            for alias in TerminalApp.aliases[h.capability] ?? [] {
                handlerMap[alias] = h
            }
        }
        return ToolRegistry(handlers: handlerMap)
    }

    /// Canonical capability id -> shorthand aliases. Mirrors the `when (h.capability)` block in
    /// the Android `TerminalApp.buildRegistry` (and `TerminalRegistry.aliases`).
    public static let aliases: [String: [String]] = [
        "hardware.haptics@1": ["device.haptics@1", "haptics.perform@1", "haptics", "device.haptics"],
        "hardware.beeper@1": ["device.beeper@1", "beeper", "device.beeper", "tone"],
        "vision.scan_code@1": ["scan-code", "scan_barcode", "scan-qr"],
        "vision.capture_photo@1": ["camera", "capture_photo"],
        "vision.identify_product@1": ["MobileNetV4-small", "identify_product"],
        "vision.remove_background@1": ["image-background-remove", "u-2-Net.tflite"],
        "secure.send@1": ["libsignal", "secure_send"],
        "secure.status@1": ["secure_status", "signal_status"],
        "secure.verify_identity@1": ["secure_verify", "verify_identity"],
        "auth.biometrics@1": ["biometrics", "auth.biometrics", "supervisor_auth"],
        "device.clipboard@1": ["clipboard", "device.clipboard"],
        "network.push@1": ["push", "network.push", "alerts"],
        "storage.offline_cache@1": ["offline-cache", "offline_cache", "storage.offline_cache"],
        "network.realtime@1": ["realtime-ws", "realtime_ws", "network.realtime", "realtime"],
        "network.transfer@1": ["net", "network.transfer", "tools.net"],
        "device.printer@1": ["print", "printer", "device.printer"],
        "vision.generate_code@1": ["code-generate", "code_gen", "generate-code", "barcode-generate", "qr-generate", "vision.generate_code"],
        "crypto.signal@1": ["signal", "crypto.signal"],
        "vectorize": ["vector", "embed", "embed-text", "embed-image"]
    ]
}
