# ---------------------------------------------------------------------------
# Tote — ProGuard / R8 rules.
# ---------------------------------------------------------------------------
# Minify is OFF for the alpha (app/build.gradle.kts: isMinifyEnabled = false),
# so this file has no effect yet. Rules below are pre-staged for the beta / v1
# release where minify will be enabled. They are inert while minify is off.
# ---------------------------------------------------------------------------

# lazysodium-java + JNA reach into native code via reflection.
-keep class com.goterl.lazysodium.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.goterl.lazysodium.**
-dontwarn com.sun.jna.**

# kotlinx.serialization (pre-staged — no @Serializable types exist in the alpha).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
