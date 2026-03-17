pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "android-pos"

include(
    ":app",
    ":core:common",
    ":core:ui",
    ":core:network",
    ":core:database",
    ":core:datastore",
    ":core:model",
    ":domain",
    ":feature:auth",
    ":feature:tablemap",
    ":feature:ticket",
    ":feature:menu",
    ":feature:kitchen",
    ":feature:payment",
    ":feature:shift",
    ":feature:scanner",
    ":feature:camera",
    ":feature:settings",
    ":device:printer",
    ":device:scanner",
    ":device:camera",
    ":device:cashdrawer",
    ":device:platform",
    ":sync",
)
