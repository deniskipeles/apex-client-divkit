package engine.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.yandex.divkit.multiplatform.DivKitDependencies
import com.yandex.divkit.multiplatform.DivKitEnvironment

actual fun createDivKitDependencies(): DivKitDependencies {
    // Must be called from a @Composable scope so LocalContext is available.
    // Wrapped in a @Composable helper below.
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
