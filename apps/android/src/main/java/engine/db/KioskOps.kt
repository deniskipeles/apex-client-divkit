package engine.db

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Kiosk order/line data plane.
 *
 * A kiosk order is a header row in `kiosk_orders` plus one row per line in
 * `kiosk_order_lines`. Cards drive it through `local-db://op/kiosk.*`, so every mutation
 * recomputes the stored totals from the persisted lines instead of trusting an arithmetic
 * expression evaluated on the UI side. That keeps the header and the lines consistent when
 * a tap is repeated, a card is reopened, or the process restarts.
 */
object KioskOps {
    const val ORDERS = "kiosk_orders"
    const val LINES = "kiosk_order_lines"
    const val INVENTORY = "inventory"

    const val DIRECTION_CUSTOMER = "CUSTOMER_REQUEST"
    const val DIRECTION_OWNER = "OWNER_RECEIPT"

    data class Line(
        val id: String,
        val orderId: String,
        val sku: String,
        val itemName: String,
        val unitPrice: Double,
        val quantity: Int
    ) {
        val lineTotal: Double get() = money(unitPrice * quantity)
    }

    data class Cart(
        val orderId: String,
        val direction: String,
        val status: String,
        val terminalId: String,
        val customerName: String,
        val createdAt: String,
        val lines: List<Line>
    ) {
        val subtotal: Double get() = money(lines.sumOf { it.unitPrice * it.quantity })
        val total: Double get() = subtotal
        val itemCount: Int get() = lines.sumOf { it.quantity }
        val label: String get() = orderLabel(orderId)
    }

    /** `#A1B2` — a short, stable handle derived from the record id, never a fake number. */
    fun orderLabel(orderId: String): String {
        val cleaned = orderId.filter { it.isLetterOrDigit() }
        val tail = cleaned.takeLast(4).uppercase()
        return if (tail.isEmpty()) "#—" else "#$tail"
    }

    fun money(value: Double): Double =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()

    fun format(value: Double): String =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()

    fun createOrder(
        engine: ApexKitDatabaseEngine,
        direction: String,
        terminalId: String,
        customerName: String = "",
        status: String = "DRAFT",
        now: Long = System.currentTimeMillis()
    ): Cart {
        val stamp = stamp(now)
        val id = engine.insertRecord(
            ORDERS,
            buildJsonObject {
                put("direction", direction)
                put("status", status)
                put("subtotal", 0)
                put("total", 0)
                put("item_count", 0)
                put("lines_json", "[]")
                put("customer_name", customerName)
                put("terminal_id", terminalId)
                put("created_at", stamp)
                put("updated_at", stamp)
            }
        )
        return load(engine, id) ?: error("Created order '$id' could not be read back")
    }

    fun addLine(
        engine: ApexKitDatabaseEngine,
        orderId: String,
        sku: String,
        itemName: String,
        unitPrice: Double,
        quantity: Int = 1,
        now: Long = System.currentTimeMillis()
    ): Cart {
        require(quantity > 0) { "Quantity must be at least 1" }
        require(unitPrice >= 0) { "Unit price cannot be negative" }
        val cart = load(engine, orderId) ?: error("Order '$orderId' does not exist")
        require(cart.status == "DRAFT") { "Order ${cart.label} is ${cart.status.lowercase()} and cannot be changed" }

        val existing = cart.lines.firstOrNull { it.sku == sku }
        val requested = (existing?.quantity ?: 0) + quantity
        requireStock(engine, sku, cart.lines, requested)

        if (existing == null) {
            engine.insertRecord(
                LINES,
                buildJsonObject {
                    put("order_id", orderId)
                    put("sku", sku)
                    put("item_name", itemName)
                    put("unit_price", money(unitPrice))
                    put("quantity", quantity)
                    put("line_total", money(unitPrice * quantity))
                }
            )
        } else {
            engine.updateRecord(
                LINES,
                existing.id,
                buildJsonObject {
                    put("quantity", requested)
                    put("line_total", money(unitPrice * requested))
                }
            )
        }
        return persistTotals(engine, orderId, now)
    }

    fun setQuantity(
        engine: ApexKitDatabaseEngine,
        orderId: String,
        lineId: String,
        quantity: Int,
        now: Long = System.currentTimeMillis()
    ): Cart {
        val cart = load(engine, orderId) ?: error("Order '$orderId' does not exist")
        require(cart.status == "DRAFT") { "Order ${cart.label} is ${cart.status.lowercase()} and cannot be changed" }
        val line = cart.lines.firstOrNull { it.id == lineId }
            ?: error("Line '$lineId' is not part of order ${cart.label}")
        require(quantity >= 0) { "Quantity cannot be negative" }
        if (quantity == 0) return removeLine(engine, orderId, lineId, now)

        requireStock(engine, line.sku, cart.lines.filter { it.id != lineId }, quantity)
        engine.updateRecord(
            LINES,
            lineId,
            buildJsonObject {
                put("quantity", quantity)
                put("line_total", money(line.unitPrice * quantity))
            }
        )
        return persistTotals(engine, orderId, now)
    }

