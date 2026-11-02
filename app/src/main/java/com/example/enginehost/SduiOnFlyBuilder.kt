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
