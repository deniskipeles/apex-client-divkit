package com.yandex.divkit.multiplatform

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class DivKitDependencies(val context: Context?)

class DivKitEnvironment private constructor(val dependencies: DivKitDependencies) {
    class Builder(private val baseContext: Context) {
        fun build(): DivKitEnvironment = DivKitEnvironment(DivKitDependencies(baseContext))
    }
}

private val LocalDivKitDependencies = staticCompositionLocalOf<DivKitDependencies?> { null }

@Composable
fun DivKit(
    dependencies: DivKitDependencies,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalDivKitDependencies provides dependencies) {
        content()
    }
}

/**
 * Dynamic Server-Driven UI (SDUI) DivKit Renderer for Jetpack Compose.
 *
 * Parses verified DivKit JSON AST (`card` -> `states` -> `div` tree), binds reactive
 * `user.*` variables from [EngineStateBridge], supports the native `json_list` custom
 * block (mirroring iOS `EngineJsonListBlockFactory`), adapts automatically to Light
 * and Dark system themes, and dispatches `local-db://`, `hardware://`, `workspace://`,
 * and `div-action://` actions.
 */
@Composable
fun DivView(
    cardId: String,
    jsonData: String,
    modifier: Modifier = Modifier,
    onAction: suspend (url: String, payload: String?) -> Unit = { _, _ -> }
) {
    val bridgeState by EngineStateBridge.state.collectAsState()
    val scope = rememberCoroutineScope()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val parsedRoot = remember(jsonData) {
        try {
            Json.parseToJsonElement(jsonData).jsonObject
        } catch (e: Exception) {
            null
        }
    }

    // Seed initial card variables if declared in JSON and not yet present in EngineStateBridge
    LaunchedEffect(parsedRoot) {
        val card = parsedRoot?.get("card")?.jsonObject ?: parsedRoot
        val vars = card?.get("variables")?.jsonArray ?: return@LaunchedEffect
        val current = EngineStateBridge.state.value
        val batch = mutableMapOf<String, DivVariableValue>()
        for (v in vars) {
            val obj = v.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            if (!name.startsWith("user.") || name in current) continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "string"
            val valueEl = obj["value"]
            val dv = when (type) {
                "integer" -> DivVariableValue.IntVal(valueEl?.jsonPrimitive?.longOrNull ?: 0L)
                "number" -> DivVariableValue.DoubleVal(valueEl?.jsonPrimitive?.doubleOrNull ?: 0.0)
                "boolean" -> DivVariableValue.Bool(valueEl?.jsonPrimitive?.booleanOrNull ?: false)
                else -> DivVariableValue.Str(valueEl?.jsonPrimitive?.contentOrNull ?: "")
            }
            batch[name] = dv
        }
        if (batch.isNotEmpty()) {
            EngineStateBridge.pushBatch(batch)
        }
    }

    if (parsedRoot == null) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            modifier = modifier.fillMaxWidth().padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "DivKit JSON Parse Error",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Ensure the SDUI payload is valid JSON.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 13.sp
                )
            }
        }
        return
    }

    val cardObj = parsedRoot["card"]?.jsonObject ?: parsedRoot
    val states = cardObj["states"]?.jsonArray
    val rootDiv = if (!states.isNullOrEmpty()) {
        states[0].jsonObject["div"]?.jsonObject
    } else {
        cardObj["div"]?.jsonObject ?: cardObj
    }

    if (rootDiv != null) {
        AnimatedVisibility(visible = true, enter = fadeIn()) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .testTag("divkit_root_$cardId")
            ) {
                RenderDivNode(
                    node = rootDiv,
                    variables = bridgeState,
                    rowContext = emptyMap(),
                    isDark = isDark,
                    onDispatchAction = { url, payloadStr ->
                        scope.launch {
                            if (url.startsWith("div-action://set_variable")) {
                                handleDivSetVariable(url)
                            } else {
                                onAction(url, payloadStr)
                            }
                        }
                    }
                )
            }
        }
    }
}

