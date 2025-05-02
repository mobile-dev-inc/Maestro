package util

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

enum class IOSDeviceType {
    REAL,
    SIMULATOR
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceCtlResponse(
    val result: Result
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Result(
        val devices: List<Device>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Device(
        val identifier: String,
        val deviceProperties: DeviceProperties,
        val hardwareProperties: HardwareProperties
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DeviceProperties(
        val name: String,
        val osVersionNumber: String,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HardwareProperties(
        val udid: String
    )
}
