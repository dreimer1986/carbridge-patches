# Loaded by name from the splitscreen extension
-keep class org.carbridge.extension.splitscreen.payload.** { *; }

# The Car App Library serializes models by class and field name
-dontobfuscate
-keep class androidx.car.app.model.** { *; }
-keep class androidx.car.app.navigation.model.** { *; }
-keep class androidx.car.app.serialization.** { *; }

-dontwarn **
