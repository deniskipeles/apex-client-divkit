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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import engine.icons.NativeIconCatalog
import engine.icons.NativeIconVectors
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class DivKitDependencies(val context: Context?)

class DivKitEnvironment private constructor(val dependencies: DivKitDependencies) {
    class Builder(private val baseContext: Context) {
        fun build(): DivKitEnvironment = DivKitEnvironment(DivKitDependencies(baseContext))
    }
}

private val LocalDivKitDependencies = staticCompositionLocalOf<DivKitDependencies?> { null }

/**
 * Runs the card's `_timers` declarations. DivKit timers are addressed by
 * `div-action://timer?id=<timer>&action=start|stop`; each tick re-reads the bridge so a
 * timer observes variables produced by the actions it dispatched.
 */
private class DivTimerController(private val scope: CoroutineScope) {
    private val jobs = mutableMapOf<String, Job>()

    fun handle(
        url: String,
        card: JsonObject,
        dispatch: suspend (String, String?) -> Unit
    ) {
        val params = url.substringAfter('?', "")
            .split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "")
                if (key.isBlank()) null else key to part.substringAfter('=', "")
            }
            .toMap()
        val id = params["id"] ?: return
        if (params["action"] != "start") {
            jobs.remove(id)?.cancel()
            return
        }
        val timers = card["_timers"]?.jsonArray
            ?: card["card"]?.jsonObject?.get("_timers")?.jsonArray
        val timer = timers
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == id }
            ?: return
        val interval = timer["tick_interval"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(50L) ?: 500L
        val actions = timer["tick_actions"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
        if (actions.isEmpty()) return

        jobs.remove(id)?.cancel()
        jobs[id] = scope.launch {
            while (isActive) {
                delay(interval)
                dispatchAllActions(actions, EngineStateBridge.state.value, emptyMap(), dispatch)
            }
        }
    }

    fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}

