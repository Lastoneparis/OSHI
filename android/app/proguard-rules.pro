# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Retrofit / Gson
-keep class com.google.gson.** { *; }
-keep class com.squareup.retrofit2.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# (No WebRTC rule. `-keep class org.webrtc.** { *; }` used to live here; the
# stream-webrtc-android dependency it protected was never referenced by a single
# line of app code and has been removed — see the note in app/build.gradle.kts.)

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.oshi.messenger.**$$serializer { *; }
-keepclassmembers class com.oshi.messenger.** { *** Companion; }
-keepclasseswithmembers class com.oshi.messenger.** { kotlinx.serialization.KSerializer serializer(...); }

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ZXing
-keep class com.google.zxing.** { *; }

# Keep data classes used in JSON parsing
-keep class com.oshi.messenger.data.** { *; }
-keep class com.oshi.messenger.network.** { *; }

# Compose
-dontwarn androidx.compose.**

# llama.cpp JNI — keep native methods and the bridge class
-keep class com.oshi.messenger.service.LlamaCpp { *; }
-keep class com.oshi.messenger.service.LocalLLMManager { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ──────────────────────────────────────────────────────────────────────────────
# 🔴 STRIP ALL android.util.Log CALLS FROM RELEASE BUILDS.
#
# Without this, every Log.d/v/i in the app ships, and several of them print
# material that must never leave the device:
#   MessageRepository  "✅ DoubleRatchet decrypted: ${plaintext.take(50)}..."
#   MessageRepository  decrypted mesh bodies, plaintextContent
#   DoubleRatchet      the first 20 base64 chars of the ROOT KEY and of the
#                      initial chain key, at session creation
#   VPSClient/GroupManager/FCMService  message bodies and notification text
#
# Testers run release builds on real phones, where anything holding READ_LOGS —
# an adb session, a device-log capture tool, a crash SDK that scrapes logcat —
# reads all of it. iOS is not exposed this way: it logs through os.Logger, whose
# payloads are redacted in release.
#
# `assumenosideeffects` lets R8 delete the calls outright, so the string
# concatenation that builds those messages is removed too — not just the write.
# Log.e and Log.wtf are DELIBERATELY KEPT: they carry no plaintext and they are
# what makes a field crash report readable.
# __MESH_DIAGNOSTICS_SURVIVE_RELEASE_2026_10_23__ `println` is deliberately NOT
# in this list. `MeshDiag` writes through it so that a handful of mesh STATE
# lines — a transition, a failure code, a count, never a body or a key — stay
# readable on a release build. Without them a phone that silently fails to
# advertise the mesh service cannot be diagnosed on the device where it fails,
# which is exactly what happened on a Galaxy A17 on 2026-10-23. Adding
# `println` here would silence that channel; if you ever need to, give MeshDiag
# another way out first.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
