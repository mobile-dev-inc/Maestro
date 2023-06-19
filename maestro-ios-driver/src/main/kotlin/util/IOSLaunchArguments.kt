package util

object IOSLaunchArguments {

    fun Map<String, Any>.toIOSLaunchArguments(): List<String> {
        if (isEmpty()) return emptyList()

        val iOSLaunchArgumentsMap = mutableMapOf<String, Any>()
        forEach { (key, value) ->
            if (value is Boolean) {
                iOSLaunchArgumentsMap[key] = value
            } else {
                if (!key.startsWith("-")) {
                    iOSLaunchArgumentsMap["-$key"] = value
                } else {
                    iOSLaunchArgumentsMap[key] = value
                }
            }
        }
        return iOSLaunchArgumentsMap.toList().flatMap { listOf(it.first, it.second.toString()) }
    }
}