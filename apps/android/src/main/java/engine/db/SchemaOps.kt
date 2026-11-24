package engine.db

import engine.db.models.CanonicalView
import engine.db.models.CollectionSchema
import engine.db.models.FieldType
import engine.tools.codec.Base45
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Interpreter for the declarative parts of a collection schema.
 *
 * Nothing here knows what a product, an order or a receipt is. Validation, aggregate
 * roll-ups, scanned-value lookups, delete guards and canonical transfer packing all read
 * their rules from the workspace schema, so a new workspace is data, not code.
 */
object SchemaOps {

    private const val SEPARATOR = '|'

    data class Upsert(val id: String, val data: JsonObject, val errors: Map<String, String>) {
        val ok: Boolean get() = errors.isEmpty()
    }

    data class Packed(
        val headerId: String,
        val payload: String,
        val checksum: String,
        val chunks: List<String>,
        val chunkBytes: Int
    )

    data class Unpacked(
        val ok: Boolean,
        val payload: String?,
        val checksumOk: Boolean,
        val missing: List<Int>,
        val reason: String?
    )

    data class Appended(val accepted: Boolean, val corrupt: Boolean, val chunks: List<String>, val reason: String?)

    // ---------------------------------------------------------------- validation

    fun validate(
        engine: ApexKitDatabaseEngine,
        collection: String,
        schema: CollectionSchema,
        data: JsonObject,
        existingId: String? = null
    ): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        for ((name, field) in schema.fields) {
            val raw = data[name]
            if (field.required && !field.auto && (raw == null || raw is JsonNull)) {
                errors[name] = "Required"
                continue
            }
            if (raw == null || raw is JsonNull) continue
            val text = raw.jsonPrimitive.contentOrNull ?: ""
            when (field.type) {
                FieldType.NUMBER -> {
                    val number = raw.jsonPrimitive.doubleOrNull
                    if (number == null) {
                        errors[name] = "Must be a number"
                    } else {
                        field.min?.let { if (number < it) errors[name] = "Must be at least ${trim(it)}" }
                        field.max?.let { if (number > it) errors[name] = "Must be at most ${trim(it)}" }
                    }
                }
                FieldType.BOOLEAN -> if (raw.jsonPrimitive.booleanOrNull == null) errors[name] = "Must be true or false"
                FieldType.SELECT -> field.options?.let { allowed ->
                    if (allowed.isNotEmpty() && text !in allowed) errors[name] = "Choose one of ${allowed.joinToString()}"
                }
                else -> {
                    field.min_length?.let { if (text.length < it) errors[name] = "Too short" }
                    field.max_length?.let { if (text.length > it) errors[name] = "Use $it characters or fewer" }
                    field.pattern?.let { pattern ->
                        if (text.isNotEmpty() && !Regex(pattern).matches(text)) errors[name] = "Invalid format"
                    }
                }
            }
        }
        for ((name, field) in schema.fields) {
            if (field.unique != true) continue
            val value = data[name]?.jsonPrimitive?.contentOrNull ?: continue
            if (value.isBlank()) continue
            val clash = engine.queryRecords(collection, listOf(QueryFilter(name, "=", value)), limit = 2)
                .firstOrNull { it.id != existingId }
            if (clash != null) errors[name] = "Already in use"
        }
        return errors
    }

    fun upsert(engine: ApexKitDatabaseEngine, collection: String, data: JsonObject, id: String? = null): Upsert {
        val col = engine.getCollection(collection) ?: error("Collection '$collection' not found")
        val schema = col.schema ?: CollectionSchema()
        val targetId = id?.takeIf { it.isNotBlank() }
            ?: data["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val errors = validate(engine, collection, schema, data, targetId)
        if (errors.isNotEmpty()) return Upsert(targetId ?: "", data, errors)
        val resolved = if (targetId.isNullOrBlank()) engine.insertRecord(collection, data)
        else engine.updateRecord(collection, targetId, data)
        val stored = engine.queryRecords(collection, listOf(QueryFilter("id", "=", resolved)), limit = 1)
            .firstOrNull()?.data ?: data
        return Upsert(resolved, stored, emptyMap())
    }

    // ---------------------------------------------------------------- aggregates

    /**
     * Recomputes every declared roll-up for [id] from its child rows and writes the header.
     * This is the only place totals are produced, so a card can never drift from the rows.
     */
    fun commit(engine: ApexKitDatabaseEngine, collection: String, id: String): Map<String, Any?> {
        val values = recompute(engine, collection, id, enforceCommitRules = true)
        val col = engine.getCollection(collection) ?: error("Collection '$collection' not found")
        val status = col.schema?.aggregates?.mapNotNull { it.commit_status }?.firstOrNull()
        if (status != null) {
            engine.updateRecord(collection, id, buildJsonObject { put("status", status) })
        }
        return values + (status?.let { mapOf("status" to it) } ?: emptyMap())
    }

    /** Writes the declared roll-ups without changing the record's status. */
    fun recompute(engine: ApexKitDatabaseEngine, collection: String, id: String): Map<String, Any?> =
        recompute(engine, collection, id, enforceCommitRules = false)

    private fun recompute(
        engine: ApexKitDatabaseEngine,
        collection: String,
        id: String,
        enforceCommitRules: Boolean
    ): Map<String, Any?> {
        val col = engine.getCollection(collection) ?: error("Collection '$collection' not found")
        val schema = col.schema ?: CollectionSchema()
        if (schema.aggregates.isEmpty()) error("Collection '$collection' declares no aggregates")
        val header = engine.queryRecords(collection, listOf(QueryFilter("id", "=", id)), limit = 1)
            .firstOrNull() ?: error("Record '$id' not found")

        val values = linkedMapOf<String, Any?>()
        for (rule in schema.aggregates) {
            val current = header.data["status"]?.jsonPrimitive?.contentOrNull
            if (enforceCommitRules && rule.editable_status.isNotEmpty() && current != null && current !in rule.editable_status) {
                error("This record is ${current.lowercase()} and can no longer change")
            }
            val children = engine.queryRecords(
                rule.target,
                listOf(QueryFilter(rule.foreign_field, "=", id)),
                limit = 1000
            ).map { it.data }
            if (enforceCommitRules && rule.require_non_empty && children.isEmpty()) {
                error("Nothing to commit yet — add something first")
            }
            rule.outputs.forEach { (field, expr) ->
                val value = evaluate(expr, children)
                values[field] = value
                values["${field}_display"] = money(value)
            }
        }
        val patch = buildJsonObject { values.forEach { (field, value) -> putAny(field, value) } }
        engine.updateRecord(collection, id, patch)
        return values + mapOf("id" to id)
    }


    // ---------------------------------------------------------------- child lists

    data class ChildWrite(
        val parentId: String,
        val childId: String,
        val child: JsonObject,
        val parent: Map<String, Any?>,
        val removedJson: String
    )

    /**
     * Appends a row to a declared child list: merges by the schema's merge key, sums the
     * quantity, enforces the declared stock ceiling, then recomputes the parent roll-ups.
     */
    fun appendChild(
        engine: ApexKitDatabaseEngine,
        collection: String,
        parentId: String,
        data: JsonObject
    ): ChildWrite {
        val col = engine.getCollection(collection) ?: error("Collection '$collection' not found")
        val rule = col.schema?.append ?: error("Collection '$collection' is not a child list")
        engine.getCollection(rule.parent) ?: error("Collection '${rule.parent}' not found")

        val quantity = data[rule.quantity_field]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 1
        if (quantity <= 0) error("Quantity must be at least 1")
        val key = data[rule.merge_key]?.jsonPrimitive?.contentOrNull
            ?: error("Missing '${rule.merge_key}'")

        val siblings = engine.queryRecords(
            collection,
            listOf(QueryFilter(rule.parent_field, "=", parentId)),
            limit = 1000
        )
        val existing = siblings.firstOrNull { it.data[rule.merge_key]?.jsonPrimitive?.contentOrNull == key }
        val requested = (existing?.data?.get(rule.quantity_field)?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0) + quantity

        rule.stock?.let { guard ->
            val stockRow = engine.queryRecords(
                guard.collection,
                listOf(QueryFilter(guard.match_field, "=", key)),
                limit = 1
            ).firstOrNull()
            val available = stockRow?.data?.get(guard.stock_field)?.jsonPrimitive?.doubleOrNull?.toInt()
            if (available != null && requested > available) {
                error("Only $available left — quantity unchanged")
            }
        }

        val childId = if (existing == null) {
            engine.insertRecord(
                collection,
                buildJsonObject {
                    data.forEach { (field, value) -> put(field, value) }
                    put(rule.parent_field, parentId)
                    put(rule.quantity_field, requested)
                }
            )
        } else {
            engine.updateRecord(
                collection,
                existing.id,
                buildJsonObject {
                    put(rule.quantity_field, requested)
                    data[rule.merge_key]?.let { put(rule.merge_key, it) }
                }
            )
        }
        val stored = engine.queryRecords(collection, listOf(QueryFilter("id", "=", childId)), limit = 1)
            .firstOrNull()?.data ?: data
        val parent = recompute(engine, rule.parent, parentId)
        return ChildWrite(parentId, childId, stored, parent, "")
    }

    /** Sets a child's quantity; zero removes it. Returns the parent's fresh roll-up. */
    fun setChildQuantity(
        engine: ApexKitDatabaseEngine,
        collection: String,
        childId: String,
        quantity: Int
    ): ChildWrite {
        val col = engine.getCollection(collection) ?: error("Collection '$collection' not found")
        val rule = col.schema?.append ?: error("Collection '$collection' is not a child list")
        val child = engine.queryRecords(collection, listOf(QueryFilter("id", "=", childId)), limit = 1)
            .firstOrNull() ?: error("Row '$childId' not found")
        val parentId = child.data[rule.parent_field]?.jsonPrimitive?.contentOrNull
            ?: error("Row '$childId' has no parent")
        if (quantity <= 0) {
            engine.deleteRecord(collection, childId)
            return ChildWrite(parentId, childId, child.data, recompute(engine, rule.parent, parentId), child.data.toString())
        }
        val key = child.data[rule.merge_key]?.jsonPrimitive?.contentOrNull
        rule.stock?.let { guard ->
            val available = engine.queryRecords(
                guard.collection,
                listOf(QueryFilter(guard.match_field, "=", key ?: "")),
                limit = 1
            ).firstOrNull()?.data?.get(guard.stock_field)?.jsonPrimitive?.doubleOrNull?.toInt()
            if (available != null && quantity > available) error("Only $available left — quantity unchanged")
        }
        engine.updateRecord(collection, childId, buildJsonObject { put(rule.quantity_field, quantity) })
        val stored = engine.queryRecords(collection, listOf(QueryFilter("id", "=", childId)), limit = 1)
            .firstOrNull()?.data ?: child.data
        return ChildWrite(parentId, childId, stored, recompute(engine, rule.parent, parentId), "")
    }

    /** Removes a child and returns the removed row, which is the whole undo payload. */
    fun removeChild(engine: ApexKitDatabaseEngine, collection: String, childId: String): ChildWrite {
        val col = engine.getCollection(collection) ?: error("Collection '$collection' not found")
        val rule = col.schema?.append ?: error("Collection '$collection' is not a child list")
        val child = engine.queryRecords(collection, listOf(QueryFilter("id", "=", childId)), limit = 1)
            .firstOrNull() ?: error("Row '$childId' not found")
        val parentId = child.data[rule.parent_field]?.jsonPrimitive?.contentOrNull
            ?: error("Row '$childId' has no parent")
        engine.deleteRecord(collection, childId)
        val undo = buildJsonObject {
            child.data.forEach { (field, value) -> put(field, value) }
        }
        return ChildWrite(parentId, childId, child.data, recompute(engine, rule.parent, parentId), undo.toString())
    }

    // ---------------------------------------------------------------- lookup

    fun lookup(
        engine: ApexKitDatabaseEngine,
        collection: String,
        value: String,
        fieldOverride: String? = null
    ): JsonObject? {
        val col = engine.getCollection(collection) ?: return null
        val rule = col.schema?.lookup
        val needle = value.trim()
        if (needle.isEmpty()) return null
        val candidates = buildList {
            fieldOverride?.let { add(it) }
            rule?.field?.let { add(it) }
            rule?.secondary_field?.let { add(it) }
        }.distinct()
        if (candidates.isEmpty()) return null
        for (field in candidates) {
            val exact = engine.queryRecords(collection, listOf(QueryFilter(field, "=", needle)), limit = 1).firstOrNull()
                ?: engine.queryRecords(collection, listOf(QueryFilter(field, "=", needle.uppercase())), limit = 1).firstOrNull()
            if (exact != null) {
                return JsonObject(
                    exact.data + mapOf(
                        "id" to JsonPrimitive(exact.id),
                        "matched_field" to JsonPrimitive(field)
                    )
                )
            }
        }
        return null
    }

    // ---------------------------------------------------------------- delete guard

    fun deleteRefusal(engine: ApexKitDatabaseEngine, collection: String, id: String): String? {
        val col = engine.getCollection(collection) ?: return null
        val record = engine.queryRecords(collection, listOf(QueryFilter("id", "=", id)), limit = 1)
            .firstOrNull()?.data ?: return null
        for (rule in col.schema?.references.orEmpty()) {
            if (!rule.restrict_delete) continue
            val value = record[rule.match]?.jsonPrimitive?.contentOrNull ?: id
            if (value.isBlank()) continue
            val used = engine.countRecords(rule.collection, listOf(QueryFilter(rule.field, "=", value)))
            if (used > 0) {
                return rule.message
                    ?: "Still used by $used row${if (used == 1L) "" else "s"} in ${rule.collection}"
            }
        }
        return null
    }

    // ---------------------------------------------------------------- transfer packing

    fun pack(engine: ApexKitDatabaseEngine, collection: String, id: String, chunkBytes: Int? = null): Packed {
        val col = engine.getCollection(collection) ?: error("Collection '$collection' not found")
        val view = col.schema?.canonical ?: error("Collection '$collection' declares no canonical view")
        val header = engine.queryRecords(collection, listOf(QueryFilter("id", "=", id)), limit = 1)
            .firstOrNull() ?: error("Record '$id' not found")

        val payload = canonicalPayload(engine, view, header.id, header.data)
        val checksum = sha256(payload)
        val encoded = Base45.encode(payload.toByteArray(Charsets.UTF_8))
        val size = (chunkBytes ?: view.chunk_bytes).coerceIn(50, 2000)
        val parts = encoded.chunked(size).ifEmpty { listOf("") }
        val chunks = parts.mapIndexed { index, text -> "${index + 1}$SEPARATOR${parts.size}$SEPARATOR$text" }

        val store = if (view.store.isEmpty()) {
            mapOf(
                "payload" to "payload_json",
                "checksum" to "checksum",
                "chunks" to "chunks_json",
                "chunk_bytes" to "chunk_bytes",
                "chunk_count" to "chunk_count"
            )
        } else view.store
        val patch = linkedMapOf<String, Any?>()
        store.forEach { (key, field) ->
            when (key) {
                "payload" -> patch[field] = payload
                "checksum" -> patch[field] = checksum
                "chunks" -> patch[field] = buildJsonArray { chunks.forEach { add(it) } }.toString()
                "chunk_bytes" -> patch[field] = size
                "chunk_count" -> patch[field] = chunks.size
            }
        }
        if (patch.isNotEmpty()) {
            engine.updateRecord(collection, id, buildJsonObject { patch.forEach { (f, v) -> putAny(f, v) } })
        }
        return Packed(id, payload, checksum, chunks, size)
    }

    fun canonicalPayload(
        engine: ApexKitDatabaseEngine,
        view: CanonicalView,
        headerId: String,
        headerData: JsonObject
    ): String {
        val root = buildJsonObject {
            view.fields.forEach { field ->
                put(field, if (field == "id") JsonPrimitive(headerId) else headerData[field] ?: JsonNull)
            }
            val child = view.child
            if (child != null) {
                val foreign = view.child_foreign_field
                    ?: error("A canonical view with a child must declare child_foreign_field")
                val rows = engine.queryRecords(child, listOf(QueryFilter(foreign, "=", headerId)), limit = 1000)
                    .sortedWith(compareBy({ it.created }, { it.id }))
                put(
                    child,
                    buildJsonArray {
                        rows.forEach { row ->
                            add(buildJsonObject { view.child_fields.forEach { f -> put(f, row.data[f] ?: JsonNull) } })
                        }
                    }
                )
            }
        }
        return root.toString()
    }

    fun append(chunks: List<String>, chunk: String): Appended {
        val parsed = parseChunk(chunk)
        if (parsed == null) return Appended(false, true, chunks, "This code is not a transfer chunk")
        if (runCatching { Base45.decode(parsed.third) }.isFailure) {
            return Appended(false, true, chunks, "This chunk is corrupted — scan it again")
        }
        if (chunks.any { parseChunk(it)?.first == parsed.first }) {
            return Appended(false, false, chunks, "Chunk ${parsed.first} already scanned — ignored")
        }
        return Appended(true, false, chunks + chunk, null)
    }

    fun unpack(chunks: List<String>, expectedChecksum: String?): Unpacked {
        if (chunks.isEmpty()) return Unpacked(false, null, false, emptyList(), "No chunks scanned yet")
        val parsed = chunks.mapNotNull { parseChunk(it) }
        if (parsed.size != chunks.size) {
            return Unpacked(false, null, false, emptyList(), "A chunk is corrupted — scan it again")
        }
        val total = parsed.first().second
        if (parsed.any { it.second != total }) {
            return Unpacked(false, null, false, emptyList(), "Chunks come from different transfers")
        }
        val byIndex = parsed.associateBy { it.first }
        val missing = (1..total).filter { it !in byIndex.keys }
        if (missing.isNotEmpty()) {
            return Unpacked(false, null, false, missing, "Missing chunk ${missing.joinToString()}")
        }
        val joined = (1..total).joinToString("") { byIndex.getValue(it).third }
        val payload = runCatching { String(Base45.decode(joined), Charsets.UTF_8) }.getOrNull()
            ?: return Unpacked(false, null, false, emptyList(), "The transfer could not be decoded")
        val checksumOk = expectedChecksum.isNullOrBlank() ||
            sha256(payload).equals(expectedChecksum, ignoreCase = true)
        if (!checksumOk) {
            return Unpacked(false, null, false, emptyList(), "Checksum mismatch — ask for a fresh code")
        }
        return Unpacked(true, payload, true, emptyList(), null)
    }

    private fun parseChunk(chunk: String): Triple<Int, Int, String>? {
        val first = chunk.indexOf(SEPARATOR)
        val second = chunk.indexOf(SEPARATOR, first + 1)
        if (first <= 0 || second <= first) return null
        val index = chunk.substring(0, first).toIntOrNull() ?: return null
        val total = chunk.substring(first + 1, second).toIntOrNull() ?: return null
        val text = chunk.substring(second + 1)
        if (index < 1 || total < 1 || index > total || text.isEmpty()) return null
        return Triple(index, total, text)
    }

    // ---------------------------------------------------------------- helpers

    fun money(value: Double): String =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else money(value)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Whitelisted aggregate grammar: count(), sum(field), sum(a * b), round2(expr). */
    fun evaluate(expr: String, rows: List<JsonObject>): Double {
        val cleaned = expr.replace(" ", "")
        return when {
            cleaned.equals("count()", ignoreCase = true) -> rows.size.toDouble()
            cleaned.startsWith("round2(", ignoreCase = true) && cleaned.endsWith(")") ->
                round2(evaluate(cleaned.substring(7, cleaned.length - 1), rows))
            cleaned.startsWith("sum(", ignoreCase = true) && cleaned.endsWith(")") -> {
                val inner = cleaned.substring(4, cleaned.length - 1)
                if ('*' in inner) {
                    val (a, b) = inner.split('*', limit = 2)
                    rows.sumOf { row -> number(row, a) * number(row, b) }
                } else {
                    rows.sumOf { row -> number(row, inner) }
                }
            }
            else -> error("Unsupported aggregate expression: $expr")
        }
    }

    private fun round2(value: Double): Double =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()

    private fun number(row: JsonObject, field: String): Double {
        val el = row[field] ?: return 0.0
        return el.jsonPrimitive.doubleOrNull ?: el.jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: 0.0
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putAny(field: String, value: Any?) {
        when (value) {
            is Double -> put(field, value)
            is Int -> put(field, value)
            is Long -> put(field, value)
            is Boolean -> put(field, value)
            null -> put(field, JsonNull)
            else -> put(field, value.toString())
        }
    }
}
