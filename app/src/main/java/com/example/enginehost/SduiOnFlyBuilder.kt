package com.example.enginehost

import kotlinx.serialization.json.*

object SduiOnFlyBuilder {

    private val prettyJson = Json { prettyPrint = true }

    /**
     * Dynamically injects a new DivKit component node into the root container of an existing
     * DivKit JSON card on the fly.
     */
    fun injectBlockIntoCard(currentCardJson: String, newBlock: JsonObject, prepend: Boolean = false): String {
        val rootObj = Json.parseToJsonElement(currentCardJson).jsonObject
        val cardObj = rootObj["card"]?.jsonObject ?: return currentCardJson
        val statesArr = cardObj["states"]?.jsonArray ?: return currentCardJson
        if (statesArr.isEmpty()) return currentCardJson
        val firstState = statesArr[0].jsonObject
        val divObj = firstState["div"]?.jsonObject ?: return currentCardJson
        val existingItems = divObj["items"]?.jsonArray?.toMutableList() ?: mutableListOf()

        if (prepend && existingItems.isNotEmpty()) {
            existingItems.add(1.coerceAtMost(existingItems.size), newBlock)
        } else {
            existingItems.add(newBlock)
        }

        val updatedDiv = JsonObject(divObj + ("items" to JsonArray(existingItems)))
        val updatedFirstState = JsonObject(firstState + ("div" to updatedDiv))
        val updatedStates = JsonArray(listOf(updatedFirstState) + statesArr.drop(1))
        val updatedCard = JsonObject(cardObj + ("states" to updatedStates))
        val updatedRoot = JsonObject(rootObj + ("card" to updatedCard))
        return prettyJson.encodeToString(JsonObject.serializer(), updatedRoot)
    }

    fun buildQuickSaleButtonBlock(
        spec: TenantWorkspaceSpec,
        productName: String,
        sku: String,
        price: Double,
        colorHex: String
    ): JsonObject = buildJsonObject {
        put("type", "button")
        put("id", "onfly_${sku.lowercase()}")
        put("text", "⚡ $productName ($$price)")
        put("subtitle", "On-the-Fly DivKit Button • SKU: $sku → db.insert")
        put("icon", "cart_add")
        put("background_color", colorHex)
        put("text_color", "#F8FAFC")
        put("corner_radius", 12)
        putJsonArray("actions") {
            add(WorkspaceProvisioner.buildInsertOrderAction(spec, productName, sku, price.toString()))
            add(WorkspaceProvisioner.buildQueryOrdersAction())
            add(WorkspaceProvisioner.buildCountOrdersAction())
        }
    }

