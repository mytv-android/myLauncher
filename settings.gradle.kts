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
include(":core:dynamiccolor")
include(":core:ambient")
include(":data:apps")
include(":data:channels")
include(":data:series")
include(":data:widgets")
include(":data:weather")
include(":data:media")
include(":data:files")
include(":data:restricted")
include(":core:diag")
include(":core:install")
include(":feature:home")
include(":feature:apps")
include(":feature:settings")
include(":feature:quicksettings")
include(":feature:widgets")
include(":feature:phone-organizer")
include(":feature:viewer")
include(":opt:shizuku")
