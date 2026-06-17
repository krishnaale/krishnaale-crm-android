# Keep Firebase Messaging service entry points.
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Keep our classes referenced from the manifest (Application + FCM service).
-keep class au.krishnaale.crm.CrmApplication { *; }
-keep class au.krishnaale.crm.CrmMessagingService { *; }

# AndroidX security-crypto / Tink.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Keep the JavascriptInterface used for blob downloads.
-keep class au.krishnaale.crm.DownloadBridge { *; }
-keepclassmembers class au.krishnaale.crm.DownloadBridge {
   @android.webkit.JavascriptInterface <methods>;
}
