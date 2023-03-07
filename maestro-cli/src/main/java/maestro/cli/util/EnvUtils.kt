package maestro.cli.util

object EnvUtils {

    fun androidHome(): String? {
        return System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: System.getenv("ANDROID_SDK_HOME")
            ?: System.getenv("ANDROID_SDK")
            ?: System.getenv("ANDROID")
    }

    fun mdevApiKey(): String? {
        return System.getenv("MDEV_API_KEY")
    }
}