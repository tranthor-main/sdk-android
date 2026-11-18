plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    ignoredClasses += "com.tranthor.sdk.BuildConfig"
    // The sample app is not a published API surface.
    ignoredProjects += listOf("example")
}
