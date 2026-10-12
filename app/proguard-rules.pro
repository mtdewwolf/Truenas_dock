# Keep kotlinx.serialization models used by truenas-api.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keep class app.truenasdock.api.model.** { *; }
-keepclasseswithmembers class app.truenasdock.api.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep OkHttp / Okio (consumed via truenas-api).
-dontwarn okhttp3.**
-dontwarn okio.**
