plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("com.android.library")
    id("app.cash.sqldelight")
    kotlin("native.cocoapods")
}

kotlin {
    androidTarget()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    cocoapods {
        summary = "KMP SDUI POS Engine with DivKit Custom Blocks & Native Charts"
        homepage = "https://example.com/sdui-pos-engine"
        version = "1.1.0"
        ios.deploymentTarget = "15.0"
        // Mature iOS Chart Library (DGCharts / Charts) alongside native Apple Swift Charts (iOS 16+)
        pod("DGCharts") {
            version = "~> 5.1.0"
        }
        pod("DivKit") {
            version = "~> 30.9.0"
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
                implementation("app.cash.sqldelight:runtime:2.0.2")
                // Mature KMP Multiplatform Charting Library (KoalaPlot Compose Multiplatform)
                implementation("io.github.koalaplot:koalaplot-core:0.6.3")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:android-driver:2.0.2")
                implementation("androidx.work:work-runtime-ktx:2.10.0")
                // Mature Android Compose Charting Library (Vico Material 3 + Core)
                implementation("com.patrykandpatrick.vico:core:2.0.0")
                implementation("com.patrykandpatrick.vico:compose-m3:2.0.0")
            }
        }
        val iosMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("app.cash.sqldelight:native-driver:2.0.2")
            }
        }
    }
}
