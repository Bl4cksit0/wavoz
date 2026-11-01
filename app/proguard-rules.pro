# --- Vosk usa JNA: sus clases se resuelven por reflexión desde código nativo ---
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# --- Para que los errores en el registro sigan siendo legibles ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
