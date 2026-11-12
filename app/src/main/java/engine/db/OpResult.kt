package engine.db

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

sealed class OpResult {
    data class Rows(val rows: List<Map<String, Any?>>, val rawRecords: List<engine.db.models.Record> = emptyList()) : OpResult()
    data class Row(val row: Map<String, Any?>?) : OpResult()
    data class Scalar(val value: Any?) : OpResult()
    data class RowId(val id: Long) : OpResult()
    data class Count(val n: Long) : OpResult()
    data class SchemaImported(val collectionsCount: Int) : OpResult()
    object Empty : OpResult()
}