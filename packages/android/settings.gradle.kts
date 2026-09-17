pluginManagement {
    repositories {
        // AGP is published on Google Maven; omitting it here fails with a misleading error.
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "trindade-android"
include(":app")
