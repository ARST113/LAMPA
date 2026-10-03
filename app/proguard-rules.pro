# Protect the native/Java boundary while allowing unused Java code to be removed.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
-keep class com.cefrium.** { *; }
-keep class org.jni_zero.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.jni_zero.CalledByNative <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.jni_zero.CalledByNativeUnchecked <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.chromium.base.annotations.CalledByNative <methods>;
}
-keepclassmembers class top.rootu.lampa.AndroidJS { public *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keep class org.chromium.**.R { *; }
-keep class org.chromium.**.R$* { *; }
-keep class gen.**.R { *; }
-keep class gen.**.R$* { *; }
-keep class com.google.android.gms.common.R$* { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn org.xwalk.core.**
-dontwarn j$.util.**

# SDK fat-AAR optional integrations are not instantiated by Lampa.
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**

# Missing optional Chrome UI resources in the fat SDK; never generate fake R IDs.
-dontwarn org.chromium.chrome.browser.**.R
-dontwarn org.chromium.chrome.browser.**.R$*
-dontwarn com.google.android.gms.base.R$*
-dontwarn com.google.android.gms.cast.framework.R$*
-dontwarn com.google.ar.core.R$*
-dontwarn org.chromium.chrome.browser.ProductConfig
-dontwarn com.google.android.material.shape.ShapeAppearance

# The SDK contains only a whitelist of generated Chromium R packages.
-dontwarn org.chromium.**.R
-dontwarn org.chromium.**.R$*