private fun divValueFromJson(element: JsonElement): DivVariableValue = when (element) {
    is JsonPrimitive -> when {
        element.booleanOrNull != null -> DivVariableValue.Bool(element.boolean)
        element.longOrNull != null -> DivVariableValue.IntVal(element.long)
        element.doubleOrNull != null -> DivVariableValue.DoubleVal(element.double)
        else -> DivVariableValue.Str(element.content)
    }
    is JsonArray -> DivVariableValue.ArrayVal(element.map { divValueFromJson(it) })
    else -> DivVariableValue.Str(element.toString())
}

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
    val timerController = remember(scope) { DivTimerController(scope) }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    DisposableEffect(timerController) {
        onDispose { timerController.stopAll() }
    }

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
                "array" -> DivVariableValue.ArrayVal(
                    valueEl?.jsonArray?.map { divValueFromJson(it) } ?: emptyList()
                )
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
                    scope = scope,
                    onDispatchAction = { url, payloadStr ->
                        when {
                            url.startsWith("div-action://set_variable") -> handleDivSetVariable(url)
                            url.startsWith("div-action://timer") -> timerController.handle(url, parsedRoot, onAction)
                            else -> onAction(url, payloadStr)
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
    scope: CoroutineScope,
    onDispatchAction: suspend (url: String, payload: String?) -> Unit,
    fillWidth: Boolean = true
) {
    val type = node["type"]?.jsonPrimitive?.contentOrNull ?: "container"
    // DivKit's `visibility` is an expression: `gone` removes the node from the layout, which is
    // how a card hides an empty QR preview or disables a control without inventing a fake state.
    val visibility = node["visibility"]?.jsonPrimitive?.contentOrNull
        ?.let { evalExpr(it, variables, rowContext) }
    if (visibility == "gone") return
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
                            RenderDivNode(
                                childObj, variables, rowContext, isDark, scope, onDispatchAction,
                                fillWidth = false
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().then(boxModifier),
                    verticalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    for (child in items) {
                        RenderDivNode(child.jsonObject, variables, rowContext, isDark, scope, onDispatchAction)
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
                    RenderDivNode(child.jsonObject, variables, rowContext, isDark, scope, onDispatchAction)
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
                                RenderDivNode(child.jsonObject, variables, rowContext, isDark, scope, onDispatchAction)
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
                    scope.launch { dispatchAllActions(actions, variables, rowContext, onDispatchAction) }
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
                    .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(radius.dp))
                    .clickable {
                        scope.launch { dispatchAllActions(actions, variables, rowContext, onDispatchAction) }
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

        "image" -> {
            // Two modes. A `file://`/`content://`/`data:` source is a real bitmap and renders
            // through Coil so tool output (generated QR PNGs, camera captures, processed
            // images) is actually visible. Anything else is treated as a native icon name.
            val iconRef = node["image_url"]?.jsonPrimitive?.contentOrNull
                ?: node["icon"]?.jsonPrimitive?.contentOrNull
                ?: node["custom_props"]?.jsonObject?.get("icon")?.jsonPrimitive?.contentOrNull
            val resolved = iconRef?.let { evalExpr(it, variables, rowContext) }
            if (resolved != null && isBitmapSource(resolved)) {
                RenderDivBitmap(node, resolved, variables, rowContext, boxModifier)
            } else {
                val sizeDp = node["width"]?.jsonObject?.get("value")?.jsonPrimitive?.intOrNull
                    ?: node["height"]?.jsonObject?.get("value")?.jsonPrimitive?.intOrNull
                    ?: node["size"]?.jsonPrimitive?.intOrNull
                    ?: 24
                val tintHex = node["tint"]?.jsonPrimitive?.contentOrNull
                    ?: node["text_color"]?.jsonPrimitive?.contentOrNull
                val tint = tintHex?.let { adaptTextColorForTheme(evalExpr(it, variables, rowContext), isDark) }
                    ?: MaterialTheme.colorScheme.onSurface
                val alignStr = node["alignment_horizontal"]?.jsonPrimitive?.contentOrNull ?: "center"
                val horizontal = when (alignStr) {
                    "left", "start" -> Alignment.CenterStart
                    "right", "end" -> Alignment.CenterEnd
                    else -> Alignment.Center
                }
                Box(
                    modifier = Modifier.fillMaxWidth().then(boxModifier),
                    contentAlignment = horizontal
                ) {
                    Icon(
                        imageVector = resolveIcon(resolved.orEmpty()),
                        contentDescription = node["content_description"]?.jsonPrimitive?.contentOrNull
                            ?: resolved,
                        tint = tint,
                        modifier = Modifier
                            .size(sizeDp.dp)
                            .testTag("div_icon_${resolved?.replace(Regex("[^a-z0-9]+"), "_") ?: "unknown"}")
                    )
                }
            }
        }

        "json_list", "sdui_chart", "chart", "bar_chart", "line_chart", "area_chart", "donut_chart", "pie_chart", "custom" -> {
            val customType = node["custom_type"]?.jsonPrimitive?.contentOrNull ?: type
            if (engine.ui.DivChartParser.isChartCustomType(customType) || engine.ui.DivChartParser.isChartCustomType(type)) {
                val actions = extractActions(node)
                RenderDivChartBlock(
                    node = node,
                    variables = variables,
                    isDark = isDark,
                    modifier = boxModifier,
                    onPointSelected = { pt ->
                        if (actions.isNotEmpty()) {
                            val ptContext = rowContext + mapOf(
                                "label" to pt.label,
                                "value" to pt.value
                            )
                            scope.launch { dispatchAllActions(actions, variables, ptContext, onDispatchAction) }
                        }
                    }
                )
            } else if (customType == "json_list" || type == "json_list") {
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
                                        scope = scope,
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

internal fun adaptSurfaceColorForTheme(hexStr: String, isDark: Boolean): Color {
    if (isDark) return parseHexColor(hexStr)
    return when (hexStr.trim().uppercase()) {
        "#1E293B", "#152033" -> Color(0xFFFFFFFF)
        "#0F172A", "#0B1120" -> Color(0xFFF8FAFC)
        // Tool cards paint their data panels this slate. Leaving it dark while
        // adaptTextColorForTheme maps the captions to light-theme greys renders
        // #475569 on #334155 (~1.5:1) - effectively invisible.
        "#334155" -> Color(0xFFF1F5F9)
        else -> parseHexColor(hexStr)
    }
}

internal fun adaptBorderColorForTheme(hexStr: String, isDark: Boolean): Color {
    if (isDark) return parseHexColor(hexStr)
    return when (hexStr.trim().uppercase()) {
        "#334155", "#1E293B" -> Color(0xFFCBD5E1)
        "#38BDF8" -> Color(0xFF93C5FD)
        else -> parseHexColor(hexStr)
    }
}

internal fun adaptTextColorForTheme(hexStr: String, isDark: Boolean): Color {
    if (isDark) {
        // #64748B is only ever a caption on a slate surface, where it is 3:1.
        return when (hexStr.trim().uppercase()) {
            "#64748B", "#94A3B8" -> Color(0xFFCBD5E1)
            else -> parseHexColor(hexStr)
        }
    }
    return when (hexStr.trim().uppercase()) {
        "#F8FAFC", "#E2E8F0", "#FFFFFF" -> Color(0xFF0F172A)
        "#94A3B8", "#CBD5E1" -> Color(0xFF475569)
        "#64748B" -> Color(0xFF475569)
        "#38BDF8" -> Color(0xFF1D4ED8)
        "#34D399" -> Color(0xFF047857)
        "#FBBF24" -> Color(0xFFB45309)
        "#F87171", "#FCA5A5" -> Color(0xFFB91C1C)
        else -> parseHexColor(hexStr)
    }
}

internal fun adaptButtonBgForTheme(hexStr: String, isDark: Boolean): Color {
    if (isDark) return parseHexColor(hexStr)
    return when (hexStr.trim().uppercase()) {
        "#334155", "#1E293B" -> Color(0xFFF1F5F9)
        "#3F1D24" -> Color(0xFFFEE2E2)
        "#0EA5E9" -> Color(0xFF1D4ED8)
        else -> parseHexColor(hexStr)
    }
}

internal fun adaptButtonTextForTheme(textHex: String, bgHex: String, isDark: Boolean): Color {
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

private suspend fun dispatchAllActions(
    actions: List<JsonObject>,
    variables: Map<String, DivVariableValue>,
    rowContext: Map<String, Any?>,
    onDispatchAction: suspend (url: String, payload: String?) -> Unit
) {
    var current = variables
    for (act in actions) {
        val type = act["type"]?.jsonPrimitive?.contentOrNull
        val variableName = act["variable_name"]?.jsonPrimitive?.contentOrNull
        if (type == "set_variable" && variableName != null) {
            current = current + (variableName to variableValueFromAction(act["value"], current, rowContext))
            EngineStateBridge.pushUpdate(variableName, current.getValue(variableName))
            continue
        }
        if (type != null && type in ARRAY_ACTION_TYPES && variableName != null) {
            val updated = applyArrayAction(type, act, current[variableName], current, rowContext)
            current = current + (variableName to updated)
            EngineStateBridge.pushUpdate(variableName, updated)
            continue
        }
        val rawUrl = act["url"]?.jsonPrimitive?.contentOrNull ?: continue
        val resolvedUrl = evalExpr(rawUrl, current, rowContext)
        val payloadObj = act["payload"]?.jsonObject
        val resolvedPayload = payloadObj?.let { resolveJsonExpressions(it, current, rowContext).toString() }
        onDispatchAction(resolvedUrl, resolvedPayload)
        // Actions run sequentially: a later action may reference a variable an earlier
        // one produced (e.g. an inserted record id), so fold in the bridge's latest state.
        current = current + EngineStateBridge.state.value
    }
}

private val ARRAY_ACTION_TYPES = setOf(
    "array_insert_value",
    "array_remove_value",
    "array_set_value",
    "array_delete_value"
)

private fun applyArrayAction(
    type: String,
    action: JsonObject,
    currentValue: DivVariableValue?,
    variables: Map<String, DivVariableValue>,
    rowContext: Map<String, Any?>
): DivVariableValue {
    val values = (currentValue as? DivVariableValue.ArrayVal)?.values?.toMutableList() ?: mutableListOf()
    fun index(): Int? = action["index"]?.jsonPrimitive?.contentOrNull
        ?.let { evalExpr(it, variables, rowContext) }
        ?.toIntOrNull()

    when (type) {
        "array_insert_value" -> {
            val item = variableValueFromAction(action["value"], variables, rowContext)
            val atStart = action["position"]?.jsonPrimitive?.contentOrNull == "start"
            if (atStart) values.add(0, item) else values.add(item)
        }
        "array_remove_value" -> {
            val target = variableValueFromAction(action["value"], variables, rowContext).asDisplayString()
            values.removeAll { it.asDisplayString() == target }
        }
        "array_set_value" -> {
            val position = index() ?: return DivVariableValue.ArrayVal(values)
            if (position < 0) return DivVariableValue.ArrayVal(values)
            val item = variableValueFromAction(action["value"], variables, rowContext)
            while (values.size <= position) values.add(DivVariableValue.Str(""))
            values[position] = item
        }
        "array_delete_value" -> {
            val position = index() ?: return DivVariableValue.ArrayVal(values)
            if (position in values.indices) values.removeAt(position)
        }
    }
    return DivVariableValue.ArrayVal(values)
}

private fun variableValueFromAction(
    value: JsonElement?,
    variables: Map<String, DivVariableValue>,
    rowContext: Map<String, Any?>
): DivVariableValue {
    val obj = value as? JsonObject
    if (obj == null) {
        return when (value) {
            is JsonPrimitive -> when {
                value.isString -> DivVariableValue.Str(evalExpr(value.content, variables, rowContext))
                value.booleanOrNull != null -> DivVariableValue.Bool(value.boolean)
                value.longOrNull != null -> DivVariableValue.IntVal(value.long)
                value.doubleOrNull != null -> DivVariableValue.DoubleVal(value.double)
                else -> DivVariableValue.Str(value.content)
            }
            is JsonArray -> DivVariableValue.ArrayVal(value.map { actionArrayValue(it, variables, rowContext) })
            else -> DivVariableValue.Str(value?.toString() ?: "")
        }
    }
    val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "string"
    val raw = obj["value"]
    return when (type) {
        "integer" -> DivVariableValue.IntVal(
            evalExpr(raw?.jsonPrimitive?.contentOrNull ?: "0", variables, rowContext).toLongOrNull() ?: 0L
        )
        "number" -> DivVariableValue.DoubleVal(
            evalExpr(raw?.jsonPrimitive?.contentOrNull ?: "0", variables, rowContext).toDoubleOrNull() ?: 0.0
        )
        "boolean" -> DivVariableValue.Bool(
            evalExpr(raw?.jsonPrimitive?.contentOrNull ?: "false", variables, rowContext).toBoolean()
        )
        "array" -> DivVariableValue.ArrayVal(
            raw?.jsonArray?.map { actionArrayValue(it, variables, rowContext) } ?: emptyList()
        )
        else -> DivVariableValue.Str(
            evalExpr(raw?.jsonPrimitive?.contentOrNull ?: "", variables, rowContext)
        )
    }
}

private fun actionArrayValue(
    element: JsonElement,
    variables: Map<String, DivVariableValue>,
    rowContext: Map<String, Any?>
): DivVariableValue = when (element) {
    is JsonPrimitive ->
        if (element.isString) DivVariableValue.Str(evalExpr(element.content, variables, rowContext))
        else divValueFromJson(element)
    is JsonArray -> DivVariableValue.ArrayVal(element.map { actionArrayValue(it, variables, rowContext) })
    else -> divValueFromJson(element)
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
                if (raw.startsWith("@{") && raw.endsWith("}") && !raw.drop(2).dropLast(1).contains("@{")) {
                    val expressionValue = evaluateDivExpression(raw.drop(2).dropLast(1), variables, rowContext)
                    if (expressionValue is List<*>) {
                        return JsonArray(expressionValue.map { expressionValueToJson(it) })
                    }
                }
                val evaluated = evalExpr(raw, variables, rowContext)
                if (raw.startsWith("@{") && raw.endsWith("}") && !raw.drop(2).dropLast(1).contains("@{")) {
                    // Only coerce short, unambiguous literals. A 60-digit Signal safety number
                    // parses as a Double, which rounds it and turns a match into a mismatch.
                    if (isSafeNumericLiteral(evaluated)) {
                        evaluated.toLongOrNull()?.let { return JsonPrimitive(it) }
                        evaluated.toDoubleOrNull()?.let { return JsonPrimitive(it) }
                    }
                    if (evaluated == "true" || evaluated == "false") return JsonPrimitive(evaluated.toBoolean())
                }
                JsonPrimitive(evaluated)
            } else el
        }
        is JsonArray -> JsonArray(el.map { resolveJsonExpressions(it, variables, rowContext) })
        is JsonObject -> JsonObject(el.mapValues { (_, v) -> resolveJsonExpressions(v, variables, rowContext) })
    }
}

private fun expressionValueToJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is List<*> -> JsonArray(value.map { expressionValueToJson(it) })
    is Map<*, *> -> JsonObject(
        value.entries.associate { (k, v) -> k.toString() to expressionValueToJson(v) }
    )
    else -> JsonPrimitive(value.toString())
}

/**
 * Numeric action arguments are convenience, not semantics. Coercing a long digit string
 * (a safety number, an account id, a barcode) to Double loses digits, so only short
 * canonical literals are converted.
 */
private fun isSafeNumericLiteral(value: String): Boolean {
    if (value.isEmpty() || value.length > 15) return false
    if (value.length > 1 && value[0] == '0' && value[1].isDigit()) return false
    return value.toLongOrNull() != null || value.toDoubleOrNull() != null
}

private val EXPR_REGEX = Regex("@\\{([^}]+)\\}")

internal fun evalExpr(
    input: String,
    variables: Map<String, DivVariableValue>,
    rowContext: Map<String, Any?>
): String {
    if (!input.contains("@{")) return input
    return EXPR_REGEX.replace(input) { match ->
        divExpressionDisplay(evaluateDivExpression(match.groupValues[1], variables, rowContext))
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

/**
 * Resolves a DivKit `icon` name to a vector.
 *
 * Resolution goes through the bundled native POS catalog ([NativeIconCatalog]), which
 * understands `icons://native/<name>`, `icons://<name>`, canonical names, aliases, and
 * Material identifiers. Unknown names fall back to [Icons.Default.FlashOn] so a card
 * always renders something.
 */
private fun resolveIcon(name: String): ImageVector {
    // Cards may carry either a bare name or the full `icons://native/<name>` scheme.
    val bare = name.removePrefix("icons://native/").removePrefix("icons://")
    NativeIconCatalog.find(bare)
        ?.let { NativeIconVectors.resolve(it.material) }
        ?.let { return it }
    return Icons.Default.FlashOn
}
