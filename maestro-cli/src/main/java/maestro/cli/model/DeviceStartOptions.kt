package maestro.cli.model

import maestro.cli.device.Platform

data class DeviceStartOptions(
    val platform: Platform,
    val osVersion: Int?,
    val forceCreate: Boolean
)