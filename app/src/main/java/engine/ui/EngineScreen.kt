package engine.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.yandex.divkit.multiplatform.DivKit
import com.yandex.divkit.multiplatform.DivKitDependencies
import com.yandex.divkit.multiplatform.DivKitEnvironment
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
fun createDivKitDependencies(): DivKitDependencies {
    error("Use rememberDivKitDependencies() from a composable")
}

@Composable
fun rememberDivKitDependencies(): DivKitDependencies {
    val ctx = LocalContext.current
    return remember {
        DivKitEnvironment.Builder(baseContext = ctx)
            .build()
            .dependencies
    }
}
