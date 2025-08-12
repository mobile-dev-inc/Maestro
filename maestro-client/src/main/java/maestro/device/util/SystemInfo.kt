package maestro.device.util

object SystemInfo {
    fun getJavaVersion(): Int {
        // Adapted from https://stackoverflow.com/a/2591122/7009800
        val version = System.getProperty("java.version")
        return if (version.startsWith("1.")) {
            version.substring(2, 3).toInt()
        } else {
            val dot = version.indexOf(".")
            if (dot != -1) version.substring(0, dot).toInt() else 0
        }
    }
}

