plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

description = "Tranthor push notifications for Android: FCM token registration, notification display and open and dismiss reporting."
apply(from = rootProject.file("gradle/publish.gradle.kts"))

android {
    namespace = "com.tranthor.push"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("test").kotlin.directories.add(rootProject.file("test-support/kotlin").path)
    }

    testOptions {
        unitTests.all { test ->
            test.testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }

    lint {
        abortOnError = true
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("com.tranthor.sdk.InternalTranthorApi")
    }
}

dependencies {
    // TranthorPush can be bound to the core's TranthorSDK, which is part of this module's public API.
    api(project(":tranthor-sdk"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.process)
    // The host app owns Firebase (version, google-services config, initialization).
    compileOnly(platform(libs.firebase.bom))
    compileOnly(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

