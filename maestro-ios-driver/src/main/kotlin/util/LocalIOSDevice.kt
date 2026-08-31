package util

class LocalIOSDevice(private val deviceCtlProcess: DeviceCtlProcess = DeviceCtlProcess()) {

    fun uninstall(deviceId: String, bundleIdentifier: String) {
        CommandLineUtils.runCommand(
            listOf(
                "xcrun",
                "devicectl",
                "device",
                "uninstall",
                "app",
                "--device",
                deviceId,
                bundleIdentifier
            )
        )
    }

    fun listDeviceViaDeviceCtl(deviceId: String): DeviceCtlResponse.Device {
        return deviceCtlProcess.listDevices().find { it.hardwareProperties?.udid == deviceId }
            ?: throw IllegalArgumentException("iOS device with identifier $deviceId not connected or available")
    }

    fun listDeviceViaDeviceCtl(): List<DeviceCtlResponse.Device> = deviceCtlProcess.listDevices()
}
