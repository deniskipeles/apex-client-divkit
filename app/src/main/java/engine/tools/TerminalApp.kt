package engine.tools

import engine.tools.impl.*

/**
 * Composition Root for the Terminal Tool Engine.
 * Single point of registration for all versioned capabilities.
 */
object TerminalApp {

    val registry: ToolRegistry by lazy { buildRegistry() }
    val router: DivActionRouter by lazy { DivActionRouter(registry) }

    fun buildRegistry(): ToolRegistry {
        val handlers = listOf<ToolHandler>(
            HapticsTool(),
            BeeperTool(),
            ScanCodeTool(),
            CapturePhotoTool(),
            EmbedImageTool(),
            RemoveBackgroundTool(),
            IdentifyProductTool(),
            SecureSendTool(),
            SecureStatusTool(),
            SecureVerifyIdentityTool(),
            BiometricsTool(),
            ClipboardTool(),
            PushAlertTool(),
            OfflineCacheTool(),
            RealtimeWsTool()
        )
        // Map canonical capabilities + common shorthand aliases
        val handlerMap = mutableMapOf<String, ToolHandler>()
        handlers.forEach { h ->
            handlerMap[h.capability] = h
            // Register canonical shortcuts
            when (h.capability) {
                "device.haptics@1" -> {
                    handlerMap["haptics.perform@1"] = h
                    handlerMap["hardware.haptics@1"] = h
                    handlerMap["haptics"] = h
                    handlerMap["device.haptics"] = h
                }
                "device.beeper@1" -> {
                    handlerMap["beeper"] = h
                    handlerMap["hardware.beeper@1"] = h
                    handlerMap["device.beeper"] = h
                    handlerMap["tone"] = h
                }
                "vision.scan_code@1" -> {
                    handlerMap["scan-code"] = h
                    handlerMap["scan_barcode"] = h
                    handlerMap["scan-qr"] = h
                }
                "vision.capture_photo@1" -> {
                    handlerMap["camera"] = h
                    handlerMap["capture_photo"] = h
                }
                "vision.identify_product@1" -> {
                    handlerMap["MobileNetV4-small"] = h
                    handlerMap["identify_product"] = h
                }
                "vision.remove_background@1" -> {
                    handlerMap["image-background-remove"] = h
                    handlerMap["u-2-Net.tflite"] = h
                }
                "secure.send@1" -> {
                    handlerMap["libsignal"] = h
                    handlerMap["secure_send"] = h
                }
                "secure.status@1" -> {
                    handlerMap["secure_status"] = h
                    handlerMap["signal_status"] = h
                }
                "secure.verify_identity@1" -> {
                    handlerMap["secure_verify"] = h
                    handlerMap["verify_identity"] = h
                }
                "auth.biometrics@1" -> {
                    handlerMap["biometrics"] = h
                    handlerMap["auth.biometrics"] = h
                    handlerMap["supervisor_auth"] = h
                }
                "device.clipboard@1" -> {
                    handlerMap["clipboard"] = h
                    handlerMap["device.clipboard"] = h
                }
                "network.push@1" -> {
                    handlerMap["push"] = h
                    handlerMap["network.push"] = h
                    handlerMap["alerts"] = h
                }
                "storage.offline_cache@1" -> {
                    handlerMap["offline-cache"] = h
                    handlerMap["offline_cache"] = h
                    handlerMap["storage.offline_cache"] = h
                }
                "network.realtime@1" -> {
                    handlerMap["realtime-ws"] = h
                    handlerMap["realtime_ws"] = h
                    handlerMap["network.realtime"] = h
                    handlerMap["realtime"] = h
                }
            }
        }
        return ToolRegistry(handlerMap)
    }
}