    fun buildMetricBannerBlock(
        title: String,
        variableExpr: String,
        accentHex: String
    ): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "horizontal")
        put("background_color", "#1E293B")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 14); put("right", 14); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", accentHex)
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("weight", 1f)
                put("text", title)
                put("font_size", 13)
                put("font_weight", "bold")
                put("text_color", "#E2E8F0")
            }
            addJsonObject {
                put("type", "text")
                put("text", variableExpr)
                put("font_size", 14)
                put("font_weight", "bold")
                put("text_color", accentHex)
                put("font_family", "monospace")
            }
        }
    }

    fun buildHardwareTriggerBlock(
        label: String,
        capability: String,
        command: String,
        terminalCode: String
    ): JsonObject = buildJsonObject {
        put("type", "button")
        put("id", "onfly_hw_${capability}_$command")
        put("text", "$label (hardware://$capability/$command)")
        put("icon", capability)
        put("background_color", "#312E81")
        put("text_color", "#C7D2FE")
        put("corner_radius", 10)
        putJsonObject("action") {
            put("log_id", "hw_${capability}_$command")
            put("url", "hardware://$capability/$command")
            putJsonObject("payload") {
                put("terminal", terminalCode)
                put("source", "sdui_on_fly_builder")
            }
        }
    }

    /**
     * Builds a generalized DivKit action button for triggering any native tool
     * under the `tools://` scheme with customizable options and output mappings.
     */
    fun buildNativeToolTriggerBlock(
        toolId: String,
        operation: String,
        label: String,
        subtitle: String,
        icon: String,
        backgroundColor: String,
        textColor: String,
        options: Map<String, String> = emptyMap(),
        outputMapping: Map<String, String> = emptyMap()
    ): JsonObject = buildJsonObject {
        put("type", "button")
        put("id", "tool_${toolId.replace(".", "_")}_$operation")
        put("text", label)
        put("subtitle", subtitle)
        put("icon", icon)
        put("background_color", backgroundColor)
        put("text_color", textColor)
        put("corner_radius", 10)
        putJsonObject("action") {
            put("log_id", "exec_tool_${toolId}_$operation")
            put("url", "tools://$toolId/$operation")
            putJsonObject("payload") {
                put("operation", operation)
                putJsonObject("options") {
                    options.forEach { (k, v) -> put(k, v) }
                }
                putJsonObject("output") {
                    outputMapping.forEach { (k, v) -> put(k, v) }
                }
            }
        }
    }

    /**
     * MobileNetV4-Small (INT8 Quantized) AI SKU Detection Tool Block
     */
    fun buildMobileNetV4ClassifyToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#1E1B4B")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#6366F1")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "🧠 MobileNetV4-Small INT8 AI SKU DETECTOR (tools://MobileNetV4-small)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#A5B4FC")
            }
            addJsonObject {
                put("type", "text")
                put("text", "AI Prediction: @{user.ai_prediction_label} • Conf: @{user.ai_confidence_score} • Latency: @{user.tool_last_latency}ms")
                put("font_size", 12)
                put("text_color", "#E0E7FF")
                put("font_family", "monospace")
            }
            add(
                buildNativeToolTriggerBlock(
                    toolId = "MobileNetV4-small",
                    operation = "classify",
                    label = "⚡ Detect Item via MobileNetV4-Small INT8",
                    subtitle = "tools://MobileNetV4-small/classify • 0.2ms latency, 3.8MB INT8",
                    icon = "camera",
                    backgroundColor = "#4F46E5",
                    textColor = "#FFFFFF",
                    options = mapOf("top_k" to "3", "min_confidence" to "0.40"),
                    outputMapping = mapOf(
                        "prediction_label" to "user.ai_prediction_label",
                        "prediction_sku" to "user.ai_prediction_sku",
                        "confidence" to "user.ai_confidence_score"
                    )
                )
            )
        }
    }

    /**
     * U-2-Net.tflite Image Background Removal Block
     */
    fun buildU2NetToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#142834")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#06B6D4")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "🪄 U-2-Net.tflite SALIENT OBJECT SEGMENTATION (tools://u-2-Net.tflite)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#67E8F9")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Matte Status: @{user.bg_remove_status} • Mask: @{user.u2net_mask_uri}")
                put("font_size", 12)
                put("text_color", "#E0F2FE")
                put("font_family", "monospace")
            }
            add(
                buildNativeToolTriggerBlock(
                    toolId = "u-2-Net.tflite",
                    operation = "segment",
                    label = "Extract Foreground Alpha Matte (u-2-Net.tflite)",
                    subtitle = "tools://u-2-Net.tflite/segment • 320x320 INT8 Saliency Map",
                    icon = "crop",
                    backgroundColor = "#0891B2",
                    textColor = "#FFFFFF",
                    options = mapOf("saliency_cutoff" to "0.45", "quantization" to "INT8"),
                    outputMapping = mapOf(
                        "mask_uri" to "user.u2net_mask_uri",
                        "status" to "user.bg_remove_status"
                    )
                )
            )
        }
    }

    /**
     * Optical 1D Barcode, 2D QR & OCR Block (tools://scan).
     */
    fun buildScannerToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#0F291E")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#10B981")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "📷 UNIFIED OPTICAL SCANNER + OCR (tools://scan)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#6EE7B7")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Decoded: @{user.scanned_code} (@{user.scanned_format} • @{user.scanned_type}) → @{user.scan_status}")
                put("font_size", 12)
                put("text_color", "#D1FAE5")
                put("font_family", "monospace")
            }
            addJsonObject {
                put("type", "text")
                put("text", "OCR: @{user.ocr_text} (conf @{user.ocr_confidence})")
                put("font_size", 12)
                put("text_color", "#A7F3D0")
                put("font_family", "monospace")
            }
            addJsonObject {
                put("type", "container")
                put("orientation", "horizontal")
                put("item_spacing", 8)
                putJsonArray("items") {
                    addJsonObject {
                        put("type", "container")
                        put("weight", 1f)
                        putJsonArray("items") {
                            add(
                                buildNativeToolTriggerBlock(
                                    toolId = "scan",
                                    operation = "scan",
                                    label = "Scan 1D Barcode",
                                    subtitle = "tools://scan/scan (EAN-13 / UPC)",
                                    icon = "scanner",
                                    backgroundColor = "#059669",
                                    textColor = "#FFFFFF",
                                    options = mapOf("target_format" to "barcode"),
                                    outputMapping = mapOf(
                                        "raw_value" to "user.scanned_code",
                                        "format" to "user.scanned_format",
                                        "type" to "user.scanned_type",
                                        "status" to "user.scan_status"
                                    )
                                )
                            )
                        }
                    }
                    addJsonObject {
                        put("type", "container")
                        put("weight", 1f)
                        putJsonArray("items") {
                            add(
                                buildNativeToolTriggerBlock(
                                    toolId = "scan",
                                    operation = "scan",
                                    label = "Scan 2D QR Code",
                                    subtitle = "tools://scan/scan (DeepLink / Pay)",
                                    icon = "qr_code",
                                    backgroundColor = "#0D9488",
                                    textColor = "#FFFFFF",
                                    options = mapOf("target_format" to "qr"),
                                    outputMapping = mapOf(
                                        "raw_value" to "user.scanned_code",
                                        "format" to "user.scanned_format",
                                        "type" to "user.scanned_type",
                                        "status" to "user.scan_status"
                                    )
                                )
                            )
                        }
                    }
                }
            }
            add(
                buildNativeToolTriggerBlock(
                    toolId = "scan",
                    operation = "ocr",
                    label = "Read Text from Image (OCR)",
                    subtitle = "tools://scan/ocr • ML Kit Text Recognition v2 (Latin)",
                    icon = "search",
                    backgroundColor = "#047857",
                    textColor = "#FFFFFF",
                    options = mapOf("uri" to "content://com.example.provider/camera/last_capture.png"),
                    outputMapping = mapOf(
                        "text" to "user.ocr_text",
                        "confidence" to "user.ocr_confidence",
                        "block_count" to "user.ocr_block_count"
                    )
                )
            )
        }
    }

    /**
     * Barcode & QR Code Generator Block (vision.generate_code@1 / tools://code-generate).
     */
    fun buildCodeGenerateToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#2A1F0B")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#F59E0B")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "🔳 BARCODE & QR CODE GENERATOR (tools://code-generate)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#FCD34D")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Generated: @{user.code_format} (@{user.code_kind}) • @{user.code_id}")
                put("font_size", 12)
                put("text_color", "#FEF3C7")
                put("font_family", "monospace")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Content: @{user.code_content}")
                put("font_size", 11)
                put("text_color", "#FDE68A")
                put("font_family", "monospace")
            }
            addJsonObject {
                put("type", "text")
                put("text", "PNG: @{user.code_image_uri}")
                put("font_size", 11)
                put("text_color", "#FDE68A")
                put("font_family", "monospace")
            }
            addJsonObject {
                put("type", "container")
                put("orientation", "horizontal")
                put("item_spacing", 8)
                putJsonArray("items") {
                    addJsonObject {
                        put("type", "container")
                        put("weight", 1f)
                        putJsonArray("items") {
                            add(
                                buildNativeToolTriggerBlock(
                                    toolId = "code-generate",
                                    operation = "generate",
                                    label = "Generate Order QR",
                                    subtitle = "tools://code-generate/generate (QR_CODE)",
                                    icon = "qr_code",
                                    backgroundColor = "#D97706",
                                    textColor = "#FFFFFF",
                                    options = mapOf(
                                        "content" to "apexpos://order/pickup?terminal=${spec.terminalCode}",
                                        "format" to "qr",
                                        "width" to "512",
                                        "ec_level" to "M",
                                        "encoding" to "uri"
                                    ),
                                    outputMapping = mapOf(
                                        "uri" to "user.code_image_uri",
                                        "format" to "user.code_format",
                                        "kind" to "user.code_kind",
                                        "content" to "user.code_content",
                                        "code_id" to "user.code_id"
                                    )
                                )
                            )
                        }
                    }
                    addJsonObject {
                        put("type", "container")
                        put("weight", 1f)
                        putJsonArray("items") {
                            add(
                                buildNativeToolTriggerBlock(
                                    toolId = "code-generate",
                                    operation = "barcode",
                                    label = "Generate SKU Barcode",
                                    subtitle = "tools://code-generate/barcode (EAN_13)",
                                    icon = "barcode",
                                    backgroundColor = "#B45309",
                                    textColor = "#FFFFFF",
                                    options = mapOf(
                                        "content" to "9780201633610",
                                        "format" to "EAN_13",
                                        "width" to "400",
                                        "height" to "160"
                                    ),
                                    outputMapping = mapOf(
                                        "uri" to "user.code_image_uri",
                                        "format" to "user.code_format",
                                        "kind" to "user.code_kind",
                                        "content" to "user.code_content",
                                        "code_id" to "user.code_id"
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * LibSignal End-to-End Encrypted Terminal Session Block
     */
    fun buildLibSignalToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#2E1065")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#8B5CF6")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "🔒 LIBSIGNAL E2EE SESSION (tools://libsignal)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#C4B5FD")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Session: @{user.signal_session_id} • Fingerprint: @{user.signal_fingerprint} • Status: @{user.signal_status}")
                put("font_size", 12)
                put("text_color", "#EDE9FE")
                put("font_family", "monospace")
            }
            add(
                buildNativeToolTriggerBlock(
                    toolId = "libsignal",
                    operation = "create_session",
                    label = "Establish E2EE Signal Ratchet Session",
                    subtitle = "tools://libsignal/create_session • Curve25519 Double Ratchet",
                    icon = "lock",
                    backgroundColor = "#7C3AED",
                    textColor = "#FFFFFF",
                    options = mapOf("cipher" to "AES-256-GCM"),
                    outputMapping = mapOf(
                        "status" to "user.signal_status",
                        "session_id" to "user.signal_session_id",
                        "fingerprint" to "user.signal_fingerprint",
                        "ciphertext" to "user.signal_ciphertext"
                    )
                )
            )
        }
    }

    /**
     * Tactile Haptics Suite Block (device.haptics@1 / tools://haptics)
     */
    fun buildHapticsToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#1F2937")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#F59E0B")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "📳 HAPTIC TACTILE ENGINE (device.haptics@1 • tools://haptics)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#FCD34D")
            }
            addJsonObject {
                put("type", "text")
                put("text", "State: @{user.haptic_state} • Pattern: @{user.haptic_pattern}")
                put("font_size", 12)
                put("text_color", "#FEF3C7")
                put("font_family", "monospace")
            }
            addJsonObject {
                put("type", "container")
                put("orientation", "horizontal")
                put("item_spacing", 8)
                putJsonArray("items") {
                    addJsonObject {
                        put("type", "button")
                        put("weight", 1f)
                        put("text", "Click")
                        put("background_color", "#374151")
                        put("text_color", "#F9FAFB")
                        put("corner_radius", 8)
                        putJsonObject("action") {
                            put("log_id", "haptic_click")
                            put("url", "div-action://terminal.tool.invoke?tool=device.haptics@1&pattern=click&result_var=haptic_state")
                        }
                    }
                    addJsonObject {
                        put("type", "button")
                        put("weight", 1f)
                        put("text", "Heavy")
                        put("background_color", "#D97706")
                        put("text_color", "#FFFFFF")
                        put("corner_radius", 8)
                        putJsonObject("action") {
                            put("log_id", "haptic_heavy")
                            put("url", "div-action://terminal.tool.invoke?tool=device.haptics@1&pattern=heavy_click&result_var=haptic_state")
                        }
                    }
                    addJsonObject {
                        put("type", "button")
                        put("weight", 1f)
                        put("text", "Success")
                        put("background_color", "#059669")
                        put("text_color", "#FFFFFF")
                        put("corner_radius", 8)
                        putJsonObject("action") {
                            put("log_id", "haptic_success")
                            put("url", "div-action://terminal.tool.invoke?tool=device.haptics@1&pattern=success&result_var=haptic_state")
                        }
                    }
                }
            }
            addJsonObject {
                put("type", "container")
                put("orientation", "horizontal")
                put("item_spacing", 8)
                putJsonArray("items") {
                    addJsonObject {
                        put("type", "button")
                        put("weight", 1f)
                        put("text", "Warning")
                        put("background_color", "#B45309")
                        put("text_color", "#FFFFFF")
                        put("corner_radius", 8)
                        putJsonObject("action") {
                            put("log_id", "haptic_warning")
                            put("url", "div-action://terminal.tool.invoke?tool=device.haptics@1&pattern=warning&result_var=haptic_state")
                        }
                    }
                    addJsonObject {
                        put("type", "button")
                        put("weight", 1f)
                        put("text", "Error Buzz")
                        put("background_color", "#DC2626")
                        put("text_color", "#FFFFFF")
                        put("corner_radius", 8)
                        putJsonObject("action") {
                            put("log_id", "haptic_error")
                            put("url", "div-action://terminal.tool.invoke?tool=device.haptics@1&pattern=error&result_var=haptic_state")
                        }
                    }
                    addJsonObject {
                        put("type", "button")
                        put("weight", 1f)
                        put("text", "Tick (tools://)")
                        put("background_color", "#4B5563")
                        put("text_color", "#F9FAFB")
                        put("corner_radius", 8)
                        putJsonObject("action") {
                            put("log_id", "haptic_tick")
                            put("url", "tools://haptics/tick")
                        }
                    }
                }
            }
        }
    }

    /**
     * Hardware Beeper Audio Suite Block (device.beeper@1 / tools://beeper)
     */
    fun buildBeeperToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#1E1B4B")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#818CF8")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "🔔 HARDWARE BEEPER AUDIO (device.beeper@1 • tools://beeper)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#C7D2FE")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Beeper Tone: @{user.beeper_tone} • Status: @{user.beeper_status}")
                put("font_size", 12)
                put("text_color", "#EEF2FF")
                put("font_family", "monospace")
            }
            addJsonObject {
                put("type", "container")
                put("orientation", "horizontal")
                put("item_spacing", 8)
                putJsonArray("items") {
                    addJsonObject {
                        put("type", "button")
                        put("weight", 1f)
                        put("text", "Ring (1200Hz)")
                        put("background_color", "#4338CA")
                        put("text_color", "#FFFFFF")
                        put("corner_radius", 8)
                        putJsonObject("action") {
                            put("log_id", "beeper_ring")
                            put("url", "tools://beeper/ring")
                        }
                    }
                    addJsonObject {
                        put("type", "button")
                        put("weight", 1f)
                        put("text", "Alert (2400Hz)")
                        put("background_color", "#D97706")
                        put("text_color", "#FFFFFF")
                        put("corner_radius", 8)
                        putJsonObject("action") {
                            put("log_id", "beeper_alert")
                            put("url", "tools://beeper/alert")
                        }
                    }
                    addJsonObject {
                        put("type", "button")
                        put("weight", 1f)
                        put("text", "Error (600Hz)")
                        put("background_color", "#DC2626")
                        put("text_color", "#FFFFFF")
                        put("corner_radius", 8)
                        putJsonObject("action") {
                            put("log_id", "beeper_error")
                            put("url", "tools://beeper/error_buzz")
                        }
                    }
                }
            }
        }
    }

    /**
     * Supervisor Biometrics Authorization Block (auth.biometrics@1 / tools://biometrics)
     */
    fun buildBiometricsToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#18181B")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#A1A1AA")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "🛡️ SUPERVISOR BIOMETRIC AUTH (auth.biometrics@1 • tools://biometrics)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#E4E4E7")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Authorized: @{user.supervisor_authenticated} • Role: @{user.supervisor_role}")
                put("font_size", 12)
                put("text_color", "#FAFAFA")
                put("font_family", "monospace")
            }
            add(
                buildNativeToolTriggerBlock(
                    toolId = "biometrics",
                    operation = "authenticate",
                    label = "Request Supervisor Authorization (Biometric / PIN)",
                    subtitle = "tools://biometrics/authenticate • Gates Manager Refunds & Voids",
                    icon = "lock",
                    backgroundColor = "#3F3F46",
                    textColor = "#FFFFFF",
                    options = mapOf("required_role" to "SUPERVISOR"),
                    outputMapping = mapOf(
                        "authenticated" to "user.supervisor_authenticated",
                        "role" to "user.supervisor_role",
                        "auth_token" to "user.supervisor_auth_token"
                    )
                )
            )
        }
    }

    /**
     * Clipboard & Push / Realtime Stream Block
     */
    fun buildClipboardAndPushToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#0F291E")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#10B981")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "📋 CLIPBOARD & REALTIME NOTIFICATIONS (tools://clipboard & tools://push)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#6EE7B7")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Clipboard: @{user.clipboard_content} • Alert: @{user.last_push_alert}")
                put("font_size", 12)
                put("text_color", "#D1FAE5")
                put("font_family", "monospace")
            }
            addJsonObject {
                put("type", "container")
                put("orientation", "horizontal")
                put("item_spacing", 8)
                putJsonArray("items") {
                    addJsonObject {
                        put("type", "container")
                        put("weight", 1f)
                        putJsonArray("items") {
                            add(
                                buildNativeToolTriggerBlock(
                                    toolId = "clipboard",
                                    operation = "copy",
                                    label = "Copy Receipt",
                                    subtitle = "tools://clipboard/copy",
                                    icon = "copy",
                                    backgroundColor = "#047857",
                                    textColor = "#FFFFFF",
                                    options = mapOf("text" to "RECEIPT #8891 - Total $24.50 @ ${spec.displayName}")
                                )
                            )
                        }
                    }
                    addJsonObject {
                        put("type", "container")
                        put("weight", 1f)
                        putJsonArray("items") {
                            add(
                                buildNativeToolTriggerBlock(
                                    toolId = "push",
                                    operation = "send_alert",
                                    label = "Kitchen Push Alert",
                                    subtitle = "tools://push/send_alert",
                                    icon = "bell",
                                    backgroundColor = "#0D9488",
                                    textColor = "#FFFFFF",
                                    options = mapOf("channel" to "kitchen_dispatch", "message" to "Order #1042 Ready for Pickup")
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun buildInventoryJsonListBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("item_spacing", 8)
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "ON-THE-FLY INVENTORY JSON_LIST (user.inventory_json)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#34D399")
            }
            addJsonObject {
                put("type", "json_list")
                put("data_var", "user.inventory_json")
                put("empty_text", "No SKUs in ${spec.tenantId}_inventory")
                putJsonObject("row_card") {
                    put("type", "container")
                    put("orientation", "horizontal")
                    put("background_color", "#0F172A")
                    put("corner_radius", 10)
                    putJsonObject("paddings") {
                        put("left", 12); put("right", 12); put("top", 8); put("bottom", 8)
                    }
                    putJsonObject("border") {
                        put("corner_radius", 10)
                        putJsonObject("stroke") {
                            put("color", "#1E293B")
                            put("width", 1)
                        }
                    }
                    putJsonArray("items") {
                        addJsonObject {
                            put("type", "container")
                            put("orientation", "vertical")
                            put("weight", 1f)
                            putJsonArray("items") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", "@{item.item_name} (@{item.category})")
                                    put("font_size", 13)
                                    put("font_weight", "bold")
                                    put("text_color", "#F8FAFC")
                                }
                                addJsonObject {
                                    put("type", "text")
                                    put("text", "SKU: @{item.sku} • $@{item.price} • Stock: @{item.stock}")
                                    put("font_size", 11)
                                    put("text_color", "#34D399")
                                    put("font_family", "monospace")
                                }
                            }
                        }
                        addJsonObject {
                            put("type", "button")
                            put("weight", 0.4f)
                            put("text", "+1 Order")
                            put("background_color", "#065F46")
                            put("text_color", "#A7F3D0")
                            put("corner_radius", 8)
                            putJsonArray("actions") {
                                add(
                                    WorkspaceProvisioner.buildInsertOrderAction(
                                        spec = spec,
                                        itemNameExpr = "@{item.item_name}",
                                        sku = "@{item.sku}",
                                        priceExpr = "@{item.price}"
                                    )
                                )
                                add(WorkspaceProvisioner.buildQueryOrdersAction())
                                add(WorkspaceProvisioner.buildCountOrdersAction())
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Builds a DivKit `custom` chart component (`"custom_type": "sdui_chart"`) bound to a
     * reactive `EngineStateBridge` JSON array variable (`user.orders_json` or `user.inventory_json`).
     * Rendered via Vico / Compose M3 Canvas on Android and Swift Charts / DGCharts on iOS.
     */
    fun buildSduiChartBlock(
        spec: TenantWorkspaceSpec,
        chartType: String = "bar",
        dataSource: String = "orders"
    ): JsonObject = buildJsonObject {
        val isOrders = dataSource == "orders"
        put("type", "custom")
        put("custom_type", "sdui_chart")
        put("id", "onfly_chart_${chartType}_$dataSource")
        putJsonObject("custom_props") {
            put("chart_type", chartType)
            put(
                "title",
                if (isOrders) {
                    "LIVE REVENUE BY SKU (${spec.terminalCode})"
                } else {
                    "STOCK DISTRIBUTION BY SKU (${spec.tenantId}_inventory)"
                }
            )
            put(
                "subtitle",
                if (isOrders) {
                    "Reactive DivKit sdui_chart → user.orders_json (Vico Android / Swift Charts iOS)"
                } else {
                    "Reactive DivKit sdui_chart → user.inventory_json (KoalaPlot KMP / Vico / DGCharts)"
                }
            )
            put("data_var", if (isOrders) "user.orders_json" else "user.inventory_json")
            put("label_field", "sku")
            put("value_field", if (isOrders) "price" else "stock")
            put("value_prefix", if (isOrders) "$" else "")
            put("value_suffix", if (isOrders) "" else " units")
            put("accent_color", spec.accentHex)
            put("height", 210)
            put("show_grid", true)
            put("show_legend", true)
            put("interactive", true)
        }
    }

    /**
     * Builds an inline-points DivKit `sdui_chart` (Area / Line chart) for hourly terminal telemetry.
     */
    fun buildHourlyTrendChartBlock(spec: TenantWorkspaceSpec, chartType: String = "area"): JsonObject = buildJsonObject {
        put("type", "custom")
        put("custom_type", "sdui_chart")
        put("id", "onfly_hourly_${chartType}_${spec.tenantId}")
        putJsonObject("custom_props") {
            put("chart_type", chartType)
            put("title", "HOURLY POS THROUGHPUT (${spec.terminalCode})")
            put("subtitle", "Custom DivKit ${chartType.uppercase()} Chart • KMP KoalaPlot + Vico M3 + iOS Swift Charts")
            put("value_prefix", "$")
            put("accent_color", spec.accentHex)
            put("height", 200)
            put("show_grid", true)
            put("show_legend", true)
            putJsonArray("points") {
                val palette = listOf("#38BDF8", "#34D399", "#818CF8", "#F59E0B", "#EC4899", "#A78BFA")
                val hours = listOf("09:00", "11:00", "13:00", "15:00", "17:00", "19:00")
                val totals = listOf(145.0, 310.5, 540.0, 390.0, 620.75, 485.25)
                hours.indices.forEach { idx ->
                    addJsonObject {
                        put("label", hours[idx])
                        put("value", totals[idx])
                        put("color", palette[idx % palette.size])
                        put("subtitle", "Window ${hours[idx]}")
                    }
                }
            }
        }
    }

    /**
     * Alternative SDUI Blueprint: Warehouse & Stock Replenishment Terminal
     */
    fun buildInventoryTerminalBlueprint(spec: TenantWorkspaceSpec): String {
        val root = buildJsonObject {
            putJsonObject("card") {
                put("log_id", "inventory_terminal_${spec.tenantId}")
                putJsonArray("states") {
                    addJsonObject {
                        put("state_id", 0)
                        putJsonObject("div") {
                            put("type", "container")
                            put("orientation", "vertical")
                            put("item_spacing", 12)
                            putJsonArray("items") {
                                add(
                                    buildMetricBannerBlock(
                                        title = "${spec.displayName} • Stock Controller",
                                        variableExpr = "@{user.inventory_count} SKUs",
                                        accentHex = "#34D399"
                                    )
                                )
                                add(
                                    buildSduiChartBlock(
                                        spec = spec,
                                        chartType = "donut",
                                        dataSource = "inventory"
                                    )
                                )
                                add(buildInventoryJsonListBlock(spec))
                                addJsonObject {
                                    put("type", "button")
                                    put("id", "refresh_inv_btn")
                                    put("text", "Reload SQLite Inventory (local-db://op/db.query)")
                                    put("icon", "refresh")
                                    put("background_color", "#0284C7")
                                    put("text_color", "#F8FAFC")
                                    put("corner_radius", 10)
                                    putJsonArray("actions") {
                                        add(WorkspaceProvisioner.buildQueryInventoryAction())
                                        add(WorkspaceProvisioner.buildCountInventoryAction())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }
}