    fun removeLine(
        engine: ApexKitDatabaseEngine,
        orderId: String,
        lineId: String,
        now: Long = System.currentTimeMillis()
    ): Cart {
        val cart = load(engine, orderId) ?: error("Order '$orderId' does not exist")
        require(cart.status == "DRAFT") { "Order ${cart.label} is ${cart.status.lowercase()} and cannot be changed" }
        require(cart.lines.any { it.id == lineId }) { "Line '$lineId' is not part of order ${cart.label}" }
        engine.deleteRecord(LINES, lineId)
        return persistTotals(engine, orderId, now)
    }

    fun clearOrder(
        engine: ApexKitDatabaseEngine,
        orderId: String,
        now: Long = System.currentTimeMillis()
    ): Cart {
        val cart = load(engine, orderId) ?: error("Order '$orderId' does not exist")
        require(cart.status == "DRAFT") { "Order ${cart.label} is ${cart.status.lowercase()} and cannot be changed" }
        cart.lines.forEach { engine.deleteRecord(LINES, it.id) }
        return persistTotals(engine, orderId, now)
    }

    fun submit(
        engine: ApexKitDatabaseEngine,
        orderId: String,
        now: Long = System.currentTimeMillis()
    ): Cart {
        val cart = load(engine, orderId) ?: error("Order '$orderId' does not exist")
        require(cart.status == "DRAFT") { "Order ${cart.label} was already ${cart.status.lowercase()}" }
        require(cart.lines.isNotEmpty()) { "Order ${cart.label} is empty; add an item before submitting" }
        engine.updateRecord(
            ORDERS,
            orderId,
            buildJsonObject {
                put("status", "SUBMITTED")
                put("submitted_at", stamp(now))
                put("updated_at", stamp(now))
            }
        )
        return persistTotals(engine, orderId, now)
    }

    fun load(engine: ApexKitDatabaseEngine, orderId: String): Cart? {
        val header = engine.queryRecords(
            ORDERS,
            filters = listOf(QueryFilter("id", "=", orderId)),
            limit = 1
        ).firstOrNull() ?: return null
        val lines = engine.queryRecords(
            LINES,
            filters = listOf(QueryFilter("order_id", "=", orderId)),
            orderBy = "id",
            ascending = true,
            limit = 200
        ).map { record ->
            Line(
                id = record.id,
                orderId = orderId,
                sku = record.data.text("sku"),
                itemName = record.data.text("item_name"),
                unitPrice = record.data.number("unit_price"),
                quantity = record.data.number("quantity").toInt()
            )
        }
        return Cart(
            orderId = header.id,
            direction = header.data.text("direction").ifBlank { DIRECTION_CUSTOMER },
            status = header.data.text("status").ifBlank { "DRAFT" },
            terminalId = header.data.text("terminal_id"),
            customerName = header.data.text("customer_name"),
            createdAt = header.data.text("created_at"),
            lines = lines
        )
    }

    /** Most recently updated order for a direction, so reopening a card shows real state. */
    fun latest(engine: ApexKitDatabaseEngine, direction: String? = null): Cart? {
        val filters = if (direction == null) emptyList() else listOf(QueryFilter("direction", "=", direction))
        val header = engine.queryRecords(
            ORDERS,
            filters = filters,
            orderBy = "updated_at",
            ascending = false,
            limit = 1
        ).firstOrNull() ?: return null
        return load(engine, header.id)
    }

    /**
     * Orders for a direction/status, newest first. The owner receipt card lists submitted
     * customer orders through this so a handed-over order can be reviewed on the owner side.
     */
    fun recent(
        engine: ApexKitDatabaseEngine,
        direction: String? = null,
        status: String? = null,
        limit: Int = 10
    ): List<Cart> {
        val filters = buildList {
            direction?.let { add(QueryFilter("direction", "=", it)) }
            status?.let { add(QueryFilter("status", "=", it)) }
        }
        return engine.queryRecords(
            ORDERS,
            filters = filters,
            orderBy = "updated_at",
            ascending = false,
            limit = limit.coerceIn(1, 50)
        ).mapNotNull { header -> load(engine, header.id) }
    }

