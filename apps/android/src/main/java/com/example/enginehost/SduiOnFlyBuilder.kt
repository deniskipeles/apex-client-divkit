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
     * Hardware Camera Capture & Torch Block (tools://camera).
     */
    fun buildCameraToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#0C2B3A")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#22D3EE")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "📸 HARDWARE CAMERA CAPTURE & TORCH (tools://camera)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#67E8F9")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Capture: @{user.camera_capture_uri} • Torch: @{user.camera_torch_state}")
                put("font_size", 12)
                put("text_color", "#CFFAFE")
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
                                    toolId = "camera",
                                    operation = "capture",
                                    label = "Capture Frame",
                                    subtitle = "tools://camera/capture • 1080p back camera",
                                    icon = "camera",
                                    backgroundColor = "#0891B2",
                                    textColor = "#FFFFFF",
                                    options = mapOf("facing" to "back", "resolution" to "1080p"),
                                    outputMapping = mapOf("capture_uri" to "user.camera_capture_uri")
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
                                    toolId = "camera",
                                    operation = "torch_toggle",
                                    label = "Toggle Torch",
                                    subtitle = "tools://camera/torch_toggle",
                                    icon = "search",
                                    backgroundColor = "#0E7490",
                                    textColor = "#FFFFFF",
                                    options = mapOf("state" to "on"),
                                    outputMapping = mapOf("torch_state" to "user.camera_torch_state")
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Universal Image Manipulation & Background Removal Block (tools://image-manipulation).
     */
    fun buildImageManipulationToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#1E1B2E")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#A78BFA")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "🎛️ IMAGE MANIPULATION & BG REMOVAL (tools://image-manipulation)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#C4B5FD")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Output: @{user.processed_image_uri} • Status: @{user.image_processing_status}")
                put("font_size", 12)
                put("text_color", "#EDE9FE")
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
                                    toolId = "image-manipulation",
                                    operation = "remove_background",
                                    label = "Remove Background",
                                    subtitle = "tools://image-manipulation/remove_background",
                                    icon = "crop",
                                    backgroundColor = "#7C3AED",
                                    textColor = "#FFFFFF",
                                    options = mapOf("op" to "remove_background", "encoding" to "uri"),
                                    outputMapping = mapOf(
                                        "uri" to "user.processed_image_uri",
                                        "status" to "user.image_processing_status"
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
                                    toolId = "image-manipulation",
                                    operation = "smooth",
                                    label = "Edge-Preserving Smooth",
                                    subtitle = "tools://image-manipulation/smooth",
                                    icon = "refresh",
                                    backgroundColor = "#6D28D9",
                                    textColor = "#FFFFFF",
                                    options = mapOf("op" to "smooth", "strength" to "0.6"),
                                    outputMapping = mapOf(
                                        "uri" to "user.processed_image_uri",
                                        "status" to "user.image_processing_status"
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
     * Text & Image Vector Embedding Block (tools://vectorize).
     */
    fun buildVectorizeToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#111827")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#38BDF8")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "🔢 TEXT & IMAGE VECTOR EMBEDDINGS (tools://vectorize)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#7DD3FC")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Source: @{user.vector_source} • Dim: @{user.vector_dim} • Similarity: @{user.vector_similarity}")
                put("font_size", 12)
                put("text_color", "#E0F2FE")
                put("font_family", "monospace")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Vector: @{user.vector_b64}")
                put("font_size", 10)
                put("text_color", "#BAE6FD")
                put("font_family", "monospace")
                put("max_lines", 2)
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
                                    toolId = "vectorize",
                                    operation = "text",
                                    label = "Embed Item Name",
                                    subtitle = "tools://vectorize/text • USE-Lite 512-d",
                                    icon = "search",
                                    backgroundColor = "#0284C7",
                                    textColor = "#FFFFFF",
                                    options = mapOf("normalize" to "true"),
                                    outputMapping = mapOf(
                                        "vector_b64" to "user.vector_b64",
                                        "dim" to "user.vector_dim",
                                        "source_type" to "user.vector_source"
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
                                    toolId = "vectorize",
                                    operation = "image_text",
                                    label = "Embed Image + OCR",
                                    subtitle = "tools://vectorize/image_text • ML Kit + USE-Lite",
                                    icon = "ocr",
                                    backgroundColor = "#0369A1",
                                    textColor = "#FFFFFF",
                                    options = mapOf("normalize" to "true", "include_ocr_text" to "true"),
                                    outputMapping = mapOf(
                                        "vector_b64" to "user.vector_b64",
                                        "dim" to "user.vector_dim",
                                        "source_type" to "user.vector_source",
                                        "ocr_text" to "user.ocr_text"
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
     * Offline Page & Schema Cache Block (tools://offline-cache).
     */
    fun buildOfflineCacheToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#1C1917")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#A8A29E")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "💾 OFFLINE PAGE & SCHEMA CACHE (tools://offline-cache)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#D6D3D1")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Status: @{user.offline_cache_status} • Cached Pages: @{user.offline_cached_pages}")
                put("font_size", 12)
                put("text_color", "#FAFAF9")
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
                                    toolId = "offline-cache",
                                    operation = "cache",
                                    label = "Cache Card",
                                    subtitle = "tools://offline-cache/cache",
                                    icon = "cloud",
                                    backgroundColor = "#57534E",
                                    textColor = "#FFFFFF",
                                    options = mapOf("op" to "cache"),
                                    outputMapping = mapOf(
                                        "status" to "user.offline_cache_status",
                                        "cached_pages" to "user.offline_cached_pages"
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
                                    toolId = "offline-cache",
                                    operation = "get_stats",
                                    label = "Cache Stats",
                                    subtitle = "tools://offline-cache/get_stats",
                                    icon = "inventory",
                                    backgroundColor = "#44403C",
                                    textColor = "#FFFFFF",
                                    options = mapOf("op" to "get_stats"),
                                    outputMapping = mapOf(
                                        "status" to "user.offline_cache_status",
                                        "cached_pages" to "user.offline_cached_pages"
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
     * Realtime WebSocket / SSE Channel Block (tools://realtime-ws).
     */
    fun buildRealtimeWsToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#0B1F2A")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#2DD4BF")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "📡 REALTIME WEBSOCKET / SSE CHANNEL (tools://realtime-ws)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#5EEAD4")
            }
            addJsonObject {
                put("type", "text")
                put("text", "State: @{user.realtime_connection_state} • Channel: @{user.realtime_channel}")
                put("font_size", 12)
                put("text_color", "#CCFBF1")
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
                                    toolId = "realtime-ws",
                                    operation = "connect",
                                    label = "Connect Stream",
                                    subtitle = "tools://realtime-ws/connect • wss:// kitchen_stream",
                                    icon = "sync",
                                    backgroundColor = "#0F766E",
                                    textColor = "#FFFFFF",
                                    options = mapOf("channel" to "kitchen_stream"),
                                    outputMapping = mapOf(
                                        "connection_state" to "user.realtime_connection_state",
                                        "channel" to "user.realtime_channel"
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
                                    toolId = "realtime-ws",
                                    operation = "disconnect",
                                    label = "Disconnect",
                                    subtitle = "tools://realtime-ws/disconnect",
                                    icon = "delete",
                                    backgroundColor = "#115E59",
                                    textColor = "#FFFFFF",
                                    options = mapOf("channel" to "kitchen_stream"),
                                    outputMapping = mapOf(
                                        "connection_state" to "user.realtime_connection_state",
                                        "channel" to "user.realtime_channel"
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
     * Network Transfer (Ktor) & LAN Hub Block (tools://net).
     */
    fun buildNetToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#111C2E")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#60A5FA")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "🌐 NETWORK TRANSFER (Ktor) & LAN HUB (tools://net)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#93C5FD")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Status: @{user.net_status} • HTTP: @{user.net_http_status} • Bytes: @{user.net_bytes} • Hub: @{user.net_hub_port}")
                put("font_size", 12)
                put("text_color", "#DBEAFE")
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
                                    toolId = "net",
                                    operation = "get",
                                    label = "HTTP GET",
                                    subtitle = "tools://net/get • Ktor CIO",
                                    icon = "sync",
                                    backgroundColor = "#1D4ED8",
                                    textColor = "#FFFFFF",
                                    options = mapOf("url" to "https://api.apexpos.local/v1/health", "timeout_ms" to "30000"),
                                    outputMapping = mapOf(
                                        "status" to "user.net_status",
                                        "http_status" to "user.net_http_status",
                                        "bytes" to "user.net_bytes"
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
                                    toolId = "net",
                                    operation = "share",
                                    label = "Start LAN Hub",
                                    subtitle = "tools://net/share • peer-to-peer",
                                    icon = "cloud",
                                    backgroundColor = "#1E40AF",
                                    textColor = "#FFFFFF",
                                    options = mapOf("port" to "8899"),
                                    outputMapping = mapOf(
                                        "status" to "user.net_status",
                                        "port" to "user.net_hub_port"
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
     * Bluetooth Printer / POS Bridge Block (tools://print).
     */
    fun buildPrintToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
        put("type", "container")
        put("orientation", "vertical")
        put("background_color", "#2A1A2E")
        put("corner_radius", 12)
        putJsonObject("paddings") {
            put("left", 12); put("right", 12); put("top", 10); put("bottom", 10)
        }
        putJsonObject("border") {
            put("corner_radius", 12)
            putJsonObject("stroke") {
                put("color", "#E879F9")
                put("width", 1)
            }
        }
        putJsonArray("items") {
            addJsonObject {
                put("type", "text")
                put("text", "🖨️ BLUETOOTH THERMAL PRINTER BRIDGE (tools://print)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#F0ABFC")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Status: @{user.print_status} • Devices: @{user.print_device_count} • Bytes: @{user.print_bytes}")
                put("font_size", 12)
                put("text_color", "#FAE8FF")
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
                                    toolId = "print",
                                    operation = "scan",
                                    label = "Scan Printers",
                                    subtitle = "tools://print/scan • RFCOMM SPP",
                                    icon = "printer",
                                    backgroundColor = "#A21CAF",
                                    textColor = "#FFFFFF",
                                    options = mapOf("op" to "scan"),
                                    outputMapping = mapOf(
                                        "status" to "user.print_status",
                                        "count" to "user.print_device_count"
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
                                    toolId = "print",
                                    operation = "print",
                                    label = "Print ESC/POS Receipt",
                                    subtitle = "tools://print/print • 58mm thermal",
                                    icon = "receipt",
                                    backgroundColor = "#86198F",
                                    textColor = "#FFFFFF",
                                    options = mapOf(
                                        "text" to "APEX POS • ${spec.displayName}\nOrder #1042  TOTAL $24.50",
                                        "align" to "left",
                                        "feed_lines" to "3"
                                    ),
                                    outputMapping = mapOf(
                                        "status" to "user.print_status",
                                        "bytes" to "user.print_bytes"
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

    /**
     * Native Icon Scheme showcase: renders a sample of `icons://native/<name>` icons and
     * dispatches the scheme to resolve a chosen icon into `user.icon_name`.
     */
    fun buildNativeIconSchemeBlock(spec: TenantWorkspaceSpec): JsonObject = buildJsonObject {
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
                put("text", "🎨 NATIVE ICON SCHEME (icons://native/<name> • ${engine.icons.NativeIconCatalog.size} icons)")
                put("font_size", 11)
                put("font_weight", "bold")
                put("text_color", "#A5B4FC")
            }
            addJsonObject {
                put("type", "text")
                put("text", "Resolved: @{user.icon_name} • Material: @{user.icon_material} • Category: @{user.icon_category}")
                put("font_size", 12)
                put("text_color", "#E0E7FF")
                put("font_family", "monospace")
            }
            // Icon gallery rendered through the `image` node type (icons:// references).
            addJsonObject {
                put("type", "container")
                put("orientation", "horizontal")
                put("item_spacing", 10)
                putJsonArray("items") {
                    listOf(
                        "icons://native/cart",
                        "icons://native/bell",
                        "icons://native/receipt",
                        "icons://native/payment",
                        "icons://native/inventory",
                        "icons://native/printer",
                        "icons://native/scan",
                        "icons://native/warehouse"
                    ).forEach { ref ->
                        addJsonObject {
                            put("type", "image")
                            put("image_url", ref)
                            put("size", 26)
                            put("tint", "#C7D2FE")
                        }
                    }
                }
            }
            // Buttons that dispatch the scheme and publish the resolved icon name.
            addJsonObject {
                put("type", "container")
                put("orientation", "horizontal")
                put("item_spacing", 8)
                putJsonArray("items") {
                    listOf(
                        Triple("Cart", "icons://native/cart", "#4338CA"),
                        Triple("Bell", "icons://native/bell", "#7C3AED"),
                        Triple("Receipt", "icons://native/receipt", "#2563EB"),
                        Triple("Discount", "icons://native/discount", "#DB2777")
                    ).forEach { (label, url, color) ->
                        addJsonObject {
                            put("type", "container")
                            put("weight", 1f)
                            putJsonArray("items") {
                                addJsonObject {
                                    put("type", "button")
                                    put("id", "icon_${url.substringAfterLast('/')}")
                                    put("text", label)
                                    put("icon", url)
                                    put("background_color", color)
                                    put("text_color", "#F8FAFC")
                                    put("corner_radius", 8)
                                    putJsonObject("action") {
                                        put("log_id", "icon_pick_${url.substringAfterLast('/')}")
                                        put("url", url)
                                    }
                                }
                            }
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
