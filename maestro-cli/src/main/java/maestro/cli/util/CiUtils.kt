package maestro.cli.util

object CiUtils {
    private val ciEnvVarMap = mapOf(
        "JENKINS_HOME" to "jenkins",
        "BITRISE_IO" to "bitrise",
        "CIRCLECI" to "circleci",
        "GITLAB_CI" to "gitlab",
        "GITHUB_ACTIONS" to "github",
        "BITBUCKET_BUILD_NUMBER" to "bitbucket",
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

        for (ciVar in ciEnvVarMap.entries) {
            try {
                if (isTruthy(System.getenv(ciVar.key).lowercase())) return ciVar.value
            } catch (e: Exception) {}
        }

        return null
    }
}