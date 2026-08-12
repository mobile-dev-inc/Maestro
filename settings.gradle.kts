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
        // Local-first for device-core: a locally published `dev.mobile.devicecore:*` in ~/.m2 wins
        // over GitHub Packages, so you can iterate on maestro-device-core in lockstep (edit there ->
        // `./gradlew publishToMavenLocal` -> bump the pinned sha in maestro-orchestra/build.gradle.kts
        // -> rebuild here) without pushing a package. When ~/.m2 carries no device-core artifact,
        // resolution falls through to GitHub Packages below. Scoped so mavenLocal only ever services
        // device-core coordinates — every other dependency stays on google()/mavenCentral(). device-core
        // versions are commit-addressed (`0.1.0-<git sha>`, device-core #138), so each build names one
        // exact device-core jar rather than a floating -SNAPSHOT that could silently shadow the remote.
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
