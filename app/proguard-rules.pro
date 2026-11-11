# OpenCV is called from native code through JNI, so its Java classes must not be renamed or removed.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
