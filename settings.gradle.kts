val coreOnly = providers.gradleProperty("coreOnly").isPresent

pluginManagement {
    repositories {
        // Skipped with -PcoreOnly, for sandboxes that can't reach Google's Maven.
        if (!providers.gradleProperty("coreOnly").isPresent) google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!coreOnly) google()
        mavenCentral()
    }
}
rootProject.name = "narrateme"
include(":core")
// The Android app needs the Android SDK; skip it with -PcoreOnly where the SDK isn't available.
if (!coreOnly) {
    include(":app")
}
