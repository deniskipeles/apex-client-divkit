package engine.db

/**
 * Typed result of an engine operation. Kept in common code so the router
 * can map it into DivKit variables without platform branching.
 */
sealed class OpResult {
    data class Rows(val rows: List<Map<String, Any?>>) : OpResult()
    data class Row(val row: Map<String, Any?>?) : OpResult()
    data class Scalar(val value: Any?) : OpResult()
    data class RowId(val id: Long) : OpResult()
    data class Count(val n: Long) : OpResult()
    object Empty : OpResult()
}
