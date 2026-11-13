# Terminal Tools Documentation

Reference documentation for every capability shipped by the terminal tool engine.
Each document describes the architecture, parameters, output schema, and DivKit usage
for one tool.

Tools are invoked either through the unified `tools://<toolId>/<operation>` scheme or
through the versioned registry (`div-action://terminal.tool.invoke?tool=<capability>`).
See the main [`README.md`](../../README.md#native-tools--ai-vision-suite-tools) for the
dispatch contract and the provenance gate.

## Index

| Document | Primary `tools://` URI | Versioned capability | Summary |
| :--- | :--- | :--- | :--- |
| [scan.md](scan.md) | `tools://scan` | `vision.scan_code@1` | Unified 1D barcode & 2D QR optical decoder + OCR text mode. |
| [haptics.md](haptics.md) | `tools://haptics` | `hardware.haptics@1` | Semantic and waveform tactile feedback. |
| [beeper.md](beeper.md) | `tools://beeper` | `hardware.beeper@1` | PCM tone synthesis, profiles, and sequences. |
| [camera.md](camera.md) | `tools://camera` | `hardware.camera@1` (`vision.capture_photo@1` alias) | Camera frame capture and torch control. |
| [image-manipulation.md](image-manipulation.md) | `tools://image-manipulation` | `vision.image_manipulation@1` | On-device image ops & background removal. |
| [embed-image.md](embed-image.md) | — | `vision.embed_image@1` | Image embedding vector (512-dim). |
| [mobilenetv4-small.md](mobilenetv4-small.md) | `tools://MobileNetV4-small` | `vision.identify_product@1` | On-device INT8 image classification / SKU. |
| [u-2-net.md](u-2-net.md) | `tools://u-2-Net.tflite` | `vision.remove_background@1` | Salient object segmentation (alpha matte). |
| [vectorize.md](vectorize.md) | `tools://vectorize` | — | Text/image/OCR embeddings + cosine similarity. |
| [libsignal.md](libsignal.md) | `tools://libsignal` | `secure.send@1`, `secure.status@1`, `secure.verify_identity@1` | Signal E2EE Double Ratchet transport. |
| [biometrics.md](biometrics.md) | `tools://biometrics` | `auth.biometrics@1` | Biometric / PIN authorization gate. |
| [clipboard.md](clipboard.md) | `tools://clipboard` | `device.clipboard@1` | System clipboard copy/paste/clear. |
| [push.md](push.md) | `tools://push` | `network.push@1` | Background alerts & push notifications. |
| [offline-cache.md](offline-cache.md) | `tools://offline-cache` | `storage.offline_cache@1` | Offline page & schema cache. |
| [realtime-ws.md](realtime-ws.md) | `tools://realtime-ws` | `network.realtime@1` | Realtime WSS/SSE channel engine. |
| [net.md](net.md) | `tools://net` | `network.transfer@1` | Ktor HTTP client + embedded LAN transfer hub. |
| [print.md](print.md) | `tools://print` | `device.printer@1` | Bluetooth printer discovery & ESC/POS printing. |
| [code-generate.md](code-generate.md) | `tools://code-generate` | `vision.generate_code@1` | Barcode & QR / matrix code generation (ZXing). |

> `vision.remove_background@1` is reachable through two entry points: the higher-level
> [`image-manipulation.md`](image-manipulation.md) pipeline and the dedicated
> [`u-2-net.md`](u-2-net.md) segmentation model.

## Adding a new tool

When a new capability is added, wire it through every layer described in
[`AGENTS.md`](../../AGENTS.md#tool-capability-architecture-tools) and add a matching
document here plus a row in the index above.
