# Keep protobuf-lite generated classes used by the Android TV protocol.
-keep class remote.** { *; }
-keep class com.google.polo.** { *; }
-keep class com.google.protobuf.** { *; }
# BouncyCastle registers providers reflectively.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
