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
        maven {
            name = "DeviceCoreGitHubPackages"
            url = uri("https://maven.pkg.github.com/mobile-dev-inc/maestro-device-core")
            credentials {
                username = (providers.gradleProperty("gpr.user").orNull) ?: System.getenv("GPR_USER")
                password = (providers.gradleProperty("gpr.read.token").orNull) ?: System.getenv("GPR_READ_TOKEN")
            }
            // Scope it: only device-core coordinates resolve from the authenticated repo, so every
            // other dependency stays on google()/mavenCentral().
            content { includeGroup("dev.mobile.devicecore") }
        }
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
