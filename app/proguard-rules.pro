# ---------------------------------------------------------------------------
# Tote — ProGuard / R8 rules.
# ---------------------------------------------------------------------------
# Minify is ON since v0.6.0-alpha1. These rules preserve the things R8
# can't see via static analysis: JNA reflection into native libs, kotlinx-
# serialization-generated serializers, OkHttp platform adapters, Compose
# runtime internals.
# ---------------------------------------------------------------------------

# === General: keep annotations + signature metadata ===
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations, RuntimeInvisibleTypeAnnotations

# === lazysodium-java + JNA ===
# lazysodium uses JNA to call into native libsodium. JNA reflects on
# interface methods to construct native call stubs at runtime — R8 would
# strip them as "unused".
-keep class com.goterl.lazysodium.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.goterl.lazysodium.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-dontwarn com.goterl.lazysodium.**
-dontwarn com.sun.jna.**

# === kotlinx.serialization ===
# Generated serializers are looked up via reflection on companion objects.
-keepclassmembers class kotlinx.serialization.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Tote's @Serializable types in com.tote.app.crypto.* (e.g., SignedMembershipList)
-keep,allowobfuscation,allowshrinking @kotlinx.serialization.Serializable class com.tote.app.**
-keepclassmembers @kotlinx.serialization.Serializable class com.tote.app.** {
    <fields>;
}

# === OkHttp + Okio ===
# OkHttp uses reflection to find platform-specific connection providers
# (Conscrypt, BoringSSL, etc.) and to load the API via the ServiceLoader.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.conscrypt.** { *; }

# === CameraX (JNA + reflection on platform camera2 wrappers) ===
-dontwarn androidx.camera.core.**
-dontwarn androidx.camera.camera2.**
-keep class androidx.camera.** { *; }

# === ZXing (uses reflection in some barcode decoders) ===
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# === org.json (Android stub; needs the real implementation at runtime) ===
-keep class org.json.** { *; }
-dontwarn org.json.**

# === Compose runtime (uses a lot of reflection) ===
# These are already covered by the compose BOM's consumer rules, but
# duplicating here is safe + explicit.
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# === Material3 + Material icons ===
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# === AndroidX WebKit (Profile API uses reflection on Android 10+) ===
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**

# === Kotlin coroutines (invokeOnCompletion etc. use reflection) ===
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# === Tote's own crypto classes (Parcelable + reflection-based APIs) ===
# Keep all data classes that participate in serialization.
-keep class com.tote.app.crypto.DeviceIdentity { *; }
-keep class com.tote.app.crypto.EpochState { *; }
-keep class com.tote.app.pairing.PairingPayload { *; }
-keep class com.tote.app.session.PeerDeviceRegistry$Peer { *; }
-keep class com.tote.app.relay.PeerRecord { *; }
-keep class com.tote.app.relay.InboxEntry { *; }
-keep class com.tote.app.relay.RelayConfig { *; }

# === Suppress R8 warnings about missing referenced classes ===
# (some referenced libs aren't on the runtime classpath, e.g., BoringSSL)
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
