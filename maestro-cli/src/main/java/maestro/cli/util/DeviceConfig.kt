package maestro.cli.util

import maestro.device.util.AvdDevice

interface AppleDeviceConfig {
    val device: String
    val versions: List<Int>
    val runtimes: Map<Int, String>
    val defaultVersion: Int
    fun generateDeviceName(version: Int): String
}

internal object DeviceConfigIos : AppleDeviceConfig {

    override val device: String = "iPhone-11"
    override val versions = listOf(15, 16, 17, 18, 26)
    override val runtimes = mapOf(
        15 to "iOS-15-5",
        16 to "iOS-16-2",
        17 to "iOS-17-0",
        18 to "iOS-18-2",
        26 to "iOS-26-0"
    )

    override val defaultVersion = 16

    override fun generateDeviceName(version: Int) = "Maestro_iPhone11_$version"
}

internal object DeviceConfigTvos : AppleDeviceConfig {

    override val device: String = "Apple-TV-4K-3rd-generation-4K"
    override val versions = listOf(16, 17, 18, 26)
    override val runtimes = mapOf(
        16 to "tvOS-16-4",
        17 to "tvOS-17-5",
        18 to "tvOS-18-5",
        26 to "tvOS-26-2"
    )

    override val defaultVersion = 16

    override fun generateDeviceName(version: Int) = "Maestro_AppleTV4K3rdGen_$version"
}

data class DeviceConfigAndroid(
    val deviceName: String,
    val device: String,
    val tag: String,
    val systemImage: String,
    val abi: String
) {
    companion object {
        val versions = listOf(34, 33, 31, 30, 29, 28)
        val defaultVersion = 30

        fun createConfig(version: Int, device: AvdDevice, architecture: CPU_ARCHITECTURE): DeviceConfigAndroid {
            val name = "Maestro_${device.name.replace(" ", "_")}_API_${version}"
            val tag = "google_apis"
            val systemImage = when (architecture) {
                CPU_ARCHITECTURE.x86_64 -> "x86_64"
                CPU_ARCHITECTURE.ARM64 -> "arm64-v8a"
                else -> throw IllegalStateException("Unsupported architecture $architecture")
            }.let {
                "system-images;android-$version;google_apis;$it"
            }
            val abi = when (architecture) {
                CPU_ARCHITECTURE.x86_64 -> "x86_64"
                CPU_ARCHITECTURE.ARM64 -> "arm64-v8a"
                else -> throw IllegalStateException("Unsupported architecture $architecture")
            }

            return DeviceConfigAndroid(
                deviceName = name,
                device = device.nameId,
                tag = tag,
                systemImage = systemImage,
                abi = abi
            )
        }

        fun choosePixelDevice(devices: List<AvdDevice>): AvdDevice? {
            return devices.find { it.nameId == "pixel_6" } ?:
            devices.find { it.nameId == "pixel_6_pro" } ?:
            devices.find { it.nameId == "pixel_5" } ?:
            devices.find { it.nameId == "pixel_4" } ?:
            devices.find { it.nameId == "pixel" }
        }
    }
}
