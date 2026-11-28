package com.yandex.divkit.multiplatform

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import engine.ui.SduiLayoutBlocks
import engine.ui.flag
import engine.ui.items
import engine.ui.number
import engine.ui.text
import androidx.compose.ui.unit.sp
import engine.icons.NativeIconCatalog
import engine.icons.NativeIconVectors
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import engine.state.WorkspaceVariableScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    is JsonObject -> DivVariableValue.DictVal(element.mapValues { divValueFromJson(it.value) })
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
            if (!WorkspaceVariableScope.isValid(name) || name in current) continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "string"
            val valueEl = obj["value"]
            val dv = when (type) {
                "integer" -> DivVariableValue.IntVal(valueEl?.jsonPrimitive?.longOrNull ?: 0L)
                "number" -> DivVariableValue.DoubleVal(valueEl?.jsonPrimitive?.doubleOrNull ?: 0.0)
                "boolean" -> DivVariableValue.Bool(valueEl?.jsonPrimitive?.booleanOrNull ?: false)
                "array" -> DivVariableValue.ArrayVal(
                    valueEl?.jsonArray?.map { divValueFromJson(it) } ?: emptyList()
                )
                "dict", "object" -> DivVariableValue.DictVal(
                    valueEl?.jsonObject?.mapValues { divValueFromJson(it.value) } ?: emptyMap()
                )
                else -> {
                    when (valueEl) {
                        is JsonPrimitive -> DivVariableValue.Str(valueEl.contentOrNull ?: "")
                        is JsonObject -> DivVariableValue.DictVal(valueEl.mapValues { divValueFromJson(it.value) })
                        is JsonArray -> DivVariableValue.ArrayVal(valueEl.map { divValueFromJson(it) })
                        else -> DivVariableValue.Str(valueEl?.toString() ?: "")
                    }
                }
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
    // Only card-declared variables are writable: the workspace owns the vocabulary.
    if (name.contains('.')) {
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
    fillWidth: Boolean = true,
    /** Nearest declared fill, so light-theme text is only darkened on a surface that was lightened. */
    surfaceHex: String? = null
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
    // A fully transparent fill is not a surface: inheriting it turns the light-theme text
    // adaptation into white-on-transparent (inactive chip labels over a light track).
    val resolvedSurface = bgHex
        ?.let { evalExpr(it, variables, rowContext) }
        ?.takeIf { !isTransparentFill(it) }
        ?: surfaceHex
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

    node["alpha"]?.jsonPrimitive?.contentOrNull?.let { raw ->
        evalExpr(raw, variables, rowContext).toFloatOrNull()?.let { boxModifier = boxModifier.alpha(it) }
    }

    // Layout nodes carry actions too (a hub row or card is a `container`). Attaching the click
    // here keeps every node type dispatchable; `text` wires its own to avoid double dispatch.
    val nodeActions = extractActions(node)
    if (nodeActions.isNotEmpty() && type != "text") {
        boxModifier = boxModifier.clickable {
            scope.launch { dispatchAllActions(nodeActions, variables, rowContext, onDispatchAction) }
        }
    }

    when (type) {
        "state" -> {
            val states = node["states"]?.jsonArray ?: JsonArray(emptyList())
            val varName = node["state_id_variable"]?.jsonPrimitive?.contentOrNull
            val varValue = varName?.let { name -> (variables[name] as? JsonPrimitive)?.contentOrNull }
            val defaultId = node["default_state_id"]?.jsonPrimitive?.contentOrNull
            val active = states.firstOrNull { state ->
                val id = state.jsonObject["state_id"]?.jsonPrimitive?.contentOrNull
                id == (varValue ?: defaultId)
            } ?: states.firstOrNull()
            val activeDiv = active?.jsonObject?.get("div")?.jsonObject
            if (activeDiv != null) {
                Box(modifier = divSizeModifier(node, fillWidth).then(boxModifier)) {
                    RenderDivNode(
                        node = activeDiv,
                        variables = variables,
                        rowContext = rowContext,
                        isDark = isDark,
                        scope = scope,
                        onDispatchAction = onDispatchAction,
                        fillWidth = fillWidth,
                        surfaceHex = resolvedSurface
                    )
                }
            }
        }
        "sticky_footer", "segmented", "bottom_tabs", "progress_ring", "text_field",
        "scan_chips", "qr_controls", "fab", "toast", "form_modal", "bottom_sheet",
        "camera_viewfinder", "inline_banner" -> {
            RenderLayoutBlock(
                type = type,
                node = node,
                variables = variables,
                rowContext = rowContext,
                isDark = isDark,
                scope = scope,
                onDispatchAction = onDispatchAction,
                modifier = boxModifier
            )
        }
        "container" -> {
            val orientation = node["orientation"]?.jsonPrimitive?.contentOrNull ?: "vertical"
            val items = node["items"]?.jsonArray ?: JsonArray(emptyList())
            val spacing = (node["item_spacing"]?.jsonPrimitive?.intOrNull ?: 8).dp
            val sizeMod = divSizeModifier(node, fillWidth).then(boxModifier)
            if (orientation == "horizontal") {
                Row(
                    modifier = sizeMod,
                    horizontalArrangement = Arrangement.spacedBy(spacing, divHAlign(node)),
                    verticalAlignment = divVAlign(node)
                ) {
                    for (child in items) {
                        val childObj = child.jsonObject
                        val weight = childObj.divWeight("width")
                        Box(modifier = if (weight != null && weight > 0f) Modifier.weight(weight) else Modifier) {
                            RenderDivNode(
                                childObj, variables, rowContext, isDark, scope, onDispatchAction,
                                fillWidth = false, surfaceHex = resolvedSurface
                            )
                        }
                    }
                }
            } else {
                // Vertical weights only resolve against a bounded height. Cards are hosted in a
                // scrolling column, so a `match_parent`-height page would collapse its weighted
                // children to zero; only a fixed-height container can distribute weight.
                val distributesHeight =
                    node["height"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull == "fixed"
                Column(
                    modifier = sizeMod,
                    verticalArrangement = Arrangement.spacedBy(spacing, divVAlign(node)),
                    horizontalAlignment = divHAlign(node)
                ) {
                    for (child in items) {
                        val childObj = child.jsonObject
                        val weight = childObj.divWeight("height")?.takeIf { distributesHeight }
                        Box(modifier = if (weight != null && weight > 0f) Modifier.weight(weight) else Modifier) {
                            RenderDivNode(
                                childObj, variables, rowContext, isDark, scope, onDispatchAction,
                                // A child only spans the parent when the parent itself spans:
                                // inside a hugging container a full-width child would stretch the
                                // container to the whole row (a status pill became a full bar).
                                fillWidth = fillWidth, surfaceHex = resolvedSurface
                            )
                        }
                    }
                }
            }
        }

        "gallery" -> {
            val items = node["items"]?.jsonArray ?: JsonArray(emptyList())
            val spacing = (node["item_spacing"]?.jsonPrimitive?.intOrNull ?: 10).dp
            val vertical = node["orientation"]?.jsonPrimitive?.contentOrNull == "vertical"
            // Gallery children are full pages: inside a scroller the width constraint is
            // unbounded, so `match_parent` would fall back to content width and two pages would
            // share the screen. Pin each child to the gallery's own width instead.
            BoxWithConstraints(modifier = divSizeModifier(node, fillWidth).then(boxModifier)) {
                val pageWidth = maxWidth
                if (vertical) {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                        for (child in items) {
                            Box(modifier = Modifier.width(pageWidth)) {
                                RenderDivNode(
                                    child.jsonObject, variables, rowContext, isDark, scope,
                                    onDispatchAction, surfaceHex = resolvedSurface
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(spacing)
                    ) {
                        for (child in items) {
                            Box(modifier = Modifier.width(pageWidth)) {
                                RenderDivNode(
                                    child.jsonObject, variables, rowContext, isDark, scope,
                                    onDispatchAction, surfaceHex = resolvedSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        "grid" -> {
            val columnCount = (node["column_count"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(1, 4)
            val items = node["items"]?.jsonArray ?: JsonArray(emptyList())
            val spacing = (node["item_spacing"]?.jsonPrimitive?.intOrNull ?: 10).dp
            val rows = items.chunked(columnCount)
            Column(
                modifier = divSizeModifier(node, fillWidth).then(boxModifier),
                verticalArrangement = Arrangement.spacedBy(spacing)
            ) {
                for (rowItems in rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing)
                    ) {
                        for (child in rowItems) {
                            Box(modifier = Modifier.weight(1f)) {
                                RenderDivNode(
                                    child.jsonObject, variables, rowContext, isDark, scope, onDispatchAction,
                                    surfaceHex = resolvedSurface
                                )
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
            val colorHex = evalExpr(
                node["text_color"]?.jsonPrimitive?.contentOrNull ?: "#F8FAFC",
                variables,
                rowContext
            )
            val alignStr = node["text_alignment_horizontal"]?.jsonPrimitive?.contentOrNull
                ?: node["alignment_horizontal"]?.jsonPrimitive?.contentOrNull ?: "left"
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

            val tabular = node["font_feature_settings"]?.jsonPrimitive?.contentOrNull
                ?.contains("tnum") == true
            Text(
                text = resolvedText,
                fontSize = fontSize,
                lineHeight = node["line_height"]?.jsonPrimitive?.intOrNull?.sp ?: TextUnit.Unspecified,
                style = if (tabular) TextStyle(fontFeatureSettings = "tnum") else LocalTextStyle.current,
                fontWeight = fontWeight,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                color = adaptTextColorForTheme(
                    evalExpr(colorHex, variables, rowContext), isDark, resolvedSurface
                ),
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
                val textAlignment = node["text_alignment_horizontal"]
                    ?.jsonPrimitive?.contentOrNull
                val alignStart = textAlignment == "left" || textAlignment == "start"
                Row(
                    modifier = Modifier
                        .then(if (alignStart) Modifier.fillMaxWidth() else Modifier)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement =
                        if (alignStart) Arrangement.Start else Arrangement.Center
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
                    Column(
                        horizontalAlignment =
                            if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
                        modifier = if (alignStart) Modifier.weight(1f) else Modifier
                    ) {
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
            val inputFontSize = (node["font_size"]?.jsonPrimitive?.intOrNull ?: 14).sp
            val inputTextColor = node["text_color"]?.jsonPrimitive?.contentOrNull
                ?.let { adaptTextColorForTheme(evalExpr(it, variables, rowContext), isDark) }
            val inputHintColor = (node["hint_color"] ?: node["placeholder_color"])
                ?.jsonPrimitive?.contentOrNull
                ?.let { adaptTextColorForTheme(evalExpr(it, variables, rowContext), isDark) }
            val inputKeyboard = node["keyboard_type"]?.jsonPrimitive?.contentOrNull
            val inputMultiLine = inputKeyboard == "multi_line_text"
            val maxVisibleLines = node["max_visible_lines"]?.jsonPrimitive?.intOrNull
            val focusNode = node["focus"]?.jsonObject
            val onFocusActions = focusNode?.get("on_focus")?.jsonArray?.mapNotNull { it as? JsonObject }
                ?: emptyList()
            val onBlurActions = focusNode?.get("on_blur")?.jsonArray?.mapNotNull { it as? JsonObject }
                ?: emptyList()
            val onChangeActions = node["on_change"]?.jsonArray?.mapNotNull { it as? JsonObject }
                ?: emptyList()
            var hadFocus by remember { mutableStateOf(false) }
            // The card draws the field's frame, background and prefix, so the entry surface must
            // not add a second outline (nor a floating label that eats the value on a 36dp row).
            Box(
                modifier = divSizeModifier(node, fillWidth).then(boxModifier),
                contentAlignment = Alignment.CenterStart
            ) {
                if (currentVal.isEmpty() && hint.isNotBlank()) {
                    Text(
                        text = hint,
                        fontSize = inputFontSize,
                        color = inputHintColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = currentVal,
                    onValueChange = { newValue ->
                        if (WorkspaceVariableScope.isValid(varName)) {
                            EngineStateBridge.pushUpdate(varName, DivVariableValue.Str(newValue))
                        }
                        if (onChangeActions.isNotEmpty()) {
                            val withEdit = variables + (varName to DivVariableValue.Str(newValue))
                            scope.launch { dispatchAllActions(onChangeActions, withEdit, rowContext, onDispatchAction) }
                        }
                    },
                    singleLine = !inputMultiLine,
                    maxLines = if (inputMultiLine) (maxVisibleLines ?: 4) else 1,
                    textStyle = TextStyle(
                        fontSize = inputFontSize,
                        color = inputTextColor ?: MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (inputKeyboard == "number") KeyboardType.Number else KeyboardType.Text
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            val actions = if (state.isFocused) onFocusActions else if (hadFocus) onBlurActions else emptyList()
                            hadFocus = state.isFocused
                            if (actions.isNotEmpty()) {
                                scope.launch { dispatchAllActions(actions, variables, rowContext, onDispatchAction) }
                            }
                        }
                        .testTag("div_input_${varName.replace('.', '_')}")
                )
            }
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
            if (resolved.isNullOrBlank()) {
                // An image whose source has not been produced yet (a QR before generation)
                // must render nothing: the icon fallback used to paint a lightning bolt
                // inside the empty placeholder.
            } else if (isBitmapSource(resolved)) {
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
                    modifier = divSizeModifier(node, fillWidth).then(boxModifier),
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
                // A blank `empty_text` means the card designs its own empty state.
                val emptyText = props["empty_text"]?.jsonPrimitive?.contentOrNull ?: "No records"
                val rowCard = props["row_card"]?.jsonObject

                val itemsList = remember(variables) {
                    parseJsonListVariable(resolveVariable(variables, dataVar))
                }

                Column(
                    modifier = divSizeModifier(node, fillWidth)
                        .then(boxModifier)
                        .testTag("div_json_list_${dataVar.replace('.', '_')}")
                ) {
                    if (itemsList.isEmpty() && emptyText.isNotBlank()) {
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
                        val rowSpacing = (node["item_spacing"]?.jsonPrimitive?.intOrNull ?: 8).dp
                        Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
                            itemsList.forEachIndexed { idx, itemMap ->
                                key(itemMap["id"] ?: idx) {
                                    RenderDivNode(
                                        node = rowCard,
                                        variables = variables,
                                        rowContext = itemMap,
                                        isDark = isDark,
                                        scope = scope,
                                        onDispatchAction = onDispatchAction,
                                        fillWidth = fillWidth
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (customType == "sdui_nav") {
                val props = node["custom_props"]?.jsonObject ?: node
                val pages = props["cards"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()
                val pageById = pages.associateBy { it["id"]?.jsonPrimitive?.contentOrNull ?: "" }
                val initialId = props["initial"]?.jsonPrimitive?.contentOrNull
                    ?: pageById.keys.firstOrNull().orEmpty()
                var stack by remember(initialId) { mutableStateOf(listOf(initialId)) }
                val activeId = stack.lastOrNull() ?: initialId
                val activeDiv = pageById[activeId]?.get("div")?.jsonObject
                val navDispatch: suspend (String, String?) -> Unit = { url, payload ->
                    val targetId = url.substringAfter("id=", "").substringBefore('&')
                    val handled = when {
                        url.startsWith("nav://push?id=") && pageById.containsKey(targetId) -> {
                            stack = stack + targetId
                            true
                        }
                        url == "nav://pop" -> {
                            if (stack.size > 1) stack = stack.dropLast(1)
                            true
                        }
                        url.startsWith("nav://replace?id=") && pageById.containsKey(targetId) -> {
                            stack = stack.dropLast(1) + targetId
                            true
                        }
                        url.startsWith("nav://reset?id=") && pageById.containsKey(targetId) -> {
                            stack = listOf(targetId)
                            true
                        }
                        else -> false
                    }
                    if (!handled) onDispatchAction(url, payload)
                }
                if (activeDiv != null) {
                    Box(modifier = Modifier.fillMaxWidth().then(boxModifier)) {
                        RenderDivNode(
                            node = activeDiv,
                            variables = variables,
                            rowContext = rowContext,
                            isDark = isDark,
                            scope = scope,
                            onDispatchAction = navDispatch,
                            fillWidth = fillWidth,
                            surfaceHex = resolvedSurface
                        )
                    }
                }
            }
        }
    }
}

/** True for `#00000000`-style fills, which paint nothing and must not set the surface tone. */
internal fun isTransparentFill(hex: String): Boolean {
    val clean = hex.trim().removePrefix("#")
    return clean.length == 8 && clean.startsWith("00")
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

internal fun adaptTextColorForTheme(hexStr: String, isDark: Boolean, surfaceHex: String? = null): Color {
    val declared = parseHexColor(hexStr)
    if (!isDark && surfaceHex != null && adaptSurfaceColorForTheme(surfaceHex, false).luminance() < 0.5f) {
        // The panel keeps its dark fill in light theme (e.g. the Kiosk catalog rows), so flipping a
        // light caption to ink paints dark-on-dark. Keep light captions light and lift the greys.
        return when (hexStr.trim().uppercase()) {
            "#64748B", "#94A3B8", "#475569" -> Color(0xFFCBD5E1)
            else -> if (declared.luminance() < 0.35f) Color(0xFFF8FAFC) else declared
        }
    }
    if (isDark) {
        // #64748B is only ever a caption on a slate surface, where it is 3:1.
        return when (hexStr.trim().uppercase()) {
            "#64748B", "#94A3B8" -> Color(0xFFCBD5E1)
            else -> parseHexColor(hexStr)
        }
    }
    val mapped = when (hexStr.trim().uppercase()) {
        "#F8FAFC", "#E2E8F0", "#FFFFFF" -> Color(0xFF0F172A)
        "#94A3B8", "#CBD5E1", "#64748B" -> Color(0xFF475569)
        "#38BDF8" -> Color(0xFF1D4ED8)
        "#34D399" -> Color(0xFF047857)
        "#FBBF24" -> Color(0xFFB45309)
        "#F87171", "#FCA5A5" -> Color(0xFFB91C1C)
        else -> null
    }
    if (mapped != null) return mapped
    // Pastel accents (#C4B5FD totals, #67E8F9 telemetry) are authored for a dark panel. On a light
    // surface they wash out to ~1.5:1, so keep the hue and drop the tone instead.
    return if (declared.luminance() > 0.5f) {
        Color(declared.red * 0.25f, declared.green * 0.25f, declared.blue * 0.25f)
    } else {
        declared
    }
}

internal fun adaptButtonBgForTheme(hexStr: String, isDark: Boolean): Color {
    if (isDark) return parseHexColor(hexStr)
    return when (hexStr.trim().uppercase()) {
        "#334155", "#1E293B" -> Color(0xFFF1F5F9)
        "#3F1D24" -> Color(0xFFFEE2E2)
        // Mid-tone brand fills only reach ~3.2-4.2:1 against a white label. The light theme drops
        // each one a step darker so the same white label clears 4.5:1.
        "#0EA5E9" -> Color(0xFF1D4ED8)
        "#0284C7" -> Color(0xFF0369A1)
        "#0891B2" -> Color(0xFF0E7490)
        "#0D9488" -> Color(0xFF0F766E)
        "#059669" -> Color(0xFF047857)
        "#D97706" -> Color(0xFFB45309)
        "#8B5CF6" -> Color(0xFF6D28D9)
        "#DB2777" -> Color(0xFFBE123C)
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

private fun resolveVariable(variables: Map<String, DivVariableValue>, name: String): DivVariableValue? {
    variables[name]?.let { return it }
    var dot = name.lastIndexOf('.')
    while (dot > 0) {
        val base = variables[name.substring(0, dot)]
        if (base is DivVariableValue.DictVal) {
            var value: DivVariableValue? = base
            for (part in name.substring(dot + 1).split('.')) {
                value = (value as? DivVariableValue.DictVal)?.values?.get(part)
            }
            return value
        }
        dot = name.lastIndexOf('.', dot - 1)
    }
    return null
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

/** DivKit width/height sizing: `fixed` sets the box, `match_parent` fills, `wrap_content` hugs content. */
private fun divSizeModifier(node: JsonObject, fillWidth: Boolean): Modifier {
    var m: Modifier = Modifier
    val width = node["width"]?.jsonObject
    when (width?.get("type")?.jsonPrimitive?.contentOrNull) {
        "fixed" -> width["value"]?.jsonPrimitive?.intOrNull?.let { m = m.width(it.dp) }
        "match_parent" -> m = m.fillMaxWidth()
        null -> if (fillWidth) m = m.fillMaxWidth()
        else -> Unit
    }
    val height = node["height"]?.jsonObject
    when (height?.get("type")?.jsonPrimitive?.contentOrNull) {
        "fixed" -> height["value"]?.jsonPrimitive?.intOrNull?.let { m = m.height(it.dp) }
        // `match_parent` height is intentionally ignored: cards are hosted in a scrolling
        // column, and filling an unbounded max height crashes the scroll modifier's measurement.
        else -> Unit
    }
    return m
}

/** DivKit weights live on the size object (`"width": {"type": "match_parent", "weight": 1}`). */
private fun JsonObject.divWeight(axis: String): Float? =
    this[axis]?.jsonObject?.get("weight")?.jsonPrimitive?.floatOrNull

private fun divHAlign(node: JsonObject): Alignment.Horizontal =
    when (node["content_alignment_horizontal"]?.jsonPrimitive?.contentOrNull
        ?: node["alignment_horizontal"]?.jsonPrimitive?.contentOrNull) {
        "center" -> Alignment.CenterHorizontally
        "right", "end" -> Alignment.End
        else -> Alignment.Start
    }

private fun divVAlign(node: JsonObject): Alignment.Vertical =
    when (node["content_alignment_vertical"]?.jsonPrimitive?.contentOrNull
        ?: node["alignment_vertical"]?.jsonPrimitive?.contentOrNull) {
        "center" -> Alignment.CenterVertically
        "bottom", "end" -> Alignment.Bottom
        else -> Alignment.Top
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
    // A chain that dismisses the surface it was launched from (a sheet's Delete → pop → pop)
    // must finish: disposal of that composition would otherwise cancel the remaining actions.
    withContext(NonCancellable) {
        runActionChain(actions, variables, rowContext, onDispatchAction)
    }
}

private suspend fun runActionChain(
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
            is JsonObject -> DivVariableValue.DictVal(value.mapValues { actionArrayValue(it.value, variables, rowContext) })
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
        "dict", "object" -> DivVariableValue.DictVal(
            raw?.jsonObject?.mapValues { actionArrayValue(it.value, variables, rowContext) } ?: emptyMap()
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
    is JsonObject -> DivVariableValue.DictVal(element.mapValues { actionArrayValue(it.value, variables, rowContext) })
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

/**
 * Renders the neutral layout blocks. Everything workspace-specific arrives as copy, values
 * and action payloads in the card, so a new workspace needs no Kotlin.
 */
@Composable
private fun RenderLayoutBlock(
    type: String,
    node: JsonObject,
    variables: Map<String, DivVariableValue>,
    rowContext: Map<String, Any?>,
    isDark: Boolean,
    scope: CoroutineScope,
    onDispatchAction: suspend (String, String?) -> Unit,
    modifier: Modifier
) {
    val props = SduiLayoutBlocks.props(node)
    fun text(key: String, fallback: String = "") =
        evalExpr(props[key]?.jsonPrimitive?.contentOrNull ?: fallback, variables, rowContext)
    fun children(key: String) = props.items(key)
    fun run(actions: List<JsonObject>) {
        if (actions.isNotEmpty()) scope.launch { dispatchAllActions(actions, variables, rowContext, onDispatchAction) }
    }
    fun actionsOf(obj: JsonObject?) = obj?.let { extractActions(it) } ?: emptyList()
    fun number(key: String) = text(key, "0").toDoubleOrNull() ?: 0.0
    fun flag(key: String) = text(key, "false").toBooleanStrictOrNull() ?: false
    val chunks = remember(variables[text("chunks_var", "chunks")]) {
        parseJsonListVariable(variables[text("chunks_var", "chunks")]).map { it["chunk"]?.toString().orEmpty() }
    }

    when (type) {
        "sticky_footer" -> SduiLayoutBlocks.StickyFooter(
            body = {
                Column(Modifier.padding(horizontal = SduiLayoutBlocks.gap(SduiLayoutBlocks.GUTTER / SduiLayoutBlocks.STEP))) {
                    Column(verticalArrangement = Arrangement.spacedBy(SduiLayoutBlocks.gap(3))) {
                        children("body").forEach { child ->
                            RenderDivNode(child, variables, rowContext, isDark, scope, onDispatchAction)
                        }
                    }
                }
            },
            footer = {
                Column(verticalArrangement = Arrangement.spacedBy(SduiLayoutBlocks.gap(2))) {
                    children("footer").forEach { child ->
                        RenderDivNode(child, variables, rowContext, isDark, scope, onDispatchAction)
                    }
                }
            },
            modifier = modifier
        )

        "segmented" -> SduiLayoutBlocks.Segmented(
            items = props.items(),
            activeValue = text("active_value"),
            modifier = modifier,
            onSelect = { item -> run(actionsOf(item)) }
        )

        "bottom_tabs" -> SduiLayoutBlocks.BottomTabs(
            items = props.items(),
            activeValue = text("active_value"),
            modifier = modifier,
            onSelect = { item -> run(actionsOf(item)) }
        )

        "progress_ring" -> SduiLayoutBlocks.Ring(
            fraction = number("value"),
            label = text("label"),
            caption = text("caption"),
            modifier = modifier
        )

        "text_field" -> SduiLayoutBlocks.TextFieldBlock(
            label = text("label"),
            variable = text("variable"),
            placeholder = text("placeholder"),
            modifier = modifier
        )

        "scan_chips" -> SduiLayoutBlocks.ScanChips(
            chunks = chunks,
            expected = number("expected").toInt(),
            modifier = modifier
        )

        "qr_controls" -> SduiLayoutBlocks.QrControls(
            index = number("index").toInt(),
            count = number("count").toInt(),
            modifier = modifier,
            onPrev = { run(actionsOf(props["prev"] as? JsonObject)) },
            onNext = { run(actionsOf(props["next"] as? JsonObject)) }
        )

        "fab" -> SduiLayoutBlocks.FloatingAction(
            label = text("label"),
            modifier = modifier,
            onClick = { run(actionsOf(props)) }
        )

        "toast" -> SduiLayoutBlocks.Toast(text("text"), modifier)

        "form_modal", "bottom_sheet" -> {
            val title = text("title")
            val body: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(SduiLayoutBlocks.gap(3))) {
                    children("body").forEach { child ->
                        RenderDivNode(child, variables, rowContext, isDark, scope, onDispatchAction)
                    }
                }
            }
            if (type == "form_modal") {
                SduiLayoutBlocks.Modal(
                    visible = flag("visible"),
                    title = title,
                    body = body,
                    primaryLabel = text("primary_label").ifBlank { null },
                    onPrimary = { run(actionsOf(props["primary"] as? JsonObject)) },
                    secondaryLabel = text("secondary_label").ifBlank { null },
                    onSecondary = { run(actionsOf(props["secondary"] as? JsonObject)) }
                )
            } else {
                SduiLayoutBlocks.SheetShell(
                    visible = flag("visible"),
                    title = title,
                    body = body,
                    onDismiss = { run(actionsOf(props["dismiss"] as? JsonObject)) }
                )
            }
        }

        "camera_viewfinder" -> SduiLayoutBlocks.Viewfinder(text("hint"), modifier)

        "inline_banner" -> SduiLayoutBlocks.Banner(text("text"), text("tone", "info"), modifier)
    }
}
