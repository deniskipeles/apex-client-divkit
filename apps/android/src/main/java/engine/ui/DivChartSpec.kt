package engine.ui

import engine.state.DivVariableValue
import kotlinx.serialization.json.*

/**
 * Supported Server-Driven UI (SDUI) Chart types across Android and iOS DivKit custom blocks.
 */
enum class DivChartType(val wireName: String, val displayLabel: String) {
    BAR("bar", "Bar Chart"),
    LINE("line", "Line Chart"),
    AREA("area", "Area Trend"),
    DONUT("donut", "Donut Chart"),
    PIE("pie", "Pie Chart");

    companion object {
        fun fromWireName(raw: String?): DivChartType {
            return when (raw?.trim()?.lowercase()) {
                "bar", "bar_chart", "column" -> BAR
                "line", "line_chart" -> LINE
                "area", "area_chart", "spline" -> AREA
                "donut", "donut_chart", "doughnut" -> DONUT
                "pie", "pie_chart" -> PIE
                else -> BAR
            }
        }
    }
}

/**
 * A single resolved data point rendered by the native platform chart engine
 * (Android Vico / Compose Chart Engine, iOS Swift Charts / DGCharts).
 */
data class DivChartPoint(
    val id: String,
    val label: String,
    val value: Double,
    val secondaryValue: Double? = null,
    val category: String = "",
    val colorHex: String = "#38BDF8"
)

/**
 * Cross-platform SDUI Chart specification parsed from a DivKit `custom` block:
 * ```json
 * {
 *   "type": "custom",
 *   "custom_type": "sdui_chart",
 *   "custom_props": {
 *     "chart_type": "bar",
 *     "title": "Live Terminal Revenue by SKU",
 *     "subtitle": "Reactive binding to @{user.orders_json}",
 *     "data_var": "user.orders_json",
 *     "label_field": "item_name",
 *     "value_field": "price",
 *     "value_prefix": "$",
 *     "allow_type_switch": true,
 *     "points": [ ... ]
 *   }
 * }
 * ```
 */
data class DivChartSpec(
    val id: String,
    val chartType: DivChartType,
    val title: String,
    val subtitle: String,
    val dataVar: String?,
    val labelField: String,
    val valueField: String,
    val valuePrefix: String,
    val valueSuffix: String,
    val accentHex: String,
    val heightDp: Int,
    val allowTypeSwitch: Boolean,
    val selectionVar: String,
    val points: List<DivChartPoint>
) {
    val totalValue: Double get() = points.sumOf { it.value }
    val maxValue: Double get() = points.maxOfOrNull { it.value } ?: 0.0
    val avgValue: Double get() = if (points.isNotEmpty()) totalValue / points.size else 0.0
}

object DivChartParser {

    private val defaultPalette = listOf(
        "#0284C7",
        "#10B981",
        "#6366F1",
        "#F59E0B",
        "#EC4899",
        "#8B5CF6",
        "#14B8A6",
        "#F97316"
    )

    fun isChartCustomType(typeOrCustomType: String?): Boolean {
        val clean = typeOrCustomType?.trim()?.lowercase() ?: return false
        return clean in setOf(
            "sdui_chart",
            "chart",
            "bar_chart",
            "line_chart",
            "area_chart",
            "donut_chart",
            "pie_chart"
        )
    }

    /**
     * Parses a DivKit `custom` block node and resolves either its reactive `user.*` JSON
     * array variable ([variables]) or inline `points` array into a [DivChartSpec].
     */
    fun parse(
        node: JsonObject,
        variables: Map<String, DivVariableValue>
    ): DivChartSpec {
        val customType = node["custom_type"]?.jsonPrimitive?.contentOrNull
            ?: node["type"]?.jsonPrimitive?.contentOrNull
            ?: "sdui_chart"
        val props = node["custom_props"]?.jsonObject ?: node

        val rawChartType = props["chart_type"]?.jsonPrimitive?.contentOrNull
            ?: customType.removeSuffix("_chart")
        val chartType = DivChartType.fromWireName(rawChartType)

        val title = props["title"]?.jsonPrimitive?.contentOrNull ?: "POS Analytics Chart"
        val subtitle = props["subtitle"]?.jsonPrimitive?.contentOrNull
            ?: "KMP SDUI Chart • Native Android (Vico/Compose) & iOS (Swift Charts/DGCharts)"
        val dataVar = props["data_var"]?.jsonPrimitive?.contentOrNull
        val labelField = props["label_field"]?.jsonPrimitive?.contentOrNull ?: "label"
        val valueField = props["value_field"]?.jsonPrimitive?.contentOrNull ?: "value"
        val valuePrefix = props["value_prefix"]?.jsonPrimitive?.contentOrNull ?: ""
        val valueSuffix = props["value_suffix"]?.jsonPrimitive?.contentOrNull ?: ""
        val accentHex = props["accent_hex"]?.jsonPrimitive?.contentOrNull ?: "#0284C7"
        val heightDp = (props["height"]?.jsonPrimitive?.intOrNull ?: 210).coerceIn(140, 360)
        val allowTypeSwitch = props["allow_type_switch"]?.jsonPrimitive?.booleanOrNull ?: true
        val selectionVar = props["selection_var"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.startsWith("user.") }
            ?: "user.selected_chart_point"
        val chartId = node["id"]?.jsonPrimitive?.contentOrNull
            ?: props["id"]?.jsonPrimitive?.contentOrNull
            ?: "chart_${title.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"

        // 1. Try resolving reactive `data_var` from EngineStateBridge first
        val reactivePoints = if (!dataVar.isNullOrBlank()) {
            parsePointsFromVariable(
                varValue = variables[dataVar],
                labelField = labelField,
                valueField = valueField
            )
        } else {
            emptyList()
        }

        // 2. Fallback to inline `points` or `series` in `custom_props`
        val resolvedPoints = if (reactivePoints.isNotEmpty()) {
            reactivePoints
        } else {
            val inlineArr = props["points"]?.jsonArray ?: props["series"]?.jsonArray
            if (inlineArr != null) {
                parsePointsFromJsonArray(inlineArr, labelField, valueField)
            } else {
                defaultFallbackPoints()
            }
        }

        return DivChartSpec(
            id = chartId,
            chartType = chartType,
            title = title,
            subtitle = subtitle,
            dataVar = dataVar,
            labelField = labelField,
            valueField = valueField,
            valuePrefix = valuePrefix,
            valueSuffix = valueSuffix,
            accentHex = accentHex,
            heightDp = heightDp,
            allowTypeSwitch = allowTypeSwitch,
            selectionVar = selectionVar,
            points = resolvedPoints
        )
    }

