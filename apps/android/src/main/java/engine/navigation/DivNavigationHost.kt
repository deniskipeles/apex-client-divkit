package engine.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.yandex.divkit.multiplatform.DivView

/**
 * Host-side navigation container over the Compose [DivView] renderer.
 *
 * Every live card id stays in the composition tree; only the active one is opaque. Keeping
 * inactive cards composed (rather than dropping them) preserves their remembered state —
 * scroll offsets, field focus, in-flight animations — across push/pop. This is the Compose
 * equivalent of iOS `removeFromSuperview()` / a detached `View` subtree.
 *
 * The bottom sheet is a second DivKit root ([ModalBottomSheet]) over the current canvas,
 * rendering the same payload the [CardRegistry] holds for that id — no re-parse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DivNavigationHost(
    initial: List<CardEntry>,
    modifier: Modifier = Modifier,
    cacheLimit: Int = 8,
) {
    val navigator = remember { DivCardNavigator(initial, cacheLimit) }
    var version by remember { mutableIntStateOf(0) }
    var sheetCardId by remember { mutableStateOf<String?>(null) }

    val router = remember(navigator) {
        NavigationRouter(navigator).apply {
            onChange = { version++ }
            onSheetPresent = { id, _ -> sheetCardId = id }
            onSheetDismiss = { sheetCardId = null }
        }
    }

    // Reading `version` here re-derives the active id after any stack mutation.
    val activeId = remember(version) { navigator.current?.id }
    val liveIds = remember(version) { navigator.liveCardIds }

    Box(modifier = modifier.fillMaxSize()) {
        // Render inactive cards first so the active card is drawn (and hit-tested) on top.
        val ordered = liveIds.sortedBy { it == activeId }
        for (id in ordered) {
            val card = CardRegistry.card(id) ?: continue
            key(id) {
                val isActive = id == activeId
                DivView(
                    cardId = id,
                    jsonData = card.toString(),
                    modifier = Modifier.fillMaxSize().alpha(if (isActive) 1f else 0f),
                    onAction = { url, _ -> router.handle(url) },
                )
            }
        }
    }

    val sheetId = sheetCardId
    val sheetCard = sheetId?.let { CardRegistry.card(it) }
    if (sheetId != null && sheetCard != null) {
        ModalBottomSheet(
            onDismissRequest = { sheetCardId = null },
            modifier = Modifier.fillMaxWidth(),
        ) {
            DivView(
                cardId = sheetId,
                jsonData = sheetCard.toString(),
                modifier = Modifier.fillMaxSize(),
                onAction = { url, _ -> router.handle(url) },
            )
        }
    }
}
