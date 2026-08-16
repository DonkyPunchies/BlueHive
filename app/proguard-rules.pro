############################################################
# ProGuard / R8 rules for BlueHive Android TV App
############################################################

# --- 1. Strip debug/info logs in release ---
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# --- 2. Keep stack traces for crash reporting ---
-keepattributes SourceFile,LineNumberTable

# --- 2b. THE HOST CONTRACT — do not let R8 touch this ---
#
# AIDL identifies an interface by its fully-qualified name at RUNTIME: the
# generated Stub carries a DESCRIPTOR string built from the package + class, and
# bindService() only succeeds when the descriptor the host exposes matches the one
# the companion asks for.
#
# R8 renames classes. Rename ICompanionHost and the descriptor no longer matches
# what Off-Grid Drive (or any other host) publishes, so every bind fails and
# BlueHive never receives a token — presenting as the 30-second blank launch and
# the "Set up BlueHive" toast, with nothing in the logs naming the cause. Worse,
# it would only break in RELEASE builds, since debug is not minified.
#
# This package is a PUBLISHED CONTRACT shared byte-for-byte with every host, so
# obfuscating it was never meaningful anyway — the names are the interface.
-keep class io.companion.host.** { *; }
-keep interface io.companion.host.** { *; }
-keepclassmembers class io.companion.host.** { *; }

# --- 3. Retrofit / OkHttp / Gson ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep Retrofit interfaces
-keep interface com.example.bluehive.api.** { *; }
-keep class com.example.bluehive.api.** { *; }

# Keep Gson models + their fields
-keep class com.example.bluehive.models.** { *; }
-keepclassmembers class com.example.bluehive.models.** {
    <fields>;
}

# Keep type signatures & annotations for Retrofit/Gson
-keepattributes Signature, *Annotation*

# --- 4. GeckoView ---
-keep class org.mozilla.** { *; }
-dontwarn org.mozilla.**

# --- 5. Coroutines / Lifecycle ---
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

-dontwarn androidx.lifecycle.**
-keep class androidx.lifecycle.** { *; }

# --- 6. Custom Views (XML inflation) ---
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# --- 7. Ktor / MockEngine (test-only, safe to ignore) ---
-dontwarn io.ktor.client.engine.mock.**
-dontwarn io.ktor.utils.io.**
-dontwarn io.ktor.http.**

# --- 8. SnakeYAML / JavaBeans (desktop-only, not on Android) ---
-dontwarn java.beans.**

# Coroutines - CRITICAL for API calls
-keepclassmembers class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlin.coroutines.** { *; }
-keep class kotlin.coroutines.** { *; }
-dontwarn kotlinx.coroutines.DebugProbesKt

# Retrofit service interfaces - CRITICAL
-keep interface com.example.bluehive.api.** { *; }
-keepclassmembers interface com.example.bluehive.api.** { *; }

# Keep your repository classes
-keep class com.example.bluehive.repository.** { *; }
-keepclassmembers class com.example.bluehive.repository.** { *; }




# --- NewPipeExtractor / jsoup re2j ---
-dontwarn com.google.re2j.**
-keep class com.google.re2j.** { *; }

# --- javax.script (not on Android) ---
-dontwarn javax.script.**

# --- NewPipeExtractor ---
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# --- jsoup ---
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# --- Coil ---
-keep class coil.** { *; }
-dontwarn coil.**

# --- Coil ExperimentalCoilApi (used in CacheMonitor) ---
-keep @coil.annotation.ExperimentalCoilApi class * { *; }

# --- Media3 / ExoPlayer ---
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# --- ThanosEffect ---
-keep class com.exjunk.thanoseffect.** { *; }
-dontwarn com.exjunk.thanoseffect.**




############################################################
# End of rules
############################################################
