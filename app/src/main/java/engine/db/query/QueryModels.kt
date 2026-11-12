package engine.db.query

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApexQuery(
    val from: String, // Target Collection Name (e.g., "students", "books", "courses")
    val system: Boolean = false,
    val select: List<SelectField> = emptyList(),
    @SerialName("where") val r#where: JsonElement? = null,
    val group_by: List<String> = emptyList(),
    val sort: String? = null,
    val limit: Long? = 100,
    val offset: Long? = 0,
    val pipeline: List<PipelineStep> = emptyList()
)

@Serializable
sealed class SelectField {
    @Serializable
    data class Simple(val value: String) : SelectField()

    @Serializable
    data class Complex(
        val field: String? = null,
        val expr: String? = null,
        val fn: String? = null, // sum, avg, count, min, max, year, month, day
        val `as`: String? = null
    ) : SelectField()
}

@Serializable
sealed class PipelineStep {
    @Serializable
    data class CumulativeSum(val field: String, val output_field: String) : PipelineStep()

    @Serializable
    data class Pivot(val key: String, val value: String, val agg: String = "sum") : PipelineStep()
}