# Keep the main activity and any other critical classes
-keep class radzdev.updater.Main { *; }

# Keep the package for the FileProvider
-keep class androidx.core.content.FileProvider { *; }

# Keep classes related to Gson (if you're using Gson for JSON parsing)
-keep class com.google.gson.** { *; }

# Keep classes related to OkHttp (if you're using OkHttp for networking)
-keep class com.squareup.okhttp3.** { *; }

# Keep the classes for AndroidX libraries
-keep class androidx.** { *; }

# Keep Compose-related classes
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep the classes for ViewBinding
-keep class androidx.viewbinding.** { *; }

# If you're using Reflection, make sure to keep these classes
-keep class * extends java.lang.reflect.InvocationHandler { *; }

# If you have any custom annotations, keep those
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# If you use external libraries that require special handling, add rules here

# General recommendations
-dontwarn okhttp3.**
-dontwarn com.squareup.okhttp3.**
-dontwarn androidx.lifecycle.**
-dontwarn androidx.activity.**
