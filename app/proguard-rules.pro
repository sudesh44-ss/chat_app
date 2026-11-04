# NEXA Production ProGuard / R8 Consumer Rules

# Keep WebRTC Native JNI bindings
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep SQLCipher Native JNI and SQLite bindings
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.**

# Keep Bouncy Castle Providers and Algorithms
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep Google Tink Crypto
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Keep Room SQLite Entities and DAOs
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.nexa.app.data.local.entity.** { *; }

# Keep Kotlinx Serialization & NEXA Protobuf Wire Models
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
    @kotlinx.serialization.Serializable <fields>;
}
-keep class com.nexa.app.core.proto.** { *; }
-keep class com.nexa.app.data.remote.signaling.** { *; }
-keep class kotlinx.serialization.** { *; }

# Keep AndroidX Credentials and Google ID
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class com.google.android.gms.auth.api.signin.** { *; }
-dontwarn androidx.credentials.**

# Keep Media3 Transformer & Codecs
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep OkHttp & Okio
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
