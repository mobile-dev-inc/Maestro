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
include("maestro-cli")
include("maestro-client")
include("maestro-orchestra")
include("maestro-orchestra-models")
include("maestro-proto")
include("maestro-test")
include("maestro-ai")
