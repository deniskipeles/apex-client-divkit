# Android ProGuard & R8 Optimization Rules for SDUI POS Multi-Tenant Engine
# Configured for maximum execution performance and safe resource shrinking without runtime latency penalties.

# 1. Preserve Annotations, Signatures and Serialization for Kotlin & Compose
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# KotlinX Serialization
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keep class * implements kotlinx.serialization.KSerializer {
    *;
}
-keep class kotlinx.serialization.** { *; }

# 2. Engine Core, Routing, Dynamic Tools & SQLite Isolation
-keep class engine.** { *; }
-keepclassmembers class engine.** { *; }
-keep class com.example.enginehost.** { *; }
-keepclassmembers class com.example.enginehost.** { *; }

# Native Tools Suite (libsignal, TFLite models, barcode/QR scanner)
-keep class engine.tools.** { *; }
-keepclassmembers class engine.tools.** { *; }

# 3. Cryptography & Security Invariants (Ed25519, AES-GCM, CanonicalJson)
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-keep class org.bouncycastle.** { *; }

# 4. DivKit & UI Engine Components
-keep class com.yandex.divkit.** { *; }
-keep class com.yandex.div.** { *; }
-keep class com.patrykandpatrick.vico.** { *; }

# 5. Fast Execution Tuning: prevent aggressive micro-optimizations that degrade JIT warmup
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-repackageclasses
-allowaccessmodification

# 6. Optional TLS platform providers in OkHttp (not required on Android)
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# 7. Ktor's debugger detector references JVM-only java.lang.management types that do not
# exist on Android (surfaced by tools://net's Ktor CIO client/server).
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn io.ktor.util.debug.**