    private fun parsePointsFromVariable(
        varValue: DivVariableValue?,
        labelField: String,
        valueField: String
    ): List<DivChartPoint> {
        val rawJson = (varValue as? DivVariableValue.Str)?.value ?: return emptyList()
        if (rawJson.isBlank()) return emptyList()
        return try {
            val arr = Json.parseToJsonElement(rawJson).jsonArray
            parsePointsFromJsonArray(arr, labelField, valueField)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parsePointsFromJsonArray(
        arr: JsonArray,
        labelField: String,
        valueField: String
    ): List<DivChartPoint> {
        // Aggregate by label so repeated order items (e.g. multiple orders of the same SKU) sum cleanly
        val aggregated = linkedMapOf<String, Double>()
        val colorByLabel = mutableMapOf<String, String>()
        val categoryByLabel = mutableMapOf<String, String>()

        arr.forEachIndexed { idx, el ->
            val obj = el as? JsonObject ?: return@forEachIndexed
            val rawLabel = obj[labelField]?.jsonPrimitive?.contentOrNull
                ?: obj["label"]?.jsonPrimitive?.contentOrNull
                ?: obj["item_name"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull
                ?: obj["sku"]?.jsonPrimitive?.contentOrNull
                ?: "Item ${idx + 1}"

            val qtyMultiplier = if (valueField == "price" && obj.containsKey("qty")) {
                (obj["qty"]?.jsonPrimitive?.doubleOrNull ?: 1.0).coerceAtLeast(1.0)
            } else {
                1.0
            }

            val rawVal = (obj[valueField]?.jsonPrimitive?.doubleOrNull
                ?: obj["value"]?.jsonPrimitive?.doubleOrNull
                ?: obj["price"]?.jsonPrimitive?.doubleOrNull
                ?: obj["stock"]?.jsonPrimitive?.doubleOrNull
                ?: 0.0) * qtyMultiplier

            aggregated[rawLabel] = (aggregated[rawLabel] ?: 0.0) + rawVal
            val customColor = obj["color_hex"]?.jsonPrimitive?.contentOrNull
                ?: obj["color"]?.jsonPrimitive?.contentOrNull
            if (customColor != null && rawLabel !in colorByLabel) {
                colorByLabel[rawLabel] = customColor
            }
            val cat = obj["category"]?.jsonPrimitive?.contentOrNull
                ?: obj["status"]?.jsonPrimitive?.contentOrNull
            if (cat != null && rawLabel !in categoryByLabel) {
                categoryByLabel[rawLabel] = cat
            }
        }

        return aggregated.entries.mapIndexed { idx, (label, totalVal) ->
            DivChartPoint(
                id = "pt_$idx",
                label = label,
                value = totalVal,
                category = categoryByLabel[label] ?: "",
                colorHex = colorByLabel[label] ?: defaultPalette[idx % defaultPalette.size]
            )
        }
    }

    private fun defaultFallbackPoints(): List<DivChartPoint> = listOf(
        DivChartPoint("p1", "09:00", 420.0, colorHex = "#0284C7"),
        DivChartPoint("p2", "11:00", 680.0, colorHex = "#10B981"),
        DivChartPoint("p3", "13:00", 950.0, colorHex = "#6366F1"),
        DivChartPoint("p4", "15:00", 610.0, colorHex = "#F59E0B"),
        DivChartPoint("p5", "17:00", 1120.0, colorHex = "#EC4899"),
        DivChartPoint("p6", "19:00", 890.0, colorHex = "#8B5CF6")
    )
}
