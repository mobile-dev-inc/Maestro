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
        // Local-first for device-core: a locally published `dev.mobile.devicecore:*:*-SNAPSHOT` in
        // ~/.m2 wins over GitHub Packages, so you can iterate on maestro-device-core in lockstep
        // (edit there -> `./gradlew publishToMavenLocal` -> rebuild here) without pushing a package.
        // When ~/.m2 carries no device-core artifact, resolution falls through to GitHub Packages
        // below. Scoped so mavenLocal only ever services device-core coordinates — every other
        // dependency stays on google()/mavenCentral(). Re-publish after each device-core edit: a
        // SNAPSHOT in ~/.m2 is a fixed filename, so a stale local jar silently shadows the remote.
        mavenLocal { content { includeGroup("dev.mobile.devicecore") } }
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
