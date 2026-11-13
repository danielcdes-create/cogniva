# Cogniva Android R8 / ProGuard rules (Kotlinx Serialization + Retrofit + Room + OkHttp)

-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature

# Kotlinx Serialization
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class br.com.concursoprep.app.**$$serializer { *; }
-keepclassmembers class br.com.concursoprep.app.** {
    *** Companion;
}
-keepclasseswithmembers class br.com.concursoprep.app.** {
    kotlinx.serialization.KSerializer serializer(...);
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

# Room entities & DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
