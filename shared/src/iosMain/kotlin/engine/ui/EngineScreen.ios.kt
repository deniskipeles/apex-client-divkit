package engine.ui

import com.yandex.divkit.multiplatform.DivKitDependencies
import com.yandex.divkit.multiplatform.DivKitKMP

actual fun createDivKitDependencies(): DivKitDependencies =
    DivKitKMP.createDependencies()
