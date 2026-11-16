# Keep kotlinx.serialization companions for API & Room models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Preserve Navigation 3 NavKey class names serialized into SavedState Bundles
-keepnames class * implements androidx.navigation3.runtime.NavKey

# Preserve Roche enum class & constant names serialized into SavedState / rememberSaveable Bundles
-keepnames enum com.grayvines.roche.** { *; }
