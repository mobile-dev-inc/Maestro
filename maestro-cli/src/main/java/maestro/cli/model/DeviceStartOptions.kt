package maestro.cli.model

import maestro.device.Platform

data class DeviceStartOptions(
    val platform: Platform,
    val osVersion: Int?,
    val forceCreate: Boolean
)