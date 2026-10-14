# Task 9 — R8/ProGuard keep rules for the Novelfa release build.
# The release block in app/build.gradle.kts enables minification with
# proguard-android-optimize.txt plus this file; verify with :app:assembleRelease.

# --- Room (entities + DAO + generated impl) ---
-keep class com.novelfa.reader.data.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# --- WebView JS bridge: methods called by name from JavaScript ---
-keepclasseswithmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.novelfa.reader.reader.ScrollBridge { *; }

# --- App entry points referenced from AndroidManifest.xml ---
-keep public class com.novelfa.reader.MainActivity
-keep public class com.novelfa.reader.NovelfaApplication
-keep public class com.novelfa.reader.BuildConfig { *; }
