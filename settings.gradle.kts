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

rootProject.name = "reeltracker"

include(":core-model")   // pure Kotlin
include(":core-detect")  // pure Kotlin, JVM tests
include(":core-data")    // Room, ReelRepository
include(":core-sync")    // Supabase: anonymous auth, friends, live counts
include(":service")      // ReelAccessibilityService, OverlayController
include(":app")          // Compose UI

// M0 forensic logger. Kept for when Instagram changes and SignalRecorder isn't enough.
include(":spike")
