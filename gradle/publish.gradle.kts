// Maven publishing for the library modules. The artifact ID is the module name and the version is
// VERSION_NAME in gradle.properties. Signing and remote repositories are not configured here.
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

group = "com.tranthor"
version = property("VERSION_NAME") as String

val repositoryUrl = "https://github.com/tranthor-main/sdk-android"

afterEvaluate {
    extensions.configure<PublishingExtension>("publishing") {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                artifactId = project.name
                pom {
                    name.set(project.name)
                    description.set(project.description)
                    url.set(repositoryUrl)
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer { name.set("Tranthor") }
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
}
