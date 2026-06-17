# =============================================================================
# ParentalControl ProGuard Rules (T23)
# =============================================================================
#
# Goals:
# - Obfuscate app code to hinder repackaging
# - Preserve reflection needed by Tink, Ktor, Room, Kotlin Serialization
# - Keep essential class names for runtime verification
# - Anti-debug basics (not as sole defense)
#
# §0.9: R8 is one layer of defense, not the only one
# =============================================================================

# ===== KEEP ANNOTATIONS =====

-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ===== KOTLIN =====

-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }

# Kotlin Serialization — required for reflection
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}

-keep,includedescriptorclasses class com.example.parentalcontrol.**$$serializer { *; }
-keepclassmembers class com.example.parentalcontrol.** {
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ===== TINK =====

-keep class com.google.crypto.tink.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.protobuf.**

# Tink Aead interface must not be obfuscated
-keep interface com.google.crypto.tink.Aead { *; }
-keep class com.google.crypto.tink.KeysetHandle { *; }

# ===== KTOR =====

-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class io.netty.** { *; }
-dontwarn io.netty.**
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# Ktor WebSocket
-keep class io.ktor.websocket.** { *; }

# Ktor ContentNegotiation
-keep class io.ktor.serialization.** { *; }

# ===== ROOM =====

-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

-keep class * extends androidx.room.RoomDatabase {
    abstract <methods>;
}

# Room Dao
-keep @androidx.room.Dao interface *

# Room TypeConverters
-keep @androidx.room.TypeConverters class *

# ===== DATASTORE =====

-keep class androidx.datastore.** { *; }
-keep class androidx.datastore.preferences.** { *; }

# ===== WORKMANAGER / HILT =====

-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker

-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }

# ===== FIREBASE / FCM =====

-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ===== PLAY INTEGRITY =====

-keep class com.google.android.play.core.integrity.** { *; }
-dontwarn com.google.android.play.core.integrity.**

# ===== NETWORK (OkHttp + Certificate Pinning) =====

-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ===== REFLECTION PRESERVATION =====

# Preserve classes used via reflection by frameworks
-keep class com.example.parentalcontrol.data.local.** { *; }
-keep class com.example.parentalcontrol.auth.** { *; }
-keep class com.example.parentalcontrol.sync.** { *; }
-keep class com.example.parentalcontrol.health.** { *; }
-keep class com.example.parentalcontrol.enforcement.** { *; }

# ===== MAINTAIN CLASS NAMES (for tampered APK detection) =====

# Keep entry points — repackaging changes these signatures
-keep class com.example.parentalcontrol.MainActivity { *; }
-keep class com.example.parentalcontrol.ParentalControlApp { *; }
-keep class com.example.parentalcontrol.accessibility.ForegroundAppService { *; }
-keep class com.example.parentalcontrol.service.UsageTrackingService { *; }
-keep class com.example.parentalcontrol.overlay.BlockOverlayService { *; }

# ===== ANTI-DEBUG BASICS =====

# Detect debugger (not as sole defense)
-keep class com.example.parentalcontrol.security.TamperDetector { *; }

# ===== ENCRYPTION / SECURITY =====

# Keep cryptographic classes
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-keep class android.security.keystore.** { *; }

# ===== OBfuscation =====

# Rename classes to short names (reduces APK size + hinders reverse engineering)
-repackageclasses ''
-allowaccessmodification
-overloadaggressively

# ===== STRIP DEBUG INFO =====

# Remove debug info but keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ===== CRASH REPORTING =====

# Keep exception stack traces readable
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations
