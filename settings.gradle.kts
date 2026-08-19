rootProject.name = "maestro"

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
        // device-core is published only to the local Maven repo.
        mavenLocal { content { includeGroup("dev.mobile.devicecore") } }
    }
}


include("maestro-utils")
include("maestro-android")
include("maestro-cli")
include("maestro-client")
include("maestro-ios")
include("maestro-ios-driver")
include("maestro-orchestra")
include("maestro-orchestra-models")
include("maestro-orchestra-proto")
include("maestro-proto")
include("maestro-test")
include("maestro-ai")
include("maestro-web")
include(":maestro-client")
include(":maestro-driver-ios")
include(":maestro-orchestra")
include(":maestro-test")
include(":maestro-xcuitest-driver")
