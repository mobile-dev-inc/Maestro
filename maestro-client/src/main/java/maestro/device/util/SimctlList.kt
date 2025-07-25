package maestro.device.util

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class SimctlList(
    val devicetypes: List<DeviceType>,
    val runtimes: List<Runtime>,
    val devices: Map<String, List<Device>>,
    val pairs: Map<String, Pair>,
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DeviceType(
        val identifier: String,
        val name: String,
        val bundlePath: String,
        val productFamily: String,
        val maxRuntimeVersion: Long?,
        val maxRuntimeVersionString: String?,
        val minRuntimeVersion: Long?,
        val minRuntimeVersionString: String?,
        val modelIdentifier: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Runtime(
        val bundlePath: String,
        val buildversion: String,
        val platform: String?,
        val runtimeRoot: String,
        val identifier: String,
        val version: String,
        val isInternal: Boolean,
        val isAvailable: Boolean,
        val name: String,
        val supportedDeviceTypes: List<DeviceType>,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Device(
        val name: String,
        val dataPath: String?,
        val logPath: String?,
        val udid: String,
        val isAvailable: Boolean,
        val deviceTypeIdentifier: String?,
        val state: String,
        val availabilityError: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Pair(
        val watch: Device,
        val phone: Device,
        val state: String,
    )
}
