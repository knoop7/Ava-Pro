pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        // GeckoView (only used by the `gecko` product flavor).
        maven(url = "https://maven.mozilla.org/maven2/")
    }
}

rootProject.name = "Ava"
include(":app")
include(":esphomeproto")
include(":microfeatures")
