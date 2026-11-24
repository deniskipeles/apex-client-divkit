package engine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import engine.state.WorkspaceVariableScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Neutral layout blocks used by workspace cards.
 *
 * Each block is geometry and tone only: copy, values and actions come from the card, so one
 * block serves any workspace and the app stays a generic interpreter.
 */
object SduiLayoutBlocks {

    const val GUTTER = 20
    const val STEP = 4

    fun gap(steps: Int) = (steps * STEP).dp

    fun props(node: JsonObject): JsonObject = node["custom_props"]?.jsonObject ?: node


    @Composable
    fun Label(text: String, modifier: Modifier = Modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
    }

    @Composable
    fun Title(text: String, modifier: Modifier = Modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier
        )
    }

    @Composable
    fun Banner(text: String, tone: String, modifier: Modifier = Modifier) {
        if (text.isBlank()) return
        val scheme = MaterialTheme.colorScheme
        val background: Color
        val foreground: Color
        when (tone.lowercase()) {
            "success" -> { background = scheme.secondaryContainer; foreground = scheme.onSecondaryContainer }
            "warn", "warning" -> { background = scheme.tertiaryContainer; foreground = scheme.onTertiaryContainer }
            "error", "danger" -> { background = scheme.errorContainer; foreground = scheme.onErrorContainer }
            else -> { background = scheme.surfaceVariant; foreground = scheme.onSurfaceVariant }
        }
        Surface(color = background, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = foreground,
                modifier = Modifier.padding(horizontal = gap(4), vertical = gap(3))
            )
        }
    }

    @Composable
    fun Ring(fraction: Double, label: String, caption: String, modifier: Modifier = Modifier) {
        val clamped = fraction.coerceIn(0.0, 1.0)
        val track = MaterialTheme.colorScheme.surfaceVariant
        val accent = MaterialTheme.colorScheme.primary
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(56.dp)) {
                    val stroke = Stroke(width = 6.dp.toPx())
                    drawArc(track, -90f, 360f, false, style = stroke)
                    drawArc(accent, -90f, (360 * clamped).toFloat(), false, style = stroke)
                }
                Text(
                    text = "${(clamped * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.width(gap(4)))
            Column {
                Title(label)
                if (caption.isNotBlank()) Label(caption)
            }
        }
    }

    @Composable
    fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
        val scheme = MaterialTheme.colorScheme
        Surface(
            color = if (active) scheme.primary else scheme.surfaceVariant,
            shape = RoundedCornerShape(50),
            modifier = Modifier.clickable(onClick = onClick)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = gap(4), vertical = gap(2))
            )
        }
    }

    @Composable
    fun Segmented(
        items: List<JsonObject>,
        activeValue: String,
        modifier: Modifier = Modifier,
        onSelect: (JsonObject) -> Unit
    ) {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap(2))) {
            items.forEach { item ->
                val value = item.text("value")
                Chip(item.text("label", value), activeValue == value) { onSelect(item) }
            }
        }
    }

    @Composable
    fun BottomTabs(
        items: List<JsonObject>,
        activeValue: String,
        modifier: Modifier = Modifier,
        onSelect: (JsonObject) -> Unit
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = gap(2)),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                items.forEach { item ->
                    val value = item.text("value")
                    val active = value == activeValue
                    TextButton(onClick = { onSelect(item) }, modifier = Modifier.height(48.dp)) {
                        Text(
                            text = item.text("label", value),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun StickyFooter(body: @Composable () -> Unit, footer: @Composable () -> Unit, modifier: Modifier = Modifier) {
        Column(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) { body() }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.padding(horizontal = gap(GUTTER / STEP), vertical = gap(3))) { footer() }
            }
        }
    }

    @Composable
    fun TextFieldBlock(
        label: String,
        variable: String,
        placeholder: String,
        modifier: Modifier = Modifier
    ) {
        val current = EngineStateBridge.getVariable(variable)?.asDisplayString().orEmpty()
        var value by remember(variable) { mutableStateOf(current) }
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                value = next
                if (WorkspaceVariableScope.isValid(variable)) {
                    EngineStateBridge.pushUpdate(variable, DivVariableValue.Str(next))
                }
            },
            label = { Text(label) },
            placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = modifier.fillMaxWidth()
        )
    }

    @Composable
    fun ScanChips(chunks: List<String>, expected: Int, modifier: Modifier = Modifier) {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap(2))) {
            val total = if (expected > 0) expected else maxOf(chunks.size, 1)
            repeat(total) { index ->
                val filled = index < chunks.size
                Surface(
                    color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                ) {}
            }
        }
    }

    @Composable
    fun QrControls(
        index: Int,
        count: Int,
        modifier: Modifier = Modifier,
        onPrev: () -> Unit,
        onNext: () -> Unit
    ) {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap(2))
        ) {
            TextButton(onClick = onPrev, enabled = index > 1, modifier = Modifier.height(48.dp)) { Text("Previous") }
            Text(
                text = "${index.coerceAtLeast(1)} of ${count.coerceAtLeast(1)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            TextButton(onClick = onNext, enabled = index < count, modifier = Modifier.height(48.dp)) { Text("Next") }
        }
    }

    @Composable
    fun FloatingAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            modifier = modifier.height(48.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }
    }

    @Composable
    fun Modal(
        visible: Boolean,
        title: String,
        body: @Composable () -> Unit,
        primaryLabel: String?,
        onPrimary: (() -> Unit)?,
        secondaryLabel: String?,
        onSecondary: (() -> Unit)?
    ) {
        if (!visible) return
        Dialog(onDismissRequest = { onSecondary?.invoke() }) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(gap(GUTTER / STEP))) {
                    Title(title)
                    Spacer(Modifier.height(gap(4)))
                    body()
                    Spacer(Modifier.height(gap(4)))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (secondaryLabel != null) {
                            TextButton(onClick = { onSecondary?.invoke() }, modifier = Modifier.height(48.dp)) {
                                Text(secondaryLabel)
                            }
                        }
                        if (primaryLabel != null) {
                            Button(
                                onClick = { onPrimary?.invoke() },
                                modifier = Modifier.height(48.dp)
                            ) { Text(primaryLabel) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun SheetShell(visible: Boolean, title: String, body: @Composable () -> Unit, onDismiss: () -> Unit) {
        if (!visible) return
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.padding(gap(GUTTER / STEP))) {
                    Title(title)
                    Spacer(Modifier.height(gap(4)))
                    body()
                    Spacer(Modifier.height(gap(4)))
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
                }
            }
        }
    }

    @Composable
    fun Toast(text: String, modifier: Modifier = Modifier) {
        if (text.isBlank()) return
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            shape = RoundedCornerShape(50),
            modifier = modifier
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = gap(4), vertical = gap(2))
            )
        }
    }

    @Composable
    fun Viewfinder(hint: String, modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = hint.ifBlank { "Point the camera at the code" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(gap(4))
            )
        }
    }

    @Composable
    fun Spinner(caption: String, modifier: Modifier = Modifier) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(gap(3)))
            Label(caption)
        }
    }
}

fun JsonObject.items(key: String = "items"): List<JsonObject> =
    this[key]?.let { runCatching { it.jsonArray }.getOrNull() }?.mapNotNull { it as? JsonObject } ?: emptyList()

fun JsonObject.text(key: String, fallback: String = ""): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: fallback

fun JsonObject.number(key: String, fallback: Double = 0.0): Double =
    this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: fallback

fun JsonObject.flag(key: String, fallback: Boolean = false): Boolean =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: fallback
