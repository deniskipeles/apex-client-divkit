package com.yandex.divkit.multiplatform

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import engine.state.DivVariableValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * True when [source] points at real pixels rather than a native icon name. Tool output is
 * written as `file://` into the tenant media sandbox; `content://` and `data:` are accepted
 * so a provider-backed or inlined bitmap previews the same way.
 */
internal fun isBitmapSource(source: String): Boolean =
    source.startsWith("file://") ||
        source.startsWith("content://") ||
        source.startsWith("data:image/")

/**
 * Renders a real bitmap inside a DivKit `image` node, so generated codes, camera captures and
 * processed images are visible instead of only their URI being printed as text.
 */
@Composable
internal fun RenderDivBitmap(
    node: JsonObject,
    source: String,
    variables: Map<String, DivVariableValue>,
    rowContext: Map<String, Any?>,
    modifier: Modifier
) {
    val heightDp = node["height"]?.jsonObject?.get("value")?.jsonPrimitive?.intOrNull
        ?: node["preview_height"]?.jsonPrimitive?.intOrNull
        ?: 160
    val radius = node["corner_radius"]?.jsonPrimitive?.intOrNull ?: 10
    val caption = node["content_description"]?.jsonPrimitive?.contentOrNull
        ?.let { evalExpr(it, variables, rowContext) }
    val tag = "div_bitmap_" + source.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._]+"), "_")

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(radius.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = source,
                contentDescription = caption ?: "Image preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp.dp)
                    .padding(4.dp)
                    .testTag(tag)
            )
            if (caption != null) {
                Text(
                    text = caption,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                )
            }
        }
    }
}
