// Teal — settings. Every repository lives here (FAIL_ON_PROJECT_REPOS) so no
// module can quietly add its own and change resolution order.
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

rootProject.name = "Teal"
include(":app")
