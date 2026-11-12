package engine.db

import app.cash.sqldelight.db.SqlDriver
import engine.db.auth.Claims
import engine.db.auth.PolicyEngine
import engine.db.models.*
import engine.db.models.Collection
import kotlinx.serialization.json.*

class ApexKitDatabaseEngine(private val driver: SqlDriver) {

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
    // 2. RECORD CREATION WITH SCHEMA ENFORCEMENT & UNIQUE INDEX LOCKING
    // =========================================================================

    fun insertRecord(
        collectionName: String,
        data: JsonObject,
        user: Claims? = null
    ): Long {
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

        // 3. Verify uniqueness constraints via _unique_values
        for ((fieldName, fieldDef) in schema.fields) {
            if (fieldDef.unique == true && data.containsKey(fieldName)) {
                val rawVal = data[fieldName]?.jsonPrimitive?.contentOrNull ?: data[fieldName].toString().trim('"')
                val idxKey = "${col.id}-${fieldDef.uid}"
                val conflict = driver.executeQuery(
                    null,
                    "SELECT record_id FROM _unique_values WHERE index_key = ? AND value = ? LIMIT 1",
                    2
                ) {
                    bindString(0, idxKey)
                    bindString(1, rawVal)
                }.use { c -> if (c.next().value) c.getLong(0) else null }

                if (conflict != null) {
                    error("Unique constraint violation: Field '$fieldName' with value '$rawVal' already exists (Record #$conflict).")
                }
            }
        }

        // 4. Verify composite unique constraints
        for (comp in schema.composite_unique) {
            if (comp.all { data.containsKey(it) }) {
                val compKey = "${col.id}-comp-" + comp.joinToString("_")
                val compVal = comp.joinToString("|") { data[it]?.jsonPrimitive?.contentOrNull ?: data[it].toString().trim('"') }
                val conflict = driver.executeQuery(
                    null,
                    "SELECT record_id FROM _unique_values WHERE index_key = ? AND value = ? LIMIT 1",
                    2
                ) {
                    bindString(0, compKey)
                    bindString(1, compVal)
                }.use { c -> if (c.next().value) c.getLong(0) else null }

                if (conflict != null) {
                    error("Composite unique violation on (${comp.joinToString(", ")}): Value '$compVal' already exists.")
                }
            }
        }

        val now = System.currentTimeMillis().toString()
        val dataStr = json.encodeToString(JsonObject.serializer(), data)

        // 5. Insert record
        driver.executeUpdate(
            null,
            "INSERT INTO records (collection_id, data, created, updated) VALUES (?, ?, ?, ?)",
            4
        ) {
            bindLong(0, col.id)
            bindString(1, dataStr)
            bindString(2, now)
            bindString(3, now)
        }
        val recordId = driver.lastInsertedRowId()

        // 6. Register single-field unique tokens in _unique_values
        for ((fieldName, fieldDef) in schema.fields) {
            if (fieldDef.unique == true && data.containsKey(fieldName)) {
                val rawVal = data[fieldName]?.jsonPrimitive?.contentOrNull ?: data[fieldName].toString().trim('"')
                val idxKey = "${col.id}-${fieldDef.uid}"
                driver.executeUpdate(
                    null,
                    "INSERT OR REPLACE INTO _unique_values (index_key, value, record_id) VALUES (?, ?, ?)",
                    3
                ) {
                    bindString(0, idxKey)
                    bindString(1, rawVal)
                    bindLong(2, recordId)
                }
            }
        }

        // 7. Register composite unique tokens
        for (comp in schema.composite_unique) {
            if (comp.all { data.containsKey(it) }) {
                val compKey = "${col.id}-comp-" + comp.joinToString("_")
                val compVal = comp.joinToString("|") { data[it]?.jsonPrimitive?.contentOrNull ?: data[it].toString().trim('"') }
                driver.executeUpdate(
                    null,
                    "INSERT OR REPLACE INTO _unique_values (index_key, value, record_id) VALUES (?, ?, ?)",
                    3
                ) {
                    bindString(0, compKey)
                    bindString(1, compVal)
                    bindLong(2, recordId)
                }
            }
        }

        // 8. Register relations in _relations table
        for ((relName, relDef) in schema.relations) {
            if (data.containsKey(relName)) {
                val targetRecId = data[relName]?.jsonPrimitive?.longOrNull
                if (targetRecId != null) {
                    val targetCol = getCollection(relDef.target_collection)
                    if (targetCol != null) {
                        driver.executeUpdate(
                            null,
                            """
                            INSERT OR REPLACE INTO _relations
                            (origin_col_id, origin_rec_id, target_col_id, target_rec_id, rel_name, properties)
                            VALUES (?, ?, ?, ?, ?, NULL)
                            """.trimIndent(),
                            5
                        ) {
                            bindLong(0, col.id)
                            bindLong(1, recordId)
                            bindLong(2, targetCol.id)
                            bindLong(3, targetRecId)
                            bindString(4, relName)
                        }
                    }
                }
            }
        }

        return recordId
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
                        id = c.getLong(0) ?: 0L,
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
        var count = 0L
        driver.executeQuery(null, sql, args.size) {
            args.forEachIndexed { i, v -> bindString(i, v) }
        }.use { c ->
            if (c.next().value) count = c.getLong(0) ?: 0L
        }
        return count
    }

    fun deleteRecord(collectionName: String, recordId: Long, user: Claims? = null): Boolean {
        val col = getCollection(collectionName) ?: return false
        val schema = col.schema ?: CollectionSchema()
        val rlsSql = PolicyEngine.compileToSql(schema.policies.delete, user)
        if (rlsSql == "1=0") error("Access Denied: Delete rejected by policy on collection '$collectionName'.")

        // 1. Delete unique tokens
        driver.executeUpdate(null, "DELETE FROM _unique_values WHERE record_id = ?", 1) {
            bindLong(0, recordId)
        }
        // 2. Delete relations
        driver.executeUpdate(null, "DELETE FROM _relations WHERE origin_rec_id = ? OR target_rec_id = ?", 2) {
            bindLong(0, recordId)
            bindLong(1, recordId)
        }
        // 3. Delete record
        val affected = driver.executeUpdate(
            null,
            "DELETE FROM records WHERE collection_id = ? AND id = ?",
            2
        ) {
            bindLong(0, col.id)
            bindLong(1, recordId)
        }
        return affected > 0
    }
}

data class QueryFilter(
    val field: String,
    val op: String = "=",
    val value: String
)