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

rootProject.name = "heimdall"

include(
    ":app",
    ":core:model",
    ":core:common",
    ":core:testing",
    ":engine:gate",
    ":engine:trigger",
    ":engine:vlm",
    ":engine:governor",
    ":ui:overlay",
    ":acceptance",
    ":benchmark",
)
