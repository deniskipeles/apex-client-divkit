package com.yandex.divkit.multiplatform

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.StackedLineChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import engine.ui.DivChartParser
import engine.ui.DivChartPoint
import engine.ui.DivChartSpec
import engine.ui.DivChartType
import kotlinx.serialization.json.JsonObject
import kotlin.math.*

/**
 * Native Android Jetpack Compose + Vico/KoalaPlot-architected Custom Chart Block Renderer for DivKit.
 *
 * Mirrors iOS `EngineChartBlockFactory` (Swift Charts + DGCharts) and consumes the shared KMP
 * [DivChartSpec] parsed from `"custom_type": "sdui_chart"` (`"bar_chart"`, `"line_chart"`,
 * `"area_chart"`, `"donut_chart"`, `"pie_chart"`).
 */
@Composable
fun RenderDivChartBlock(
    node: JsonObject,
    variables: Map<String, DivVariableValue>,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    onPointSelected: (DivChartPoint) -> Unit = {}
) {
    val spec = remember(node, variables) {
        DivChartParser.parse(node, variables)
    }

    var activeChartType by remember(spec.id, spec.chartType) {
        mutableStateOf(spec.chartType)
    }
    var selectedIndex by remember(spec.id, spec.points.size) {
        mutableStateOf(0)
    }

    val animProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
        label = "sdui_chart_anim"
    )

    val colorScheme = MaterialTheme.colorScheme
    val points = spec.points
    val safeSelectedIdx = selectedIndex.coerceIn(0, (points.size - 1).coerceAtLeast(0))
    val selectedPoint = points.getOrNull(safeSelectedIdx)

    Surface(
        color = colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, colorScheme.outline),
        modifier = modifier
            .fillMaxWidth()
            .testTag("div_custom_chart_${spec.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Header Row: Title + Subtitle + Engine Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = spec.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            color = colorScheme.primary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "KMP Chart",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = spec.subtitle,
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 2. Interactive Chart Type Switcher (Bar / Line / Area / Donut)
            if (spec.allowTypeSwitch) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val switchableTypes = listOf(
                        DivChartType.BAR,
                        DivChartType.LINE,
                        DivChartType.AREA,
                        DivChartType.DONUT
                    )
                    switchableTypes.forEach { t ->
                        val isSelected = activeChartType == t ||
                            (activeChartType == DivChartType.PIE && t == DivChartType.DONUT)
                        Surface(
                            color = if (isSelected) colorScheme.primary
                            else colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) colorScheme.primary else colorScheme.outline
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { activeChartType = t }
                                .testTag("chart_switch_${spec.id}_${t.wireName}")
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            ) {
                                Icon(
                                    imageVector = when (t) {
                                        DivChartType.BAR -> Icons.Default.BarChart
                                        DivChartType.LINE -> Icons.Default.ShowChart
                                        DivChartType.AREA -> Icons.Default.StackedLineChart
                                        DivChartType.DONUT, DivChartType.PIE -> Icons.Default.DonutLarge
                                    },
                                    contentDescription = t.displayLabel,
                                    tint = if (isSelected) Color.White else colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = t.wireName.replaceFirstChar { it.uppercase() },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // 3. Summary KPI Strip (Total, Peak, Selected Point Callout)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChartKpiChip(
                    label = "TOTAL",
                    value = formatChartValue(spec.totalValue, spec.valuePrefix, spec.valueSuffix),
                    color = colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                ChartKpiChip(
                    label = "PEAK",
                    value = formatChartValue(spec.maxValue, spec.valuePrefix, spec.valueSuffix),
                    color = colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
                if (selectedPoint != null) {
                    ChartKpiChip(
                        label = selectedPoint.label.uppercase().take(12),
                        value = formatChartValue(selectedPoint.value, spec.valuePrefix, spec.valueSuffix),
                        color = parseChartHexColor(selectedPoint.colorHex),
                        modifier = Modifier.weight(1.25f)
                    )
                }
            }

            // 4. Native High-DPI Canvas Chart Surface (Bar, Line, Area, or Donut/Pie)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(spec.heightDp.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC)
                    )
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                when (activeChartType) {
                    DivChartType.BAR -> BarChartCanvas(
                        spec = spec,
                        points = points,
                        selectedIndex = safeSelectedIdx,
                        animProgress = animProgress,
                        isDark = isDark,
                        onSelectIndex = { idx ->
                            selectedIndex = idx
                            points.getOrNull(idx)?.let { pt ->
                                publishChartSelection(spec, pt)
                                onPointSelected(pt)
                            }
                        }
                    )

                    DivChartType.LINE, DivChartType.AREA -> LineAreaChartCanvas(
                        spec = spec,
                        points = points,
                        isAreaFill = activeChartType == DivChartType.AREA,
                        selectedIndex = safeSelectedIdx,
                        animProgress = animProgress,
                        isDark = isDark,
                        onSelectIndex = { idx ->
                            selectedIndex = idx
                            points.getOrNull(idx)?.let { pt ->
                                publishChartSelection(spec, pt)
                                onPointSelected(pt)
                            }
                        }
                    )

                    DivChartType.DONUT, DivChartType.PIE -> DonutPieChartCanvas(
                        spec = spec,
                        points = points,
                        isDonut = activeChartType == DivChartType.DONUT,
                        selectedIndex = safeSelectedIdx,
                        animProgress = animProgress,
                        isDark = isDark,
                        onSelectIndex = { idx ->
                            selectedIndex = idx
                            points.getOrNull(idx)?.let { pt ->
                                publishChartSelection(spec, pt)
                                onPointSelected(pt)
                            }
                        }
                    )
                }
            }

            // 5. Interactive Legend / Point Selector Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                points.forEachIndexed { idx, pt ->
                    val isSel = idx == safeSelectedIdx
                    val ptColor = parseChartHexColor(pt.colorHex)
                    Surface(
                        color = if (isSel) ptColor.copy(alpha = 0.18f) else colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isSel) ptColor else colorScheme.outline.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                selectedIndex = idx
                                publishChartSelection(spec, pt)
                                onPointSelected(pt)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(ptColor)
                            )
                            Text(
                                text = pt.label,
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                color = colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                text = formatChartValue(pt.value, spec.valuePrefix, spec.valueSuffix),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = ptColor
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun publishChartSelection(spec: DivChartSpec, point: DivChartPoint) {
    val formatted = "${point.label}: ${formatChartValue(point.value, spec.valuePrefix, spec.valueSuffix)}"
    if (spec.selectionVar.startsWith("user.")) {
        EngineStateBridge.pushUpdate(spec.selectionVar, DivVariableValue.Str(formatted))
    }
}

@Composable
private fun ChartKpiChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        color = colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BarChartCanvas(
    spec: DivChartSpec,
    points: List<DivChartPoint>,
    selectedIndex: Int,
    animProgress: Float,
    isDark: Boolean,
    onSelectIndex: (Int) -> Unit
) {
    val gridColor = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)
    val maxVal = (spec.maxValue.takeIf { it > 0.0 } ?: 1.0) * 1.12

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(points.size) {
                detectTapGestures { offset ->
                    if (points.isNotEmpty() && size.width > 0) {
                        val slotWidth = size.width.toFloat() / points.size
                        val tappedIdx = (offset.x / slotWidth).toInt().coerceIn(0, points.size - 1)
                        onSelectIndex(tappedIdx)
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val bottomPad = 14f
        val topPad = 12f
        val chartH = (h - bottomPad - topPad).coerceAtLeast(10f)

        // Horizontal Y-axis gridlines
        for (i in 0..3) {
            val y = topPad + (chartH / 3f) * i
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        if (points.isEmpty()) return@Canvas
        val slotWidth = w / points.size
        val barWidth = (slotWidth * 0.56f).coerceIn(12f, 64f)

        points.forEachIndexed { idx, pt ->
            val ratio = ((pt.value / maxVal).toFloat().coerceIn(0.04f, 1f)) * animProgress
            val barHeight = chartH * ratio
            val left = idx * slotWidth + (slotWidth - barWidth) / 2f
            val top = topPad + (chartH - barHeight)
            val baseColor = parseChartHexColor(pt.colorHex)
            val isSelected = idx == selectedIndex

            // Highlight column backdrop when selected
            if (isSelected) {
                drawRoundRect(
                    color = baseColor.copy(alpha = 0.12f),
                    topLeft = Offset(idx * slotWidth + 4f, topPad),
                    size = Size(slotWidth - 8f, chartH),
                    cornerRadius = CornerRadius(8.dp.toPx())
                )
            }

            // Vertical gradient bar
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        baseColor,
                        baseColor.copy(alpha = if (isSelected) 0.95f else 0.72f)
                    ),
                    startY = top,
                    endY = top + barHeight
                ),
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(6.dp.toPx())
            )

            // Top indicator dot on selected bar
            if (isSelected) {
                drawCircle(
                    color = baseColor,
                    radius = 4.dp.toPx(),
                    center = Offset(left + barWidth / 2f, (top - 6.dp.toPx()).coerceAtLeast(4f))
                )
            }
        }
    }
}

