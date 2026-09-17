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
    }
}

rootProject.name = "MoaLog"

include(":composeApp")
include(":shared")
include(":app-shell")
include(":core:model")
include(":core:contracts")
include(":core:designsystem")
include(":core:network")
include(":core:database")
include(":feature:home")
include(":feature:plan")
include(":feature:records")
include(":feature:assets")
include(":feature:maintenance")
include(":feature:setup")
include(":server")
