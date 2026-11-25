package com.example.enginehost

import com.example.enginehost.demo.DemoCardRepository
import kotlinx.serialization.json.*

/**
 * Builds and edits DivKit cards for the host.
 *
 * Tool showcase cards are not written in Kotlin: each lives in `resources/demo/apex/tools/<tool>.json`
 * and is loaded through [DemoCardRepository]. The builders here only cover the small pieces the
 * host composes at runtime (on-the-fly buttons, hardware triggers, the tool action button) and
 * the assembled showcase card.
 */
object SduiOnFlyBuilder {

    private val prettyJson = Json { prettyPrint = true }

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

    /**
     * The entry hub card: POS essentials plus a catalog that links to each tool card.
     *
     * Tool markup is no longer inlined here — every entry pushes its own card through
     * `nav://push`, so the landing card stays small and each tool gets its own screen.
     */
    fun buildShowcaseCard(spec: TenantWorkspaceSpec): String {
        val card = DemoCardRepository.hubCard(spec) ?: return missingCardDocument(spec)
        return DemoCardRepository.encode(card)
    }

    /**
     * What a workspace renders when no DivKit document could be loaded.
     *
     * The previous fallback was `{"card":{"log_id":"empty"}}`: a card with no states, so the
     * screen was indistinguishable from a broken app. This one says what is wrong.
     */
    private fun missingCardDocument(spec: TenantWorkspaceSpec): String =
        DemoCardRepository.encode(
            buildJsonObject {
                putJsonObject("card") {
                    put("log_id", "missing_card_${spec.tenantId}")
                    putJsonArray("states") {
                        addJsonObject {
                            put("state_id", 0)
                            putJsonObject("div") {
                                put("type", "container")
                                put("orientation", "vertical")
                                put("item_spacing", 12)
                                putJsonObject("paddings") {
                                    put("left", 20); put("right", 20); put("top", 24); put("bottom", 24)
                                }
                                putJsonArray("items") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Workspace card unavailable")
                                        put("font_size", 20)
                                        put("font_weight", "bold")
                                        put("text_color", "#111827")
                                    }
                                    addJsonObject {
                                        put("type", "text")
                                        put(
                                            "text",
                                            "No DivKit document was found for '${spec.tenantId}'. " +
                                                "The workspace bundle is missing or unreadable in this build."
                                        )
                                        put("font_size", 14)
                                        put("text_color", "#6B7280")
                                    }
                                    addJsonObject {
                                        put("type", "text")
                                        put(
                                            "text",
                                            "Open the workspace again from the hub, or rebuild with a valid " +
                                                "workspace bundle."
                                        )
                                        put("font_size", 13)
                                        put("text_color", "#6B7280")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )

    /** Every registered card for [spec], entry hub first — see [DemoCardRepository.cardStack]. */
    fun buildCardStack(spec: TenantWorkspaceSpec): List<Pair<String, JsonObject>> =
        DemoCardRepository.cardStack(spec)

    /** A single tool card by file name (e.g. `scanner`), or an empty container when absent. */
    fun buildToolCard(spec: TenantWorkspaceSpec, name: String): JsonObject =
        DemoCardRepository.toolCard(spec, name)
            ?: buildJsonObject { put("type", "container"); putJsonArray("items") {} }

    fun buildScannerToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "scanner")
    fun buildCodeGenerateToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "code_generate")
    fun buildMobileNetV4ClassifyToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "mobilenet")
    fun buildU2NetToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "u2net")
    fun buildImageManipulationToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "image_manipulation")
    fun buildCameraToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "camera")
    fun buildEmbeddingToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "embedding")
    fun buildLibSignalToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "libsignal")
    fun buildBiometricsToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "biometrics")
    fun buildHapticsToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "haptics")
    fun buildBeeperToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "beeper")
    fun buildClipboardAndPushToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "clipboard_push")
    fun buildOfflineCacheToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "offline_cache")
    fun buildRealtimeWsToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "realtime_ws")
    fun buildNetToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "net")
    fun buildPrintToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "print")
    fun buildScreenToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "screen")
    fun buildFilesToolBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "files")
    fun buildNativeIconSchemeBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "native_icons")
    fun buildCatalogJsonListBlock(spec: TenantWorkspaceSpec): JsonObject = buildToolCard(spec, "catalog_list")

    fun buildSduiChartBlock(
        spec: TenantWorkspaceSpec,
        chartType: String = "bar",
        dataSource: String = "orders"
    ): JsonObject {
        val card = buildToolCard(spec, "sdui_chart")
        return buildJsonObject {
            card.forEach { (k, v) -> put(k, v) }
            putJsonObject("custom_props") {
                card["custom_props"]?.jsonObject?.forEach { (k, v) -> put(k, v) }
                put("chart_type", chartType)
                put("data_source", dataSource)
            }
        }
    }

    fun buildHourlyTrendChartBlock(spec: TenantWorkspaceSpec, chartType: String = "area"): JsonObject {
        val card = buildToolCard(spec, "hourly_chart")
        return buildJsonObject {
            card.forEach { (k, v) -> put(k, v) }
            putJsonObject("custom_props") {
                card["custom_props"]?.jsonObject?.forEach { (k, v) -> put(k, v) }
                put("chart_type", chartType)
            }
        }
    }

    /** The catalog terminal blueprint: banner, donut chart, catalog list, reload button. */
    fun buildCatalogTerminalBlueprint(spec: TenantWorkspaceSpec): String {
        val root = buildJsonObject {
            putJsonObject("card") {
                put("log_id", "catalog_terminal_${spec.tenantId}")
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
                                        variableExpr = "@{user.catalog_count} SKUs",
                                        accentHex = "#34D399"
                                    )
                                )
                                add(buildSduiChartBlock(spec, chartType = "donut", dataSource = "catalog"))
                                add(buildCatalogJsonListBlock(spec))
                                addJsonObject {
                                    put("type", "button")
                                    put("id", "refresh_inv_btn")
                                    put("text", "Reload SQLite Catalog (local-db://op/db.query)")
                                    put("icon", "refresh")
                                    put("background_color", "#0284C7")
                                    put("text_color", "#F8FAFC")
                                    put("corner_radius", 10)
                                    putJsonArray("actions") {
                                        add(WorkspaceProvisioner.buildCatalogQueryAction())
                                        add(WorkspaceProvisioner.buildCatalogCountAction())
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
