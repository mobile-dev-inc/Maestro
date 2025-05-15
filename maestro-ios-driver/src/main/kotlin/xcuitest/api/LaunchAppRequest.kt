package xcuitest.api

import java.util.*

data class LaunchAppRequest(val appId: String, val launchArguments: Map<String, Any>)
