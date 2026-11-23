package engine.db

import engine.sqlite.SqlDriver
import engine.db.auth.Claims
import engine.db.auth.PolicyEngine
import engine.db.models.*
import engine.db.models.Collection
import kotlinx.serialization.json.*

class ApexKitDatabaseEngine(val driver: SqlDriver) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    init {
        WorkspaceDatabase.Schema.create(driver)
    }

    // =========================================================================
    // 1. SCHEMA IMPORT & COLLECTION REGISTRATION
    // =========================================================================

    fun importTenantSchema(schemaPackage: TenantSchemaPackage): Int {
        var importedCount = 0
        for (col in schemaPackage.collections) {
            val schemaStr = col.schema?.let { json.encodeToString(CollectionSchema.serializer(), it) }
            driver.executeUpdate(
                null,
                "INSERT OR REPLACE INTO collections (id, name, schema, index_key) VALUES (?, ?, ?, ?)",
                4
            ) {
                bindLong(0, col.id)
                bindString(1, col.name)
                if (schemaStr != null) bindString(2, schemaStr) else bindNull(2)
                if (col.index != null) bindString(3, col.index) else bindNull(3)
            }
            importedCount++
        }
        ensureIndexes(listCollections())
        return importedCount
    }

    fun importTenantSchemaJson(rawJson: String): Int {
        val pkg = json.decodeFromString(TenantSchemaPackage.serializer(), rawJson)
        return importTenantSchema(pkg)
    }

    fun getCollection(nameOrId: String): Collection? {
        val isId = nameOrId.toLongOrNull() != null
        val sql = if (isId) "SELECT id, name, schema, index_key FROM collections WHERE id = ? LIMIT 1"
                  else "SELECT id, name, schema, index_key FROM collections WHERE name = ? LIMIT 1"

        var result: Collection? = null
        driver.executeQuery(null, sql, 1) {
            if (isId) bindLong(0, nameOrId.toLong()) else bindString(0, nameOrId)
        }.use { c ->
            if (c.next().value) {
                val schemaStr = c.getString(2)
                val schema = schemaStr?.let { json.decodeFromString(CollectionSchema.serializer(), it) }
                result = Collection(
                    id = c.getLong(0) ?: 0L,
                    name = c.getString(1) ?: "",
                    schema = schema,
                    index = c.getString(3)
                )
            }
        }
        return result
    }

    fun listCollections(): List<Collection> {
        val list = mutableListOf<Collection>()
        driver.executeQuery(null, "SELECT id, name, schema, index_key FROM collections ORDER BY id ASC", 0).use { c ->
            while (c.next().value) {
                val schemaStr = c.getString(2)
                val schema = schemaStr?.let { json.decodeFromString(CollectionSchema.serializer(), it) }
                list.add(
                    Collection(
                        id = c.getLong(0) ?: 0L,
                        name = c.getString(1) ?: "",
                        schema = schema,
                        index = c.getString(3)
                    )
                )
            }
        }
        return list
    }

    // =========================================================================
    // 1b. SCHEMA-GENERATED EXPRESSION INDEXES
    // =========================================================================

    private data class DesiredIndex(
        val name: String,
        val collectionId: Long,
        val kind: String,
        val fields: List<String>
    )

    private data class AppliedIndex(
        val name: String,
        val collectionId: Long,
        val kind: String,
        val fields: List<String>
    )

    /**
     * Reconciles the indexes required by [collections] with those recorded in
     * `_applied_indexes`: creates missing, drops removed, recreates changed. Runs as one
     * transaction so a failed reconcile leaves the previous index set intact.
     */
    fun ensureIndexes(collections: List<Collection>) {
        driver.executeUpdate(
            null,
            """
            CREATE TABLE IF NOT EXISTS _applied_indexes (
                index_name    TEXT PRIMARY KEY,
                collection_id INTEGER NOT NULL,
                kind          TEXT NOT NULL,
                fields        TEXT NOT NULL
            )
            """.trimIndent(),
            0
        )

        if (!driver.supportsJson1) return

        val desired = computeDesiredIndexes(collections)
        val applied = loadAppliedIndexes()

        driver.beginTransaction()
        try {
            val desiredByName = desired.associateBy { it.name }

            for (existing in applied) {
                if (desiredByName[existing.name] == null) {
                    driver.executeUpdate(null, "DROP INDEX IF EXISTS ${quoteIdent(existing.name)}", 0)
                    driver.executeUpdate(null, "DELETE FROM _applied_indexes WHERE index_name = ?", 1) {
                        bindString(0, existing.name)
                    }
                }
            }

            for (index in desired) {
                val existing = applied.firstOrNull { it.name == index.name }
                if (existing != null && sameSpec(existing, index)) continue

                if (existing != null) {
                    driver.executeUpdate(null, "DROP INDEX IF EXISTS ${quoteIdent(existing.name)}", 0)
                    driver.executeUpdate(null, "DELETE FROM _applied_indexes WHERE index_name = ?", 1) {
                        bindString(0, existing.name)
                    }
                }

                if (index.kind == "unique" || index.kind == "composite") {
                    val duplicate = findDuplicate(index)
                    if (duplicate != null) {
                        error(
                            "Cannot create unique index '${index.name}' on (${index.fields.joinToString(", ")}): " +
                                "duplicate value '$duplicate' already exists."
                        )
                    }
                }

                driver.executeUpdate(null, createIndexSql(index), 0)
                driver.executeUpdate(
                    null,
                    "INSERT OR REPLACE INTO _applied_indexes (index_name, collection_id, kind, fields) VALUES (?, ?, ?, ?)",
                    4
                ) {
                    bindString(0, index.name)
                    bindLong(1, index.collectionId)
                    bindString(2, index.kind)
                    bindString(3, json.encodeToString(JsonArray.serializer(), JsonArray(index.fields.map { JsonPrimitive(it) })))
                }
            }

            driver.setTransactionSuccessful()
        } finally {
            driver.endTransaction()
        }
    }

    private fun computeDesiredIndexes(collections: List<Collection>): List<DesiredIndex> {
        val desired = mutableListOf<DesiredIndex>()
        for (col in collections) {
            val schema = col.schema ?: continue
            for ((fieldName, fieldDef) in schema.fields) {
                when {
                    fieldDef.unique == true ->
                        desired += DesiredIndex("idx_u_${col.id}_${sanitize(fieldName)}", col.id, "unique", listOf(fieldName))
                    fieldDef.sql_indexed ->
                        desired += DesiredIndex("idx_n_${col.id}_${sanitize(fieldName)}", col.id, "non_unique", listOf(fieldName))
                }
            }
            schema.composite_unique.forEachIndexed { i, comp ->
                if (comp.isNotEmpty()) {
                    desired += DesiredIndex("idx_c_${col.id}_$i", col.id, "composite", comp)
                }
            }
        }
        return desired
    }

    private fun loadAppliedIndexes(): List<AppliedIndex> {
        val list = mutableListOf<AppliedIndex>()
        driver.executeQuery(null, "SELECT index_name, collection_id, kind, fields FROM _applied_indexes", 0).use { c ->
            while (c.next().value) {
                val fields = try {
                    json.decodeFromString(JsonArray.serializer(), c.getString(3) ?: "[]").map { it.jsonPrimitive.content }
                } catch (_: Exception) {
                    emptyList()
                }
                list.add(
                    AppliedIndex(
                        name = c.getString(0) ?: "",
                        collectionId = c.getLong(1) ?: 0L,
                        kind = c.getString(2) ?: "",
                        fields = fields
                    )
                )
            }
        }
        return list
    }

    private fun sameSpec(a: AppliedIndex, b: DesiredIndex): Boolean =
        a.collectionId == b.collectionId && a.kind == b.kind && a.fields == b.fields

    private fun createIndexSql(index: DesiredIndex): String {
        val unique = if (index.kind == "unique" || index.kind == "composite") "UNIQUE " else ""
        val expressions = index.fields.joinToString(", ") { field ->
            "json_extract(data, '$.${field.replace("'", "''")}')"
        }
        return "CREATE ${unique}INDEX ${quoteIdent(index.name)} ON records (collection_id, $expressions)"
    }

    /** Returns a duplicated value for the index's field set, or null when all values are distinct. */
    private fun findDuplicate(index: DesiredIndex): String? {
        val expressions = index.fields.joinToString(", ") { field ->
            "json_extract(data, '$.${field.replace("'", "''")}')"
        }
        val sql = """
            SELECT $expressions AS dup
            FROM records
            WHERE collection_id = ${index.collectionId}
            GROUP BY $expressions
            HAVING COUNT(*) > 1
            LIMIT 1
        """.trimIndent()
        var duplicate: String? = null
        driver.executeQuery(null, sql, 0).use { c ->
            if (c.next().value) duplicate = c.getString(0) ?: ""
        }
        return duplicate
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    // =========================================================================
    // 2. RECORD CREATION WITH SCHEMA ENFORCEMENT
    // =========================================================================

    fun insertRecord(
        collectionName: String,
        data: JsonObject,
        user: Claims? = null
    ): String {
        val col = getCollection(collectionName) ?: error("Collection '$collectionName' not found in schema.")
        val schema = col.schema ?: CollectionSchema()

        // 1. Verify RLS create policy
        val policySql = PolicyEngine.compileToSql(schema.policies.create, user)
        if (policySql == "1=0") error("Access Denied: Record creation rejected by policy on collection '$collectionName'.")

        // 2. Validate required fields
        for ((fieldName, fieldDef) in schema.fields) {
            if (fieldDef.required && !fieldDef.auto && !data.containsKey(fieldName)) {
                error("Validation Error: Field '$fieldName' is required in collection '$collectionName'.")
            }
        }

        val now = System.currentTimeMillis().toString()
        val dataStr = json.encodeToString(JsonObject.serializer(), data)

        val recordId: String
        driver.beginTransaction()
        try {
            // 3. Resolve the record identity (supplied id preserved as text, else generated).
            recordId = resolveRecordId(data["id"])

            // 4. Insert the record. Uniqueness is enforced by the schema-generated
            //    expression indexes; SQLite raises a constraint error on collision.
            try {
                driver.executeUpdate(
                    null,
                    "INSERT INTO records (id, collection_id, data, created, updated) VALUES (?, ?, ?, ?, ?)",
                    5
                ) {
                    bindString(0, recordId)
                    bindLong(1, col.id)
                    bindString(2, dataStr)
                    bindString(3, now)
                    bindString(4, now)
                }
            } catch (e: Exception) {
                throw translateConstraintError(e, schema, data, collectionName)
            }

            // 5. Materialise relation rows only where behaviour requires them.
            insertRelationRows(schema, recordId, data)

            driver.setTransactionSuccessful()
        } finally {
            driver.endTransaction()
        }

        return recordId
    }

    /**
     * Applies a shallow JSON merge to an existing record. Unspecified fields are preserved;
     * the record id is immutable. Returns the record id so callers can chain another action.
     */
    fun updateRecord(
        collectionName: String,
        recordId: String,
        patch: JsonObject,
        user: Claims? = null
    ): String {
        val col = getCollection(collectionName) ?: error("Collection '$collectionName' not found in schema.")
        val schema = col.schema ?: CollectionSchema()

        val policySql = PolicyEngine.compileToSql(schema.policies.update, user)
        if (policySql == "1=0") error("Access Denied: Record update rejected by policy on collection '$collectionName'.")

        val existing = loadRecordForCollection(col.id, recordId)
            ?: error("Record '$recordId' not found in collection '$collectionName'.")

        val mergedFields = LinkedHashMap<String, JsonElement>()
        existing.data.forEach { (k, v) -> mergedFields[k] = v }
        patch.forEach { (k, v) -> if (k != "id") mergedFields[k] = v }
        val merged = JsonObject(mergedFields)

        for ((fieldName, fieldDef) in schema.fields) {
            if (fieldDef.required && !fieldDef.auto && !merged.containsKey(fieldName)) {
                error("Validation Error: Field '$fieldName' is required in collection '$collectionName'.")
            }
        }

        val dataStr = json.encodeToString(JsonObject.serializer(), merged)
        val now = System.currentTimeMillis().toString()

        driver.beginTransaction()
        try {
            val updated = driver.executeUpdate(
                null,
                "UPDATE records SET data = ?, updated = ? WHERE id = ? AND collection_id = ?",
                4
            ) {
                bindString(0, dataStr)
                bindString(1, now)
                bindString(2, recordId)
                bindLong(3, col.id)
            }
            if (updated == 0L) error("Record '$recordId' not found in collection '$collectionName'.")

            driver.executeUpdate(null, "DELETE FROM _relations WHERE origin_rec_id = ?", 1) {
                bindString(0, recordId)
            }
            insertRelationRows(schema, recordId, merged)
            driver.setTransactionSuccessful()
        } finally {
            driver.endTransaction()
        }

        return recordId
    }

    private fun loadRecordForCollection(collectionId: Long, recordId: String): LoadedRecord? {
        var result: LoadedRecord? = null
        driver.executeQuery(
            null,
            "SELECT id, collection_id, data FROM records WHERE id = ? AND collection_id = ? LIMIT 1",
            2
        ) {
            bindString(0, recordId)
            bindLong(1, collectionId)
        }.use { c ->
            if (c.next().value) {
                val dataObj = try {
                    json.decodeFromString(JsonObject.serializer(), c.getString(2) ?: "{}")
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }
                result = LoadedRecord(
                    id = c.getString(0) ?: recordId,
                    collectionId = c.getLong(1) ?: collectionId,
                    data = dataObj
                )
            }
        }
        return result
    }

    /**
     * Resolves a record id: a supplied non-empty value is preserved as text, otherwise a
     * value is drawn from the `_record_id_sequence` AUTOINCREMENT sequence and stored as text.
     */
    private fun resolveRecordId(input: JsonElement?): String {
        if (input != null && input !is JsonNull) {
            val text = input.jsonPrimitive.contentOrNull?.trim()
            if (text.isNullOrEmpty()) error("Validation Error: Record 'id' must not be empty.")
            return text
        }
        driver.executeUpdate(null, "INSERT INTO _record_id_sequence (id) VALUES (NULL)", 0)
        return driver.lastInsertedRowId().toString()
    }

    private fun insertRelationRows(schema: CollectionSchema, recordId: String, data: JsonObject) {
        for ((relName, relDef) in schema.relations) {
            val targetRecId = recordIdText(data[relName]) ?: continue

            val requiresRow = relDef.cascade_on_target_delete ||
                relDef.relation_type == RelationType.MANY ||
                relDef.has_properties ||
                relDef.sql_indexed
            if (!requiresRow) continue

            val properties = if (relDef.has_properties) {
                data["${relName}_properties"]?.takeIf { it is JsonObject }?.toString()
            } else {
                null
            }

            driver.executeUpdate(
                null,
                """
                INSERT OR REPLACE INTO _relations (origin_rec_id, target_rec_id, properties)
                VALUES (?, ?, ?)
                """.trimIndent(),
                3
            ) {
                bindString(0, recordId)
                bindString(1, targetRecId)
                if (properties != null) bindString(2, properties) else bindNull(2)
            }
        }
    }

    private fun translateConstraintError(
        e: Exception,
        schema: CollectionSchema,
        data: JsonObject,
        collectionName: String
    ): Exception {
        val message = e.message ?: ""
        if (!message.contains("UNIQUE", ignoreCase = true) && !message.contains("constraint", ignoreCase = true)) {
            return e
        }
        val uniqueFields = schema.fields.filterValues { it.unique == true }.keys.filter { data.containsKey(it) }
        val detail = when {
            uniqueFields.isNotEmpty() -> "Field(s) ${uniqueFields.joinToString(", ") { "'$it'" }} already exist"
            schema.composite_unique.isNotEmpty() -> "Composite unique constraint violated"
            else -> "Unique constraint violated"
        }
        return IllegalStateException("Unique constraint violation on collection '$collectionName': $detail.")
    }

    // =========================================================================
    // 3. GENERIC QUERY ENGINE
    // =========================================================================

    fun queryRecords(
        collectionName: String,
        filters: List<QueryFilter> = emptyList(),
        orderBy: String? = null,
        ascending: Boolean = false,
        limit: Int = 100,
        offset: Int = 0,
        user: Claims? = null
    ): List<Record> {
        val col = getCollection(collectionName) ?: return emptyList()
        val schema = col.schema ?: CollectionSchema()

        // 1. RLS Policy predicate
        val rlsSql = PolicyEngine.compileToSql(schema.policies.read, user)
        if (rlsSql == "1=0") return emptyList()

        val whereClauses = mutableListOf("records.collection_id = ${col.id}", rlsSql)
        val args = mutableListOf<String>()

        for (f in filters) {
            val fieldPath = f.field.replace("'", "''")
            when (f.op.uppercase()) {
                "=", "!=", ">", ">=", "<", "<=" -> {
                    whereClauses.add("json_extract(records.data, '$.$fieldPath') ${f.op} ?")
                    args.add(f.value)
                }
                "LIKE" -> {
                    whereClauses.add("json_extract(records.data, '$.$fieldPath') LIKE ?")
                    args.add(f.value)
                }
                "IN" -> {
                    val values = f.value.split(",").map { it.trim() }
                    val placeholders = values.joinToString(",") { "?" }
                    whereClauses.add("json_extract(records.data, '$.$fieldPath') IN ($placeholders)")
                    args.addAll(values)
                }
            }
        }

        val orderClause = if (!orderBy.isNullOrBlank()) {
            val orderField = orderBy.replace("'", "''")
            val dir = if (ascending) "ASC" else "DESC"
            if (orderField == "id") "ORDER BY records.id $dir"
            else "ORDER BY json_extract(records.data, '$.$orderField') $dir"
        } else {
            "ORDER BY records.id DESC"
        }

        val sql = """
            SELECT records.id, records.collection_id, records.data, records.created, records.updated
            FROM records
            WHERE ${whereClauses.joinToString(" AND ")}
            $orderClause
            LIMIT $limit OFFSET $offset
        """.trimIndent()

        if (!driver.supportsJson1 && sql.contains("json_extract", ignoreCase = true)) {
            return queryRecordsInMemory(col, filters, orderBy, ascending, limit, offset)
        }

        return try {
            val records = mutableListOf<Record>()
            driver.executeQuery(null, sql, args.size) {
                args.forEachIndexed { i, v -> bindString(i, v) }
            }.use { c ->
                while (c.next().value) {
                    val rawData = c.getString(2) ?: "{}"
                    val dataObj = try {
                        json.decodeFromString(JsonObject.serializer(), rawData)
                    } catch (_: Exception) {
                        JsonObject(emptyMap())
                    }
                    records.add(
                        Record(
                            id = c.getString(0) ?: "",
                            collectionId = c.getLong(1) ?: col.id,
                            data = dataObj,
                            created = c.getString(3) ?: "",
                            updated = c.getString(4) ?: ""
                        )
                    )
                }
            }
            records
        } catch (e: Exception) {
            if (!isMissingJson1(e)) throw e
            queryRecordsInMemory(col, filters, orderBy, ascending, limit, offset)
        }
    }

    private fun queryRecordsInMemory(
        col: Collection,
        filters: List<QueryFilter>,
        orderBy: String?,
        ascending: Boolean,
        limit: Int,
        offset: Int
    ): List<Record> {
        val all = loadAllRecords(col)
        val filtered = all.filter { record ->
            val flat = record.data.toMutableMap()
            flat["id"] = JsonPrimitive(record.id)
            filters.all { matchesFilter(it, flat) }
        }
        val sorted = if (orderBy.isNullOrBlank() || orderBy == "id") {
            filtered.sortedBy { it.id }
        } else {
            filtered.sortedWith { left, right ->
                compareJson(left.data[orderBy], right.data[orderBy])
            }
        }
        val ordered = if (ascending) sorted else sorted.reversed()
        return ordered.drop(offset).take(limit)
    }

    private fun loadAllRecords(col: Collection): List<Record> {
        val records = mutableListOf<Record>()
        driver.executeQuery(
            null,
            "SELECT id, collection_id, data, created, updated FROM records WHERE collection_id = ?",
            1
        ) { bindLong(0, col.id) }.use { c ->
            while (c.next().value) {
                val dataObj = try {
                    json.decodeFromString(JsonObject.serializer(), c.getString(2) ?: "{}")
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }
                records.add(
                    Record(
                        id = c.getString(0) ?: "",
                        collectionId = c.getLong(1) ?: col.id,
                        data = dataObj,
                        created = c.getString(3) ?: "",
                        updated = c.getString(4) ?: ""
                    )
                )
            }
        }
        return records
    }

    private fun matchesFilter(filter: QueryFilter, record: Map<String, JsonElement>): Boolean {
        val actual = record[filter.field]
        val actualText = when (actual) {
            null, is JsonNull -> null
            is JsonPrimitive -> actual.contentOrNull
            else -> actual.toString()
        }
        val expected = filter.value
        val actualNumber = actualText?.toDoubleOrNull()
        val expectedNumber = expected.toDoubleOrNull()
        return when (filter.op.uppercase()) {
            "=", "EQ" -> if (actualNumber != null && expectedNumber != null) actualNumber == expectedNumber else actualText == expected
            "!=", "NEQ" -> if (actualNumber != null && expectedNumber != null) actualNumber != expectedNumber else actualText != expected
            ">", "GT" -> compareNumbers(actualText, expected) > 0
            ">=", "GTE" -> compareNumbers(actualText, expected) >= 0
            "<", "LT" -> compareNumbers(actualText, expected) < 0
            "<=", "LTE" -> compareNumbers(actualText, expected) <= 0
            "LIKE" -> actualText?.let { Regex("^" + Regex.escape(expected).replace("%", ".*") + "$", RegexOption.IGNORE_CASE).matches(it) } == true
            "IN" -> expected.split(",").map { it.trim() }.any { it == actualText }
            else -> false
        }
    }

    private fun compareNumbers(actual: String?, expected: String): Int {
        val actualNumber = actual?.toDoubleOrNull()
        val expectedNumber = expected.toDoubleOrNull()
        return if (actualNumber != null && expectedNumber != null) {
            actualNumber.compareTo(expectedNumber)
        } else {
            actual.orEmpty().compareTo(expected)
        }
    }

    private fun compareJson(left: JsonElement?, right: JsonElement?): Int {
        val leftText = (left as? JsonPrimitive)?.contentOrNull
        val rightText = (right as? JsonPrimitive)?.contentOrNull
        val leftNumber = leftText?.toDoubleOrNull()
        val rightNumber = rightText?.toDoubleOrNull()
        return if (leftNumber != null && rightNumber != null) {
            leftNumber.compareTo(rightNumber)
        } else {
            leftText.orEmpty().compareTo(rightText.orEmpty())
        }
    }

    private fun isMissingJson1(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it.message?.contains("no such function: json_extract", ignoreCase = true) == true
        }

    fun countRecords(collectionName: String, filters: List<QueryFilter> = emptyList(), user: Claims? = null): Long {
        val col = getCollection(collectionName) ?: return 0L
        val schema = col.schema ?: CollectionSchema()
        val rlsSql = PolicyEngine.compileToSql(schema.policies.read, user)
        if (rlsSql == "1=0") return 0L

        val whereClauses = mutableListOf("records.collection_id = ${col.id}", rlsSql)
        val args = mutableListOf<String>()

        for (f in filters) {
            val fieldPath = f.field.replace("'", "''")
            whereClauses.add("json_extract(records.data, '$.$fieldPath') ${f.op} ?")
            args.add(f.value)
        }

        val sql = "SELECT COUNT(*) FROM records WHERE ${whereClauses.joinToString(" AND ")}"
        if (!driver.supportsJson1 && sql.contains("json_extract", ignoreCase = true)) {
            return loadAllRecords(col).count { record ->
                val flat = record.data.toMutableMap()
                flat["id"] = JsonPrimitive(record.id)
                filters.all { matchesFilter(it, flat) }
            }.toLong()
        }
        return try {
            var count = 0L
            driver.executeQuery(null, sql, args.size) {
                args.forEachIndexed { i, v -> bindString(i, v) }
            }.use { c ->
                if (c.next().value) count = c.getLong(0) ?: 0L
            }
            count
        } catch (e: Exception) {
            if (!isMissingJson1(e)) throw e
            loadAllRecords(col).count { record ->
                val flat = record.data.toMutableMap()
                flat["id"] = JsonPrimitive(record.id)
                filters.all { matchesFilter(it, flat) }
            }.toLong()
        }
    }

    // =========================================================================
    // 4. TRANSACTIONAL RECURSIVE DELETE
    // =========================================================================

    fun deleteRecord(collectionName: String, recordId: String, user: Claims? = null): Boolean {
        val deleted: Boolean
        driver.beginTransaction()
        try {
            deleted = deleteRecordRec(collectionName, recordId, user, mutableSetOf())
            driver.setTransactionSuccessful()
        } finally {
            driver.endTransaction()
        }
        return deleted
    }

    private fun deleteRecordRec(
        collectionName: String,
        recordId: String,
        user: Claims?,
        visited: MutableSet<String>
    ): Boolean {
        val id = recordId.trim()
        if (!visited.add(id)) return false

        val col = getCollection(collectionName) ?: return false
        val schema = col.schema ?: CollectionSchema()
        val rlsSql = PolicyEngine.compileToSql(schema.policies.delete, user)
        if (rlsSql == "1=0") error("Access Denied: Delete rejected by policy on collection '$collectionName'.")

        // Resolve cascading origins before deleting: the FK cascade below removes the
        // relation rows that identify them.
        val cascadingOrigins = findCascadingOrigins(id)

        val affected = driver.executeUpdate(
            null,
            "DELETE FROM records WHERE id = ?",
            1
        ) {
            bindString(0, id)
        }

        for (originId in cascadingOrigins) {
            val originCollection = collectionNameForRecord(originId) ?: continue
            deleteRecordRec(originCollection, originId, user, visited)
        }

        return affected > 0
    }

    /**
     * Returns the ids of records that reference [targetRecordId] through a relation marked
     * `cascade_on_target_delete`. Relation values are read from the origin record's JSON and
     * compared using the same text normalization as `records.id`.
     */
    private fun findCascadingOrigins(targetRecordId: String): List<String> {
        val origins = mutableListOf<String>()
        driver.executeQuery(
            null,
            "SELECT DISTINCT origin_rec_id FROM _relations WHERE target_rec_id = ?",
            1
        ) {
            bindString(0, targetRecordId)
        }.use { c ->
            while (c.next().value) {
                c.getString(0)?.let { origins.add(it) }
            }
        }

        val cascading = mutableListOf<String>()
        for (originId in origins) {
            val record = loadRecord(originId) ?: continue
            val originSchema = getCollection(record.collectionId.toString())?.schema ?: continue
            for ((relName, relDef) in originSchema.relations) {
                if (!relDef.cascade_on_target_delete) continue
                val referenced = recordIdText(record.data[relName]) ?: continue
                if (referenced == targetRecordId) {
                    cascading.add(originId)
                    break
                }
            }
        }
        return cascading
    }

    private data class LoadedRecord(val id: String, val collectionId: Long, val data: JsonObject)

    private fun loadRecord(id: String): LoadedRecord? {
        var result: LoadedRecord? = null
        driver.executeQuery(null, "SELECT id, collection_id, data FROM records WHERE id = ? LIMIT 1", 1) {
            bindString(0, id)
        }.use { c ->
            if (c.next().value) {
                val dataObj = try {
                    json.decodeFromString(JsonObject.serializer(), c.getString(2) ?: "{}")
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }
                result = LoadedRecord(id = c.getString(0) ?: id, collectionId = c.getLong(1) ?: 0L, data = dataObj)
            }
        }
        return result
    }

    private fun collectionNameForRecord(recordId: String): String? {
        var name: String? = null
        driver.executeQuery(
            null,
            "SELECT c.name FROM records r JOIN collections c ON c.id = r.collection_id WHERE r.id = ? LIMIT 1",
            1
        ) {
            bindString(0, recordId)
        }.use { c ->
            if (c.next().value) name = c.getString(0)
        }
        return name
    }

    /** Normalizes a JSON relation value to the text form used for `records.id`. */
    private fun recordIdText(el: JsonElement?): String? {
        if (el == null || el is JsonNull) return null
        if (el !is JsonPrimitive) return null
        val text = el.contentOrNull?.trim() ?: return null
        return text.ifEmpty { null }
    }
}

data class QueryFilter(
    val field: String,
    val op: String = "=",
    val value: String
)