    /** Compact rows for a card `json_list`, one object per order. */
    fun listJson(carts: List<Cart>): String = buildJsonArray {
        carts.forEach { cart ->
            add(
                buildJsonObject {
                    put("id", cart.orderId)
                    put("order_label", cart.label)
                    put("status", cart.status)
                    put("direction", cart.direction)
                    put("item_count", cart.itemCount)
                    put("total_display", format(cart.total))
                    put("created_at", cart.createdAt)
                }
            )
        }
    }.toString()

    fun latestOrCreate(
        engine: ApexKitDatabaseEngine,
        direction: String,
        terminalId: String,
        now: Long = System.currentTimeMillis()
    ): Cart {
        val open = latest(engine, direction)?.takeIf { it.status == "DRAFT" }
        return open ?: createOrder(engine, direction, terminalId, now = now)
    }

    /**
     * Canonical transfer document for the live order. Keys are emitted in a fixed order so
     * the checksum of an assembled copy can be compared with the source byte for byte.
     */
    fun exportPayload(cart: Cart): String = buildJsonObject {
        put("schema", "apex.kiosk.transfer/1")
        put("order", cart.label)
        put("order_id", cart.orderId)
        put("direction", cart.direction)
        put("status", cart.status)
        put("terminal", cart.terminalId)
        put("customer", cart.customerName)
        put("created_at", cart.createdAt)
        put("item_count", cart.itemCount)
        put("total", format(cart.total))
        putJsonArray("items") {
            cart.lines.forEach { line ->
                addJsonObject {
                    put("sku", line.sku)
                    put("name", line.itemName)
                    put("unit_price", format(line.unitPrice))
                    put("quantity", line.quantity)
                    put("line_total", format(line.lineTotal))
                }
            }
        }
    }.toString()

    fun checksum(payload: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)

    /** Flat row handed to `OpResult.Row` so cards can bind every field directly. */
    fun row(cart: Cart): Map<String, Any?> = mapOf(
        "order_id" to cart.orderId,
        "order_label" to cart.label,
        "status" to cart.status,
        "direction" to cart.direction,
        "terminal_id" to cart.terminalId,
        "customer_name" to cart.customerName,
        "created_at" to cart.createdAt,
        "subtotal" to cart.subtotal,
        "total" to cart.total,
        "item_count" to cart.itemCount.toLong(),
        "line_count" to cart.lines.size.toLong(),
        "lines_json" to linesJson(cart),
        "subtotal_display" to format(cart.subtotal),
        "total_display" to format(cart.total),
        "payload_json" to exportPayload(cart),
        "checksum" to checksum(exportPayload(cart))
    )

    fun linesJson(cart: Cart): String = buildJsonArray {
        cart.lines.forEach { line ->
            add(
                buildJsonObject {
                    put("id", line.id)
                    put("sku", line.sku)
                    put("item_name", line.itemName)
                    put("unit_price", format(line.unitPrice))
                    put("quantity", line.quantity)
                    put("line_total", format(line.lineTotal))
                }
            )
        }
    }.toString()

    private fun persistTotals(
        engine: ApexKitDatabaseEngine,
        orderId: String,
        now: Long
    ): Cart {
        val cart = load(engine, orderId) ?: error("Order '$orderId' does not exist")
        engine.updateRecord(
            ORDERS,
            orderId,
            buildJsonObject {
                put("subtotal", cart.subtotal)
                put("total", cart.total)
                put("item_count", cart.itemCount)
                put("lines_json", linesJson(cart))
                put("payload_json", exportPayload(cart))
                put("checksum", checksum(exportPayload(cart)))
                put("updated_at", stamp(now))
            }
        )
        return cart
    }

    private fun requireStock(
        engine: ApexKitDatabaseEngine,
        sku: String,
        otherLines: List<Line>,
        requestedQuantity: Int
    ) {
        val stock = engine.queryRecords(
            INVENTORY,
            filters = listOf(QueryFilter("sku", "=", sku)),
            limit = 1
        ).firstOrNull()?.data?.number("stock") ?: return
        val alreadyOnOrder = otherLines.filter { it.sku == sku }.sumOf { it.quantity }
        require(alreadyOnOrder + requestedQuantity <= stock) {
            "Only ${stock.toInt()} in stock for $sku"
        }
    }

    private fun stamp(now: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(now))

    private fun JsonObject.text(field: String): String =
        this[field]?.let { el ->
            (el as? JsonPrimitive)?.contentOrNull ?: ""
        } ?: ""

    private fun JsonObject.number(field: String): Double {
        val el = this[field] as? JsonPrimitive ?: return 0.0
        return el.doubleOrNull ?: el.contentOrNull?.toDoubleOrNull() ?: 0.0
    }
}
