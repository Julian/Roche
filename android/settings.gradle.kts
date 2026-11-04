pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal() // spotless is only published here
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Roche"

include(":app")

include(":baselineprofile")
