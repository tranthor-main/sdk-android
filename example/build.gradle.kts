import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Optional local Firebase options (never committed): tranthor.firebase.* in local.properties.
// Without them the example runs with FCM disabled; local notification previews still work.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun localString(key: String): String = "\"" + (localProperties.getProperty(key) ?: "").replace("\"", "") + "\""

android {
    namespace = "com.tranthor.example"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.tranthor.example"
        // firebase-messaging 26 requires API 24; the libraries themselves support API 23.
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FIREBASE_APPLICATION_ID", localString("tranthor.firebase.applicationId"))
        buildConfigField("String", "FIREBASE_API_KEY", localString("tranthor.firebase.apiKey"))
        buildConfigField("String", "FIREBASE_PROJECT_ID", localString("tranthor.firebase.projectId"))
        buildConfigField("String", "FIREBASE_SENDER_ID", localString("tranthor.firebase.senderId"))
        // Exact HTTPS CDN hostnames for remote rich images, comma separated; empty rejects all.
        buildConfigField("String", "IMAGE_HOSTS", localString("tranthor.example.imageHosts"))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    implementation(project(":tranthor-sdk"))
    implementation(project(":tranthor-push"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.compose.ui.test.manifest)
}
