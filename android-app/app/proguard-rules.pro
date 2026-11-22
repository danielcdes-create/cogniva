# Cogniva Android R8 / ProGuard rules (Kotlinx Serialization + Retrofit + Room + WorkManager + OkHttp)

-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, SourceFile, LineNumberTable
-keep class kotlin.Metadata { *; }

# Kotlinx Serialization
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep class kotlinx.serialization.json.** { *; }
-keep class kotlinx.serialization.internal.** { *; }
-keep,includedescriptorclasses class br.com.concursoprep.app.**$$serializer { *; }
-keep @kotlinx.serialization.Serializable class br.com.concursoprep.app.** { *; }
-keepclassmembers class br.com.concursoprep.app.** {
    *** Companion;
}
-keepclasseswithmembers class br.com.concursoprep.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers enum br.com.concursoprep.app.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepnames class <2>$$serializer
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Explicit keep for @Keep-annotated DTOs (e.g. DeleteAccountRequest)
-keep @androidx.annotation.Keep class * { *; }

# Retrofit 2
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-keep interface br.com.concursoprep.app.data.remote.ConcursoApiService { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# Room entities, DAOs & generated implementations
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class **.*_Impl { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# WorkManager Workers instantiated via reflection
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
