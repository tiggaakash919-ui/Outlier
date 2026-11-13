# Outlier Radar R8/ProGuard rules

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable,Signature,Exceptions,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-renamesourcefileattribute SourceFile

# Kotlin metadata / reflection (Moshi KotlinJsonAdapterFactory, etc.)
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { *; }

# Moshi: keep JSON model classes and adapters (reflection-based)
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}

# App/domain/data model classes serialized via Moshi/Room/Retrofit
-keep class com.outlierradar.app.** { *; }
-keepclassmembers class com.outlierradar.app.** { *; }
-keep class com.outlierradar.app.** { *; }
-keepclassmembers class com.outlierradar.app.** { *; }

# Retrofit interfaces and annotations
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keep interface retrofit2.** { *; }
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# Room
-keep class androidx.room.** { *; }
-keep @androidx.room.* class * { *; }
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Database class * { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# WorkManager workers and factory
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker
-keep class * extends androidx.work.WorkerFactory
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# Enums (used by name in Room/JSON/UI)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable/Serializable if present
-keepclassmembers class * implements android.os.Parcelable { public static final ** CREATOR; }
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Firebase / Google (defensive)
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Coil / Compose runtime (generally safe, keep common reflective bits)
-dontwarn coil.**
-keep class coil.** { *; }
