package maestro.cli.util

import maestro.device.util.AvdDevice

internal object DeviceConfigIos {

    val device: String = "iPhone-11"
    val versions = listOf(15, 16, 17, 18)
    val runtimes = mapOf(
        15 to "iOS-15-5",
        16 to "iOS-16-2",
        17 to "iOS-17-0",
        18 to "iOS-18-2"
    )

    val defaultVersion = 16

    fun generateDeviceName(version: Int) = "Maestro_iPhone11_$version"
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