# ============================================
# Androidx Work Database (Room)
# ============================================
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}

-keep class androidx.work.impl.WorkDatabase {
    public <init>();
}

# ============================================
# Epher Core Classes (must keep for reflection)
# ============================================
-keep class com.epher.app.** { *; }

# ============================================
# Security & Cryptography Libraries
# ============================================
# Google Tink
-keep class com.google.crypto.tink.** { *; }

# BouncyCastle (preserve crypto implementations)
-keep class org.bouncycastle.** { *; }

# ============================================
# JNI & Native Code
# ============================================
# BareKit (Hyperswarm Bare runtime)
-keep class com.holepunch.** { *; }
-keep class to.holepunch.** { *; }
-keep class to.holepunch.bare.kit.** { *; }
-keepnames class to.holepunch.** { *; }
-dontwarn com.google.firebase.messaging.**
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================
# Serialization & JSON
# ============================================
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    *** Companion;
}

# ============================================
# Remove logging in release builds
# ============================================
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ============================================
# Optimize method/field inlining
# ============================================
-optimizationpasses 5
-allowaccessmodification
-dontpreverify
