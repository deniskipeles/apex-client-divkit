package engine.ui

import androidx.compose.runtime.*
import com.yandex.divkit.multiplatform.DivKit
import com.yandex.divkit.multiplatform.DivKitDependencies
import com.yandex.divkit.multiplatform.DivView

/**
 * Root Compose screen. Platform layers (Android Activity / iOS UIViewController)
 * inject [divKitDependencies] and [engine] so this composable stays platform-neutral.
 */
@Composable
fun EngineScreen(
    divKitDependencies: DivKitDependencies,
    initialJson: String,
    cardId: String = "engine_root",
    onAction: suspend (url: String, payload: String?) -> Unit = { _, _ -> }
) {
    var json by remember(initialJson) { mutableStateOf(initialJson) }

    DivKit(dependencies = divKitDependencies) {
        DivView(
            cardId = cardId,
            jsonData = json,
            onAction = onAction
        )
    }
}

/** Platform-specific DivKitDependencies factory. */
expect fun createDivKitDependencies(): DivKitDependencies
