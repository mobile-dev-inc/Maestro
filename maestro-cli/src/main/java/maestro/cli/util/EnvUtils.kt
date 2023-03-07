package maestro.cli.util

object EnvUtils {

    fun androidHome(): String? {
        return System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: System.getenv("ANDROID_SDK_HOME")
            ?: System.getenv("ANDROID_SDK")
            ?: System.getenv("ANDROID")
    }

    fun maestroCloudApiKey(): String? {
        return System.getenv("MAESTRO_CLOUD_API_KEY")
    }
}