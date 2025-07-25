package maestro.cli.util

object CiUtils {

    // When adding a new CI, also add the first version of Maestro that supports it.
    private val ciEnvVarMap = mapOf(
        "APPVEYOR" to "appveyor", // since v1.37.4
        "BITBUCKET_BUILD_NUMBER" to "bitbucket",
        "BITRISE_IO" to "bitrise",
        "BUILDKITE" to "buildkite", // since v1.37.4
        "CIRCLECI" to "circleci",
        "CIRRUS_CI" to "cirrusci", // since v1.37.4
        "DRONE" to "drone", // since v1.37.4
        "GITHUB_ACTIONS" to "github",
        "GITLAB_CI" to "gitlab",
        "JENKINS_HOME" to "jenkins",
        "TEAMCITY_VERSION" to "teamcity", // since v1.37.4
        "CI" to "ci"
    )

    private fun isTruthy(envVar: String?): Boolean {
        if (envVar == null) return false
        return envVar != "0" && envVar != "false"
    }

    fun getCiProvider(): String? {
        val mdevCiEnvVar = System.getenv("MDEV_CI")
        if (isTruthy(mdevCiEnvVar)) {
            return mdevCiEnvVar
        }

        for (ciEnvVar in ciEnvVarMap.entries) {
            try {
                if (isTruthy(System.getenv(ciEnvVar.key).lowercase())) return ciEnvVar.value
            } catch (e: Exception) {
                // We don't care
            }
        }

        return null
    }
}
