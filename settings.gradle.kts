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
    }
}

// Configure Source Control for forked kotlin-sdk
sourceControl {
    gitRepository(uri("https://github.com/steviec/kotlin-sdk.git")) {
        producesModule("io.modelcontextprotocol:kotlin-sdk")
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
include("maestro-studio:server")
include("maestro-studio:web")
include("maestro-test")
include("maestro-ai")
include("maestro-web")
include(":maestro-client")
include(":maestro-driver-ios")
include(":maestro-orchestra")
include(":maestro-studio")
include(":maestro-test")
include(":maestro-xcuitest-driver")
