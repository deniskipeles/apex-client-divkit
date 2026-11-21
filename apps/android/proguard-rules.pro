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

# libsignal's curve25519 providers are instantiated by name inside
# `Curve25519.constructClass` via `Class.forName`, so R8 cannot see the reference
# and strips them — the release APK then throws NoClassDefFoundError the first
# time an E2EE session is opened.
-keep class org.whispersystems.curve25519.** { *; }

# 4. Room & androidx.startup: reflectively instantiated components
# Room's generated `<Database>_Impl` classes are created by
# `Room.getGeneratedImplementation` via `Class.forName(...).getDeclaredConstructor(new Class[0])`.
# Room ships only `-keep class * extends androidx.room.RoomDatabase`, which preserves the class
# but lets R8 strip the implicit no-arg constructor, so the reflective lookup throws
# NoSuchMethodException: <init> [] at startup (WorkManager -> WorkDatabase -> WorkDatabase_Impl).
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(...); }

# androidx.startup instantiates each Initializer listed in AndroidManifest meta-data by name.
-keep class * implements androidx.startup.Initializer { <init>(); }
-keepclassmembers class * implements androidx.startup.Initializer { <init>(...); }
-keep class androidx.startup.** { *; }

# WorkManager resolves InputMerger / Worker subclasses from string class names.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class * extends androidx.work.WorkerFactory { *; }

# ML Kit and Firebase discover component registrars by name from manifest/asset metadata and
# instantiate them with a no-arg constructor (`ComponentDiscovery` / `ComponentRegistrar`).
# Without the constructor R8 leaves the classes in place but drops `<init>`, so OCR, barcode
# scanning and text recognition fail at runtime with a "Invalid component registrar" warning.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keepclassmembers class * implements com.google.firebase.components.ComponentRegistrar { <init>(...); }
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# 5. DivKit & UI Engine Components
-keep class com.yandex.divkit.** { *; }
-keep class com.yandex.div.** { *; }
-keep class com.patrykandpatrick.vico.** { *; }

# 6. Fast Execution Tuning: prevent aggressive micro-optimizations that degrade JIT warmup
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-repackageclasses
-allowaccessmodification

# 7. Optional TLS platform providers in OkHttp (not required on Android)
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# 8. Ktor's debugger detector references JVM-only java.lang.management types that do not
# exist on Android (surfaced by tools://net's Ktor CIO client/server).
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn io.ktor.util.debug.**
