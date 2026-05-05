# ===================================================================
# QBSmarter – R8 / ProGuard rules for release builds.
# ===================================================================
#
# This file is consulted by R8 (configured in build.gradle.kts under
# `buildTypes { release { ... } }`) on top of Android's bundled
# `proguard-android-optimize.txt`. The rules here serve three purposes:
#
#   1. Strip low-severity logging from the bytecode (debug, verbose,
#      info), keeping warning/error so crash reports remain useful.
#   2. Keep classes that are accessed reflectively (Koin, kotlinx-
#      serialization, AndroidX lifecycle, Compose Compiler internals).
#   3. Suppress warnings for transitive dependencies that have
#      missing-class references, which is normal in cross-platform
#      builds where some classes only exist on certain targets.
#
# When adding a new library that uses reflection, add its consumer
# rules here and document the reason inline.
# ===================================================================


# -- Section 1: Log stripping ---------------------------------------
# `-assumenosideeffects` tells R8 the listed methods have no observable
# effect, so calls to them can be removed entirely. The methods stay
# in the JAR (we can't actually delete library code), but the calls
# vanish – including the construction of any String arguments, which
# is the real win for performance.
#
# We strip:
#   - Kermit Logger.d / v / i (and their lambda-message overloads).
#   - android.util.Log.d / v / i.
#
# We KEEP w / e at all severities because:
#   - Crash reports (uncaught exceptions) often log a final message
#     before going down. Without .e we'd lose that breadcrumb.
#   - Internal warnings ("connect: timed out", "import: schema mismatch")
#     are valuable for diagnosing user-reported issues post-shipping.
#
# Rationale: aggressive log stripping is the standard production
# practice. The "code says one thing, prod does another" tax pays for
# itself in APK size + zero log-spam in user devices' logcat.

# Kermit (co.touchlab.kermit) – strip debug/verbose/info.
# The vararg/lambda overloads need separate rules because they have
# distinct method signatures from R8's perspective.
-assumenosideeffects class co.touchlab.kermit.Logger {
    public *** d(...);
    public *** v(...);
    public *** i(...);
}
-assumenosideeffects class co.touchlab.kermit.Logger$Companion {
    public *** d(...);
    public *** v(...);
    public *** i(...);
}
# BaseLogger is the underlying interface that Logger delegates to;
# need to strip there too so calls aren't merely re-routed.
-assumenosideeffects class co.touchlab.kermit.BaseLogger {
    public *** d(...);
    public *** v(...);
    public *** i(...);
}

# android.util.Log – strip debug/verbose/info.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}


# -- Section 2: Reflection-using libraries --------------------------
# These libraries inspect class names / annotations at runtime. Without
# explicit -keep rules R8's tree-shaker would (correctly, from its
# point of view) drop the classes as unreferenced.

# Koin – DI graph is built at runtime by inspecting class references.
# The DI module file (`AppModule.kt`) names types via `get<T>()` /
# `single<T>` patterns that R8 can't statically follow. Keeping all
# `data class`es and ViewModels avoids whack-a-mole.
-keep class com.zucham.qbsmarter.** { *; }
# Koin's own bytecode also uses reflection.
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# kotlinx.serialization – generated $$serializer companions are
# referenced by name from KSerializer.lookup() / SerializersModule.
# The `@Serializable` annotation processor generates these, and R8
# would happily inline-and-drop them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# AndroidX Lifecycle – uses generated _LifecycleAdapter classes
# discovered reflectively by ProcessLifecycleOwner.
-keep class * implements androidx.lifecycle.GeneratedAdapter { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Compose Compiler – keeps method names referenced by the runtime's
# composer state machine.
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# Korender (the 3D engine) – uses Java reflection internally for
# shader / material discovery. Keep the whole package out of an
# abundance of caution.
-keep class com.zakgof.korender.** { *; }
-dontwarn com.zakgof.korender.**

# SQLDelight – generated query classes referenced via the database
# interface. Keep all generated `db.*` types.
-keep class com.zucham.qbsmarter.db.** { *; }


# -- Section 3: BLE / system APIs -----------------------------------

# AppCompat's per-app locale plumbing – uses reflection to locate
# AppCompatDelegate. Already kept by AndroidX consumer rules but
# explicit insurance doesn't hurt.
-keep class androidx.appcompat.app.AppCompatDelegate { *; }


# -- Section 4: Suppress warnings for missing platform classes ------
# These come up because shared multiplatform code references types
# that exist on JVM/web but not Android (e.g. some kotlinx
# serialization JS-only classes). Harmless at runtime.
-dontwarn java.lang.invoke.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.Unit
