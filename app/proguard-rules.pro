# ProGuard / R8 Rules for ParamAuto Release

# -----------------------------------------------------------------------------
# AndroidX Car App Library
# -----------------------------------------------------------------------------
-keep class androidx.car.app.** { *; }
-keep interface androidx.car.app.** { *; }
-dontwarn androidx.car.app.**

# Preserve CarAppService and Session implementations
-keep class com.parampilot.auto.ParamAuto { *; }
-keep public class com.parampilot.auto.ParamAutoCarAppService extends androidx.car.app.CarAppService { *; }
-keep public class com.parampilot.auto.ParamAutoSession extends androidx.car.app.Session { *; }
-keep public class * extends androidx.car.app.Screen { *; }

# -----------------------------------------------------------------------------
# MapLibre GL Native SDK
# -----------------------------------------------------------------------------
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**

# Native JNI methods must not be renamed or stripped
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve MapLibre geometry, style, annotations and offline classes
-keep class org.maplibre.android.geometry.** { *; }
-keep class org.maplibre.android.maps.** { *; }
-keep class org.maplibre.android.style.** { *; }
-keep class org.maplibre.android.offline.** { *; }
-keep class org.maplibre.android.annotations.** { *; }

# -----------------------------------------------------------------------------
# ParamPilot Auto Application Models & Reflection
# -----------------------------------------------------------------------------
-keep class com.parampilot.auto.MainActivity { *; }
-keep class com.parampilot.auto.GalaxyWebActivity { *; }

# Preserve JSON models, telemetry state, and BleBridge protocol structures
-keep class com.parampilot.auto.NavState* { *; }
-keep class com.parampilot.auto.NavStateManager* { *; }
-keep class com.parampilot.auto.SgGalaxyClient* { *; }
-keep class com.parampilot.auto.BleBridgeClient* { *; }
-keep class com.parampilot.auto.StarPilotBleBridge* { *; }
-keep class com.parampilot.auto.RouteProgress* { *; }
-keep class com.parampilot.auto.Bookmarks* { *; }
-keep class com.parampilot.auto.DestinationHistory* { *; }
-keep class com.parampilot.auto.SearchCache* { *; }
-keep class com.parampilot.auto.UsageCounters* { *; }

# -----------------------------------------------------------------------------
# Kotlin & Coroutines
# -----------------------------------------------------------------------------
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature,SourceFile,LineNumberTable
-dontwarn kotlin.**
-dontwarn kotlinx.**

# -----------------------------------------------------------------------------
# Strip Debug Logging in Release Builds
# -----------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

