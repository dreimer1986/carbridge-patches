# The classes are merged into another app's dex: renamed classes would collide with its own
-dontobfuscate
-dontoptimize

# Entry points are declared by the patch, or loaded by name from the payload
-keep class org.carbridge.extension.splitscreen.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

-dontwarn **
