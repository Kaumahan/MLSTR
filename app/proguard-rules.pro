# kotlinx.serialization: keep generated serializers for our cached models.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.mlbb.counteroverlay.**$$serializer { *; }
-keepclassmembers class com.mlbb.counteroverlay.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Retrofit interface (suspend functions returning Response<ResponseBody>).
-keep,allowobfuscation interface com.mlbb.counteroverlay.data.remote.MlbbApi
-keepattributes Signature, Exceptions
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
