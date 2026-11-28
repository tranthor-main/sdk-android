plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

description = "Tranthor events SDK for Android: identify, track and page calls with durable, offline-tolerant delivery."

android {
    namespace = "com.tranthor.sdk"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        // AndroidX lifecycle-process 2.10 (ProcessLifecycleOwner) requires API 23.
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "VERSION_NAME", "\"${property("VERSION_NAME")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // One runner implementation shared by the JVM host test and the instrumented test.
        getByName("test").kotlin.directories.add("src/conformance/kotlin")
        getByName("androidTest").kotlin.directories.add("src/conformance/kotlin")
        getByName("test").kotlin.directories.add(rootProject.file("test-support/kotlin").path)
        // The vendored suite in conformance/ is the single copy used by both runners.
        getByName("test").resources.directories.add(rootProject.file("conformance").path)
        getByName("androidTest").assets.directories.add(rootProject.file("conformance").path)
    }

    testOptions {
        unitTests.all { test ->
            test.maxHeapSize = "1g"
            test.testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = false }
            // JVM conformance runs only when requested (scripts/test-host.sh).
            val conformance = project.hasProperty("tranthor.conformance")
            if (conformance) {
                test.filter.includeTestsMatching("com.tranthor.sdk.conformance.JvmConformanceTest")
                test.outputs.upToDateWhen { false }
            } else {
                test.filter.excludeTestsMatching("com.tranthor.sdk.conformance.JvmConformanceTest")
            }
            listOf("CONFORMANCE_HOST", "CONFORMANCE_ALLOW_SKIP", "CONFORMANCE_SCENARIO", "CONFORMANCE_RESULTS").forEach { key ->
                System.getenv(key)?.let { test.environment(key, it) }
            }
        }
    }


    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("com.tranthor.sdk.InternalTranthorApi")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // End-to-end push tests drive tranthor-push's public API against this core (push JVM tests cannot build a TranthorSDK).
    testImplementation(project(":tranthor-push"))

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}

