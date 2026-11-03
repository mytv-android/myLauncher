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
        google()
        mavenCentral()
    }
}

rootProject.name = "myLauncher"

include(":app")
include(":core:designsystem")
include(":core:tvfocus")
include(":core:glass")
include(":data:apps")
include(":data:channels")
include(":feature:home")
include(":feature:apps")
include(":feature:settings")
