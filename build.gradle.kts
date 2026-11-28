plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.maven.publish) apply false
}

apiValidation {
    ignoredClasses += "com.tranthor.sdk.BuildConfig"
    // The sample app is not a published API surface.
    ignoredProjects += listOf("example")
}

// Maven Central publishing for the library modules. The artifact ID is the module name and the
// version is VERSION_NAME in gradle.properties. Artifacts are signed only when a signing key is
// supplied, so publishToMavenLocal works without one.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            val repositoryUrl = "https://github.com/tranthor-main/sdk-android"
            coordinates("com.tranthor", project.name, property("VERSION_NAME") as String)
            publishToMavenCentral()
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
            pom {
                name.set(project.name)
                description.set(provider { project.description })
                url.set(repositoryUrl)
                licenses {
                    license {
                        name.set("Tranthor SDK License")
                        url.set("$repositoryUrl/blob/main/LICENSE")
                    }
                }
                developers {
                    developer {
                        name.set("Tranthor")
                        url.set("https://tranthor.com")
                    }
                }
                scm {
                    url.set(repositoryUrl)
                    connection.set("scm:git:$repositoryUrl.git")
                    developerConnection.set("scm:git:ssh://git@github.com/tranthor-main/sdk-android.git")
                }
            }
        }
    }
}
