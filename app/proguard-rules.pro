# ── GPS Tracker - ProGuard Rules ──────────────────────────────────────────────

# Manter todas as classes do app (evita quebrar funcionalidades)
-keep class com.gpstracker.app.** { *; }

# SSL bypass - manter TrustManager e X509TrustManager
-keep class javax.net.ssl.** { *; }
-keep class java.security.** { *; }
-dontwarn javax.net.ssl.**

# Google Play Services Location
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# JSON
-keepclassmembers class * {
    @org.json.JSONField *;
}

# Manter informações de debug para stack traces legíveis
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
