package com.example.contract

// Generated from packages/contract/tools.json by tools/generate-tool-ids.py. Do not edit by hand.
object ToolIds {
    val ALL: List<String> = listOf(
        "hardware.haptics@1",
        "hardware.beeper@1",
        "vision.scan_code@1",
        "vision.capture_photo@1",
        "vision.embed_image@1",
        "vision.remove_background@1",
        "vision.identify_product@1",
        "secure.send@1",
        "secure.status@1",
        "secure.verify_identity@1",
        "crypto.signal@1",
        "vectorize",
        "auth.biometrics@1",
        "device.clipboard@1",
        "network.push@1",
        "storage.offline_cache@1",
        "network.realtime@1",
        "network.transfer@1",
        "device.printer@1",
        "vision.generate_code@1",
        "device.keep_awake@1",
        "storage.files@1"
    )

    val ALIASES: Map<String, List<String>> = mapOf(
        "hardware.haptics@1" to listOf("device.haptics@1", "haptics.perform@1", "haptics", "device.haptics"),
        "hardware.beeper@1" to listOf("device.beeper@1", "beeper", "device.beeper", "tone"),
        "vision.scan_code@1" to listOf("scan-code", "scan_barcode", "scan-qr"),
        "vision.capture_photo@1" to listOf("camera", "capture_photo"),
        "vision.embed_image@1" to listOf(),
        "vision.remove_background@1" to listOf("image-background-remove", "u-2-Net.tflite"),
        "vision.identify_product@1" to listOf("MobileNetV4-small", "identify_product"),
        "secure.send@1" to listOf("libsignal", "secure_send"),
        "secure.status@1" to listOf("secure_status", "signal_status"),
        "secure.verify_identity@1" to listOf("secure_verify", "verify_identity"),
        "crypto.signal@1" to listOf("signal", "crypto.signal"),
        "vectorize" to listOf("vector", "embed", "embed-text", "embed-image"),
        "auth.biometrics@1" to listOf("biometrics", "auth.biometrics", "supervisor_auth"),
        "device.clipboard@1" to listOf("clipboard", "device.clipboard"),
        "network.push@1" to listOf("push", "network.push", "alerts"),
        "storage.offline_cache@1" to listOf("offline-cache", "offline_cache", "storage.offline_cache"),
        "network.realtime@1" to listOf("realtime-ws", "realtime_ws", "network.realtime", "realtime"),
        "network.transfer@1" to listOf("net", "network.transfer", "tools.net"),
        "device.printer@1" to listOf("print", "printer", "device.printer"),
        "vision.generate_code@1" to listOf("code-generate", "code_gen", "generate-code", "barcode-generate", "qr-generate", "vision.generate_code"),
        "device.keep_awake@1" to listOf("keep-awake", "keep_awake", "keepawake", "device.keep_awake", "wakelock"),
        "storage.files@1" to listOf("files", "file", "storage.files", "file-store", "fs")
    )
}
