package util

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

enum class IOSDeviceType {
    REAL,
    SIMULATOR
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class XCDevice(
    val modelCode: String,
    val simulator: Boolean,
    val modelName: String,
    val identifier: String,
    val platform: String,
    val name: String,
    val operatingSystemVersion: String,
    val modelUTI: String,
    val architecture: String,
) {
    companion object {
        const val IPHONE_PLATFORM = "com.apple.platform.iphoneos"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceCtlResponse(
    val info: Info,
    val result: Result
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Info(
        val arguments: List<String>,
        val commandType: String,
        val environment: Map<String, String>,
        val jsonVersion: Int,
        val outcome: String,
        val version: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Result(
        val devices: List<Device>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Device(
        val identifier: String,
        val capabilities: List<Capability>,
        val connectionProperties: ConnectionProperties,
        val deviceProperties: DeviceProperties,
        val hardwareProperties: HardwareProperties,
        val tags: List<String>,
        val visibilityClass: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Capability(
        val featureIdentifier: String,
        val name: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ConnectionProperties(
        val authenticationType: String,
        val isMobileDeviceOnly: Boolean,
        val lastConnectionDate: String,
        val pairingState: String,
        val potentialHostnames: List<String>,
        val transportType: String,
        val tunnelState: String,
        val tunnelTransportProtocol: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DeviceProperties(
        val bootedFromSnapshot: Boolean,
        val bootedSnapshotName: String,
        val ddiServicesAvailable: Boolean,
        val developerModeStatus: String,
        val hasInternalOSBuild: Boolean,
        val name: String,
        val osBuildUpdate: String,
        val osVersionNumber: String,
        val rootFileSystemIsWritable: Boolean
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HardwareProperties(
        val cpuType: CpuType,
        val deviceType: String,
        val ecid: Long,
        val hardwareModel: String,
        val internalStorageCapacity: Long,
        val isProductionFused: Boolean,
        val marketingName: String,
        val platform: String,
        val productType: String,
        val reality: String,
        val serialNumber: String,
        val supportedCPUTypes: List<CpuType>,
        val supportedDeviceFamilies: List<Int>,
        val thinningProductType: String,
        val udid: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CpuType(
        val name: String,
        val subType: Int,
        val type: Int
    )
}
