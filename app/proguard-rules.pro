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