@Composable
private fun LineAreaChartCanvas(
    spec: DivChartSpec,
    points: List<DivChartPoint>,
    isAreaFill: Boolean,
    selectedIndex: Int,
    animProgress: Float,
    isDark: Boolean,
    onSelectIndex: (Int) -> Unit
) {
    val gridColor = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)
    val primaryColor = parseChartHexColor(spec.accentHex)
    val maxVal = (spec.maxValue.takeIf { it > 0.0 } ?: 1.0) * 1.15

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(points.size) {
                detectTapGestures { offset ->
                    if (points.isNotEmpty() && size.width > 0) {
                        val step = if (points.size > 1) size.width.toFloat() / (points.size - 1) else size.width.toFloat()
                        val tappedIdx = ((offset.x + step / 2f) / step).toInt().coerceIn(0, points.size - 1)
                        onSelectIndex(tappedIdx)
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val padX = 16.dp.toPx()
        val topPad = 14.dp.toPx()
        val bottomPad = 14.dp.toPx()
        val chartW = (w - padX * 2f).coerceAtLeast(10f)
        val chartH = (h - topPad - bottomPad).coerceAtLeast(10f)

        for (i in 0..3) {
            val y = topPad + (chartH / 3f) * i
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        if (points.isEmpty()) return@Canvas

        val coords = points.mapIndexed { idx, pt ->
            val x = if (points.size > 1) {
                padX + (chartW / (points.size - 1)) * idx
            } else {
                w / 2f
            }
            val ratio = ((pt.value / maxVal).toFloat().coerceIn(0.03f, 1f)) * animProgress
            val y = topPad + chartH * (1f - ratio)
            Offset(x, y)
        }

        val linePath = Path().apply {
            coords.forEachIndexed { idx, pt ->
                if (idx == 0) {
                    moveTo(pt.x, pt.y)
                } else {
                    val prev = coords[idx - 1]
                    val midX = (prev.x + pt.x) / 2f
                    cubicTo(midX, prev.y, midX, pt.y, pt.x, pt.y)
                }
            }
        }

        if (isAreaFill && coords.size >= 2) {
            val areaPath = Path().apply {
                addPath(linePath)
                lineTo(coords.last().x, topPad + chartH)
                lineTo(coords.first().x, topPad + chartH)
                close()
            }
            drawPath(
                path = areaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.38f),
                        primaryColor.copy(alpha = 0.02f)
                    ),
                    startY = topPad,
                    endY = topPad + chartH
                )
            )
        }

        drawPath(
            path = linePath,
            color = primaryColor,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        coords.forEachIndexed { idx, offset ->
            val isSel = idx == selectedIndex
            val ptColor = parseChartHexColor(points[idx].colorHex)
            if (isSel) {
                drawLine(
                    color = ptColor.copy(alpha = 0.45f),
                    start = Offset(offset.x, topPad),
                    end = Offset(offset.x, topPad + chartH),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawCircle(
                    color = ptColor.copy(alpha = 0.25f),
                    radius = 9.dp.toPx(),
                    center = offset
                )
            }
            drawCircle(
                color = if (isDark) Color(0xFF0F172A) else Color.White,
                radius = if (isSel) 5.dp.toPx() else 4.dp.toPx(),
                center = offset
            )
            drawCircle(
                color = if (isSel) ptColor else primaryColor,
                radius = if (isSel) 4.dp.toPx() else 3.dp.toPx(),
                center = offset
            )
        }
    }
}

@Composable
private fun DonutPieChartCanvas(
    spec: DivChartSpec,
    points: List<DivChartPoint>,
    isDonut: Boolean,
    selectedIndex: Int,
    animProgress: Float,
    isDark: Boolean,
    onSelectIndex: (Int) -> Unit
) {
    val total = spec.totalValue.takeIf { it > 0.0 } ?: 1.0
    val selectedPt = points.getOrNull(selectedIndex)
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(points.size) {
                        detectTapGestures { offset ->
                            if (points.isEmpty()) return@detectTapGestures
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val dx = offset.x - center.x
                            val dy = offset.y - center.y
                            var angle = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI + 90.0) % 360.0
                            if (angle < 0) angle += 360.0
                            var acc = 0.0
                            for (i in points.indices) {
                                val sweep = (points[i].value / total) * 360.0
                                if (angle >= acc && angle <= acc + sweep) {
                                    onSelectIndex(i)
                                    break
                                }
                                acc += sweep
                            }
                        }
                    }
            ) {
                val diameter = min(size.width, size.height) * 0.86f
                val strokeW = if (isDonut) diameter * 0.24f else diameter / 2f
                val topLeft = Offset(
                    (size.width - diameter) / 2f,
                    (size.height - diameter) / 2f
                )
                var startAngle = -90f
                points.forEachIndexed { idx, pt ->
                    val sweep = ((pt.value / total).toFloat() * 360f) * animProgress
                    val color = parseChartHexColor(pt.colorHex)
                    val isSel = idx == selectedIndex
                    if (isDonut) {
                        drawArc(
                            color = color,
                            startAngle = startAngle + 1.5f,
                            sweepAngle = (sweep - 3f).coerceAtLeast(2f),
                            useCenter = false,
                            topLeft = topLeft,
                            size = Size(diameter, diameter),
                            style = Stroke(
                                width = if (isSel) strokeW * 1.18f else strokeW,
                                cap = StrokeCap.Butt
                            )
                        )
                    } else {
                        drawArc(
                            color = color,
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = true,
                            topLeft = topLeft,
                            size = Size(diameter, diameter)
                        )
                    }
                    startAngle += sweep
                }
            }

            if (isDonut && selectedPt != null) {
                val sharePct = ((selectedPt.value / total) * 100.0).roundToInt()
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$sharePct%",
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = parseChartHexColor(selectedPt.colorHex)
                    )
                    Text(
                        text = selectedPt.label.take(10),
                        fontSize = 10.sp,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }

        // Right-side breakdown list inside Donut/Pie view
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            points.take(4).forEachIndexed { idx, pt ->
                val sharePct = ((pt.value / total) * 100.0).roundToInt()
                val ptColor = parseChartHexColor(pt.colorHex)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelectIndex(idx) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(ptColor)
                        )
                        Text(
                            text = pt.label,
                            fontSize = 11.sp,
                            fontWeight = if (idx == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "$sharePct%",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = ptColor
                    )
                }
            }
        }
    }
}

private fun formatChartValue(value: Double, prefix: String, suffix: String): String {
    val formattedNum = if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        String.format("%.2f", value)
    }
    return "$prefix$formattedNum$suffix"
}

private fun parseChartHexColor(hexStr: String): Color {
    val clean = hexStr.trim().removePrefix("#")
    return try {
        when (clean.length) {
            6 -> Color(("FF$clean").toLong(16))
            8 -> Color(clean.toLong(16))
            else -> Color(0xFF0284C7)
        }
    } catch (_: Exception) {
        Color(0xFF0284C7)
    }
}
