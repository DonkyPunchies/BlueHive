############################################################
# ProGuard / R8 rules for BlueHive Android TV App
############################################################

# --- 1. Strip debug/info logs in release ---
#
# e() AND wtf() ARE DELIBERATELY NOT STRIPPED, as of 2026-08-16.
#
# They were, and it cost a diagnosis. An episode ended prematurely and there was
# nothing to look at: no crash (a playback failure is not one), no relay row (it
# played direct), and no app logs (this block had removed them). Three sources,
# all silent, for a bug that ExoPlayer had already described precisely.
#
# The reason for stripping was that log STRINGS shipped inside the APK and the
# Diagnostics "Send Logs" feature uploaded that output to the backend. That
# concern is about volume and content, and v/d/i/w is where both live — the
# chatty per-frame, per-request, per-focus-change lines. Log.e fires on real
# failures only, so keeping it costs a handful of strings and buys the ability to
# diagnose the field.
#
# If a leak is ever traced to an error string, fix that call site rather than
# blinding the release build again.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
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

# -- R8 VerifyError guard: the two detail screens -------------------------------
#
# OPENING ANY TV SHOW KILLED THE APP. ART refused to load
# TVShowsDetailsScreenComposeKt at all -- "Verifier rejected class ... type
# Conflict unexpected as arg to if-eqz" -- because R8's optimizer emitted
# bytecode for TVShowsDetailsScreenContent that fails verification: a register
# carrying incompatible types down different branches, then tested as a
# condition. That function takes 27 parameters and is a single enormous
# composable, which is the shape these R8 miscompiles live in.
#
# THE FILE HAD NOT CHANGED. R8 re-optimizes the whole app on every release
# build, so edits elsewhere shifted how it treated this function; the source
# was byte-identical to the build where it worked. Debug builds never show it,
# because they skip R8 entirely.
#
# Keeping the class stops R8 rewriting these members' signatures and inlining
# them, which is what produced the unverifiable register types. The movie
# screen is kept too: same shape, same risk, and the verifier scan that
# should have caught this one did not, so its absence of complaints about
# the movie screen proves nothing. The real fix is splitting both functions;
# remove these lines only after that, and only once a release build has been
# opened on a device.
-keep class com.example.bluehive.TVShowsDetailsScreenComposeKt { *; }
-keep class com.example.bluehive.MoviesDetailsScreenComposeKt { *; }
