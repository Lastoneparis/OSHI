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

rootProject.name = "OSHI"
include(":app")
// __WEAR_BRIDGE_2026_11_17__ Wear OS companion (same applicationId, ships in the same Play listing).
include(":wear")
