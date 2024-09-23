import java.net.URI

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()

    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url= URI("https://transmit.jfrog.io/artifactory/transmit-security-gradle-release-local/")
        }
        google()
        mavenCentral()

    }
}

rootProject.name = "Passkeys demo App"
include(":app")
 