private fun handleDivSetVariable(url: String) {
    val query = url.substringAfter("?", "")
    val params = query.split("&").mapNotNull { part ->
        val idx = part.indexOf("=")
        if (idx > 0) part.substring(0, idx) to java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
        else null
    }.toMap()
    val name = params["name"] ?: return
    val value = params["value"] ?: ""
    if (name.startsWith("user.")) {
        EngineStateBridge.pushUpdate(name, DivVariableValue.Str(value))
    }
}

@Composable
private fun RenderDivNode(
    node: JsonObject,
    variables: Map<String, DivVariableValue>,
    rowContext: Map<String, Any?>,
    isDark: Boolean,
    onDispatchAction: (url: String, payload: String?) -> Unit
) {
    val type = node["type"]?.jsonPrimitive?.contentOrNull ?: "container"
    val paddings = node["paddings"]?.jsonObject
    val margins = node["margins"]?.jsonObject
    val cornerRadius = node["corner_radius"]?.jsonPrimitive?.intOrNull ?: 0
    val bgHex = node["background_color"]?.jsonPrimitive?.contentOrNull
        ?: node["background"]?.jsonArray?.firstOrNull()?.jsonObject?.get("color")?.jsonPrimitive?.contentOrNull
    val borderHex = node["border"]?.jsonObject?.get("stroke")?.jsonObject?.get("color")?.jsonPrimitive?.contentOrNull
    val borderWidth = node["border"]?.jsonObject?.get("stroke")?.jsonObject?.get("width")?.jsonPrimitive?.intOrNull ?: 0
    val borderRadius = node["border"]?.jsonObject?.get("corner_radius")?.jsonPrimitive?.intOrNull ?: cornerRadius

    var boxModifier: Modifier = Modifier
    if (margins != null) {
        boxModifier = boxModifier.padding(
            start = (margins["left"]?.jsonPrimitive?.intOrNull ?: 0).dp,
            top = (margins["top"]?.jsonPrimitive?.intOrNull ?: 0).dp,
            end = (margins["right"]?.jsonPrimitive?.intOrNull ?: 0).dp,
            bottom = (margins["bottom"]?.jsonPrimitive?.intOrNull ?: 0).dp
        )
    }
    if (borderRadius > 0) {
        boxModifier = boxModifier.clip(RoundedCornerShape(borderRadius.dp))
    }
    if (bgHex != null) {
        val resolvedBg = adaptSurfaceColorForTheme(evalExpr(bgHex, variables, rowContext), isDark)
        boxModifier = boxModifier.background(resolvedBg)
        // Add subtle border in light mode when a container doesn't specify one so cards stay crisp like Screenshot 2
        if (!isDark && borderHex == null && borderRadius > 0) {
            boxModifier = boxModifier.border(
                width = 1.dp,
                color = Color(0xFFCBD5E1),
                shape = RoundedCornerShape(borderRadius.dp)
            )
        }
    }
    if (borderHex != null && borderWidth > 0) {
        boxModifier = boxModifier.border(
            width = borderWidth.dp,
            color = adaptBorderColorForTheme(evalExpr(borderHex, variables, rowContext), isDark),
            shape = RoundedCornerShape(borderRadius.dp)
        )
    }
    if (paddings != null) {
        boxModifier = boxModifier.padding(
            start = (paddings["left"]?.jsonPrimitive?.intOrNull ?: 0).dp,
            top = (paddings["top"]?.jsonPrimitive?.intOrNull ?: 0).dp,
            end = (paddings["right"]?.jsonPrimitive?.intOrNull ?: 0).dp,
            bottom = (paddings["bottom"]?.jsonPrimitive?.intOrNull ?: 0).dp
        )
    }

    when (type) {
        "container" -> {
            val orientation = node["orientation"]?.jsonPrimitive?.contentOrNull ?: "vertical"
            val items = node["items"]?.jsonArray ?: JsonArray(emptyList())
            val spacing = (node["item_spacing"]?.jsonPrimitive?.intOrNull ?: 8).dp
            if (orientation == "horizontal") {
                Row(
                    modifier = Modifier.fillMaxWidth().then(boxModifier),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (child in items) {
                        val childObj = child.jsonObject
                        val weight = childObj["weight"]?.jsonPrimitive?.floatOrNull
                        Box(modifier = if (weight != null && weight > 0f) Modifier.weight(weight) else Modifier) {
                            RenderDivNode(childObj, variables, rowContext, isDark, onDispatchAction)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().then(boxModifier),
                    verticalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    for (child in items) {
                        RenderDivNode(child.jsonObject, variables, rowContext, isDark, onDispatchAction)
                    }
                }
            }
        }

        "gallery" -> {
            val items = node["items"]?.jsonArray ?: JsonArray(emptyList())
            val spacing = (node["item_spacing"]?.jsonPrimitive?.intOrNull ?: 10).dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .then(boxModifier),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                for (child in items) {
                    RenderDivNode(child.jsonObject, variables, rowContext, isDark, onDispatchAction)
                }
            }
        }

        "grid" -> {
            val columnCount = (node["column_count"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(1, 4)
            val items = node["items"]?.jsonArray ?: JsonArray(emptyList())
            val spacing = (node["item_spacing"]?.jsonPrimitive?.intOrNull ?: 10).dp
            val rows = items.chunked(columnCount)
            Column(
                modifier = Modifier.fillMaxWidth().then(boxModifier),
                verticalArrangement = Arrangement.spacedBy(spacing)
            ) {
                for (rowItems in rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing)
                    ) {
                        for (child in rowItems) {
                            Box(modifier = Modifier.weight(1f)) {
                                RenderDivNode(child.jsonObject, variables, rowContext, isDark, onDispatchAction)
                            }
                        }
                        repeat(columnCount - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        "text" -> {
            val rawText = node["text"]?.jsonPrimitive?.contentOrNull ?: ""
            val resolvedText = evalExpr(rawText, variables, rowContext)
            val fontSize = (node["font_size"]?.jsonPrimitive?.intOrNull ?: 14).sp
            val fontWeightStr = node["font_weight"]?.jsonPrimitive?.contentOrNull ?: "regular"
            val fontWeight = when (fontWeightStr) {
                "bold" -> FontWeight.Bold
                "medium", "semibold" -> FontWeight.SemiBold
                "light" -> FontWeight.Light
                else -> FontWeight.Normal
            }
            val colorHex = node["text_color"]?.jsonPrimitive?.contentOrNull ?: "#F8FAFC"
            val alignStr = node["alignment_horizontal"]?.jsonPrimitive?.contentOrNull ?: "left"
            val textAlign = when (alignStr) {
                "center" -> TextAlign.Center
                "right" -> TextAlign.End
                else -> TextAlign.Start
            }
            val monospace = node["font_family"]?.jsonPrimitive?.contentOrNull == "monospace"
            val actions = extractActions(node)

            val clickableMod = if (actions.isNotEmpty()) {
                Modifier.clickable {
                    dispatchAllActions(actions, variables, rowContext, onDispatchAction)
                }
            } else Modifier

            Text(
                text = resolvedText,
                fontSize = fontSize,
                fontWeight = fontWeight,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                color = adaptTextColorForTheme(evalExpr(colorHex, variables, rowContext), isDark),
                textAlign = textAlign,
                maxLines = node["max_lines"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .then(if (alignStr == "center" || alignStr == "right") Modifier.fillMaxWidth() else Modifier)
                    .then(boxModifier)
                    .then(clickableMod)
            )
        }

        "button" -> {
            val rawText = node["text"]?.jsonPrimitive?.contentOrNull ?: "Action"
            val resolvedText = evalExpr(rawText, variables, rowContext)
            val rawSubtitle = node["subtitle"]?.jsonPrimitive?.contentOrNull
            val resolvedSubtitle = rawSubtitle?.let { evalExpr(it, variables, rowContext) }
            val btnBgHex = node["background_color"]?.jsonPrimitive?.contentOrNull ?: "#2563EB"
            val btnTextHex = node["text_color"]?.jsonPrimitive?.contentOrNull ?: "#FFFFFF"
            val radius = node["corner_radius"]?.jsonPrimitive?.intOrNull ?: 10
            val iconName = node["icon"]?.jsonPrimitive?.contentOrNull
            val actions = extractActions(node)
            val logId = node["id"]?.jsonPrimitive?.contentOrNull
                ?: node["action"]?.jsonObject?.get("log_id")?.jsonPrimitive?.contentOrNull
                ?: resolvedText.lowercase().replace(Regex("[^a-z0-9]+"), "_")

            val resolvedBtnBg = adaptButtonBgForTheme(evalExpr(btnBgHex, variables, rowContext), isDark)
            val resolvedBtnText = adaptButtonTextForTheme(
                evalExpr(btnTextHex, variables, rowContext),
                evalExpr(btnBgHex, variables, rowContext),
                isDark
            )

            Surface(
                color = resolvedBtnBg,
                shape = RoundedCornerShape(radius.dp),
                border = if (!isDark && btnBgHex.uppercase() in setOf("#334155", "#1E293B", "#0F172A")) {
                    BorderStroke(1.dp, Color(0xFFCBD5E1))
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(radius.dp))
                    .clickable {
                        dispatchAllActions(actions, variables, rowContext, onDispatchAction)
                    }
                    .testTag("div_btn_$logId")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (iconName != null) {
                        Icon(
                            imageVector = resolveIcon(iconName),
                            contentDescription = resolvedText,
                            tint = resolvedBtnText,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = resolvedText,
                            color = resolvedBtnText,
                            fontWeight = FontWeight.Bold,
                            fontSize = (node["font_size"]?.jsonPrimitive?.intOrNull ?: 14).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!resolvedSubtitle.isNullOrBlank()) {
                            Text(
                                text = resolvedSubtitle,
                                color = resolvedBtnText.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        "input" -> {
            val varName = node["text_variable"]?.jsonPrimitive?.contentOrNull ?: "user.input_text"
            val hint = node["hint_text"]?.jsonPrimitive?.contentOrNull ?: "Enter value..."
            val currentVal = variables[varName]?.asDisplayString() ?: ""
            OutlinedTextField(
                value = currentVal,
                onValueChange = { newValue ->
                    if (varName.startsWith("user.")) {
                        EngineStateBridge.pushUpdate(varName, DivVariableValue.Str(newValue))
                    }
                },
                label = { Text(hint, fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(boxModifier)
                    .testTag("div_input_${varName.replace('.', '_')}")
            )
        }

        "separator" -> {
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth().then(boxModifier),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
        }

        "json_list", "custom" -> {
            val customType = node["custom_type"]?.jsonPrimitive?.contentOrNull ?: type
            if (customType == "json_list" || type == "json_list") {
                val props = node["custom_props"]?.jsonObject ?: node
                val dataVar = props["data_var"]?.jsonPrimitive?.contentOrNull ?: "user.items"
                val emptyText = props["empty_text"]?.jsonPrimitive?.contentOrNull ?: "No records"
                val rowCard = props["row_card"]?.jsonObject

                val itemsList = remember(variables[dataVar]) {
                    parseJsonListVariable(variables[dataVar])
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(boxModifier)
                        .testTag("div_json_list_${dataVar.replace('.', '_')}")
                ) {
                    if (itemsList.isEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.Inbox,
                                    contentDescription = emptyText,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = emptyText,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else if (rowCard != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsList.forEachIndexed { idx, itemMap ->
                                key(itemMap["id"] ?: idx) {
                                    RenderDivNode(
                                        node = rowCard,
                                        variables = variables,
                                        rowContext = itemMap,
                                        isDark = isDark,
                                        onDispatchAction = onDispatchAction
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun adaptSurfaceColorForTheme(hexStr: String, isDark: Boolean): Color {
    if (isDark) return parseHexColor(hexStr)
    return when (hexStr.trim().uppercase()) {
        "#1E293B", "#152033" -> Color(0xFFFFFFFF)
        "#0F172A", "#0B1120" -> Color(0xFFF8FAFC)
        else -> parseHexColor(hexStr)
    }
}

private fun adaptBorderColorForTheme(hexStr: String, isDark: Boolean): Color {
    if (isDark) return parseHexColor(hexStr)
    return when (hexStr.trim().uppercase()) {
        "#334155", "#1E293B" -> Color(0xFFCBD5E1)
        "#38BDF8" -> Color(0xFF93C5FD)
        else -> parseHexColor(hexStr)
    }
}

private fun adaptTextColorForTheme(hexStr: String, isDark: Boolean): Color {
    if (isDark) return parseHexColor(hexStr)
    return when (hexStr.trim().uppercase()) {
        "#F8FAFC", "#E2E8F0", "#FFFFFF" -> Color(0xFF0F172A)
        "#94A3B8", "#CBD5E1" -> Color(0xFF475569)
        "#64748B" -> Color(0xFF64748B)
        "#38BDF8" -> Color(0xFF1D4ED8)
        "#34D399" -> Color(0xFF047857)
        "#FBBF24" -> Color(0xFFB45309)
        "#F87171", "#FCA5A5" -> Color(0xFFDC2626)
        else -> parseHexColor(hexStr)
    }
}

private fun adaptButtonBgForTheme(hexStr: String, isDark: Boolean): Color {
    if (isDark) return parseHexColor(hexStr)
    return when (hexStr.trim().uppercase()) {
        "#334155", "#1E293B" -> Color(0xFFF1F5F9)
        "#3F1D24" -> Color(0xFFFEE2E2)
        "#0EA5E9" -> Color(0xFF1D4ED8)
        else -> parseHexColor(hexStr)
    }
}

private fun adaptButtonTextForTheme(textHex: String, bgHex: String, isDark: Boolean): Color {
    if (isDark) return parseHexColor(textHex)
    val upperBg = bgHex.trim().uppercase()
    if (upperBg in setOf("#334155", "#1E293B")) {
        return when (textHex.trim().uppercase()) {
            "#38BDF8" -> Color(0xFF1D4ED8)
            "#A78BFA" -> Color(0xFF6D28D9)
            "#34D399" -> Color(0xFF047857)
            else -> Color(0xFF0F172A)
        }
    }
    if (upperBg == "#3F1D24") return Color(0xFFB91C1C)
    if (upperBg == "#0EA5E9") return Color(0xFFFFFFFF)
    return parseHexColor(textHex)
}

private fun parseJsonListVariable(valObj: DivVariableValue?): List<Map<String, Any?>> {
    if (valObj == null) return emptyList()
    return when (valObj) {
        is DivVariableValue.Str -> {
            val raw = valObj.value.trim()
            if (!raw.startsWith("[")) return emptyList()
            try {
                val arr = Json.parseToJsonElement(raw).jsonArray
                arr.mapNotNull { el ->
                    (el as? JsonObject)?.mapValues { (_, v) ->
                        when (v) {
                            is JsonNull -> null
                            is JsonPrimitive -> v.content
                            else -> v.toString()
                        }
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        is DivVariableValue.ArrayVal -> {
            valObj.values.mapIndexed { idx, v ->
                mapOf("index" to idx, "value" to v.asDisplayString(), "title" to v.asDisplayString())
            }
        }
        else -> emptyList()
    }
}

private fun extractActions(node: JsonObject): List<JsonObject> {
    val list = mutableListOf<JsonObject>()
    node["action"]?.jsonObject?.let { list.add(it) }
    node["actions"]?.jsonArray?.forEach { el ->
        (el as? JsonObject)?.let { list.add(it) }
    }
    return list
}

private fun dispatchAllActions(
    actions: List<JsonObject>,
    variables: Map<String, DivVariableValue>,
    rowContext: Map<String, Any?>,
    onDispatchAction: (url: String, payload: String?) -> Unit
) {
    for (act in actions) {
        val rawUrl = act["url"]?.jsonPrimitive?.contentOrNull ?: continue
        val resolvedUrl = evalExpr(rawUrl, variables, rowContext)
        val payloadObj = act["payload"]?.jsonObject
        val resolvedPayload = payloadObj?.let { resolveJsonExpressions(it, variables, rowContext).toString() }
        onDispatchAction(resolvedUrl, resolvedPayload)
    }
}

private fun resolveJsonExpressions(
    el: JsonElement,
    variables: Map<String, DivVariableValue>,
    rowContext: Map<String, Any?>
): JsonElement {
    return when (el) {
        is JsonNull -> JsonNull
        is JsonPrimitive -> {
            if (el.isString) {
                val raw = el.content
                val evaluated = evalExpr(raw, variables, rowContext)
                if (raw.startsWith("@{") && raw.endsWith("}") && !raw.drop(2).dropLast(1).contains("@{")) {
                    evaluated.toLongOrNull()?.let { return JsonPrimitive(it) }
                    evaluated.toDoubleOrNull()?.let { return JsonPrimitive(it) }
                    if (evaluated == "true" || evaluated == "false") return JsonPrimitive(evaluated.toBoolean())
                }
                JsonPrimitive(evaluated)
            } else el
        }
        is JsonArray -> JsonArray(el.map { resolveJsonExpressions(it, variables, rowContext) })
        is JsonObject -> JsonObject(el.mapValues { (_, v) -> resolveJsonExpressions(v, variables, rowContext) })
    }
}

private val EXPR_REGEX = Regex("@\\{([^}]+)\\}")

private fun evalExpr(
    input: String,
    variables: Map<String, DivVariableValue>,
    rowContext: Map<String, Any?>
): String {
    if (!input.contains("@{")) return input
    return EXPR_REGEX.replace(input) { match ->
        val token = match.groupValues[1].trim()
        when {
            token.startsWith("item.") -> {
                val field = token.removePrefix("item.")
                rowContext[field]?.toString() ?: ""
            }
            token.startsWith("user.item.") -> {
                val field = token.removePrefix("user.item.")
                rowContext[field]?.toString() ?: ""
            }
            else -> {
                variables[token]?.asDisplayString() ?: ""
            }
        }
    }
}

private fun parseHexColor(hexStr: String): Color {
    val clean = hexStr.trim().removePrefix("#")
    return try {
        when (clean.length) {
            6 -> Color(("FF$clean").toLong(16))
            8 -> Color(clean.toLong(16))
            else -> Color(0xFF38BDF8)
        }
    } catch (_: Exception) {
        Color(0xFF38BDF8)
    }
}

private fun resolveIcon(name: String) = when (name.lowercase()) {
    "add", "plus", "cart_add" -> Icons.Default.AddShoppingCart
    "scan", "barcode", "scanner" -> Icons.Default.QrCodeScanner
    "print", "printer", "receipt" -> Icons.Default.Print
    "nfc", "tap", "card" -> Icons.Default.Contactless
    "delete", "trash", "clear" -> Icons.Default.DeleteOutline
    "sync", "cloud" -> Icons.Default.Sync
    "check", "checkout", "pay" -> Icons.Default.CheckCircle
    "inventory", "box" -> Icons.Default.Inventory2
    "refresh" -> Icons.Default.Refresh
    else -> Icons.Default.FlashOn
}
