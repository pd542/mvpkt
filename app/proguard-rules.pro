# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-dontobfuscate
-keep,allowoptimization class is.xyz.mpv.** { public protected *; }

# Keep multi-connection proxy class + stack traces for field debugging.
-keep class live.mehiz.mpvkt.network.SegmentedHttpCache { *; }
-keepattributes SourceFile,LineNumberTable
# Log.e / Log.w are not stripped by proguard-android-optimize; SegmentedHttpCache
# and PlayerActivity use Log.e so release APKs still emit segmented diagnostics.
