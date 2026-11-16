import Foundation

/// iOS capability registry mirroring `engine.tools.ToolRegistry`. Resolves canonical ids
/// and aliases declared in packages/contract/tools.json.
public final class TerminalRegistry: @unchecked Sendable {
    public static let shared = TerminalRegistry()

    private let lock = NSLock()
    private var handlers: [String: ToolHandler] = [:]

    public init() {}

    @discardableResult
    public func register(_ handler: ToolHandler) -> TerminalRegistry {
        lock.lock(); defer { lock.unlock() }
        handlers[handler.capability] = handler
        for alias in TerminalRegistry.aliases[handler.capability] ?? [] {
            handlers[alias] = handler
        }
        return self
    }

    public func resolve(_ capability: String) -> ToolHandler? {
        lock.lock(); defer { lock.unlock() }
        return handlers[capability]
    }

    public func manifest() -> [String: Int] {
        lock.lock(); defer { lock.unlock() }
        return handlers.keys.reduce(into: [:]) { $0[$1] = 1 }
    }

    /// Canonical id -> aliases. Kept in sync with packages/contract/tools.json.
    public static let aliases: [String: [String]] = [
        "hardware.haptics@1": ["device.haptics@1", "haptics.perform@1", "haptics", "device.haptics"],
        "hardware.beeper@1": ["device.beeper@1", "beeper", "device.beeper", "tone"],
        "vision.scan_code@1": ["scan-code", "scan_barcode", "scan-qr"],
        "vision.capture_photo@1": ["camera", "capture_photo"],
        "vision.embed_image@1": [],
        "vision.remove_background@1": ["image-background-remove", "u-2-Net.tflite"],
        "vision.identify_product@1": ["MobileNetV4-small", "identify_product"],
        "secure.send@1": ["libsignal", "secure_send"],
        "secure.status@1": ["secure_status", "signal_status"],
        "secure.verify_identity@1": ["secure_verify", "verify_identity"],
        "crypto.signal@1": ["signal", "crypto.signal"],
        "vectorize": ["vector", "embed", "embed-text", "embed-image"],
        "auth.biometrics@1": ["biometrics", "auth.biometrics", "supervisor_auth"],
        "device.clipboard@1": ["clipboard", "device.clipboard"],
        "network.push@1": ["push", "network.push", "alerts"],
        "storage.offline_cache@1": ["offline-cache", "offline_cache", "storage.offline_cache"],
        "network.realtime@1": ["realtime-ws", "realtime_ws", "network.realtime", "realtime"],
        "network.transfer@1": ["net", "network.transfer", "tools.net"],
        "device.printer@1": ["print", "printer", "device.printer"],
        "vision.generate_code@1": ["code-generate", "code_gen", "generate-code", "barcode-generate", "qr-generate", "vision.generate_code"],
    ]
}
