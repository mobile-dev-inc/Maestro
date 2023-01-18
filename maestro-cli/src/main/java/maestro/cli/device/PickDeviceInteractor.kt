package maestro.cli.device

import maestro.cli.CliError

object PickDeviceInteractor {

    fun pickDevice(deviceId: String? = null): Device.Connected {
        if (deviceId != null) {
            val device = DeviceService.listConnectedDevices()
                .find {
                    it.instanceId == deviceId
                } ?: throw CliError("Device with id $deviceId is not connected")

            DeviceService.prepareDevice(device)

            return device
        }

        return pickDeviceInternal()
            .let { pickedDevice ->
                var result: Device = pickedDevice

                if (result is Device.AvailableForLaunch) {
                    result = DeviceService.startDevice(result)
                }

                if (result !is Device.Connected) {
                    error("Device $result is not connected")
                }

                DeviceService.prepareDevice(result)

                result
            }
    }

    private fun pickDeviceInternal(): Device {
        val connectedDevices = DeviceService.listConnectedDevices()

        if (connectedDevices.size == 1) {
            val device = connectedDevices[0]

            PickDeviceView.showRunOnDevice(device)

            return device
        }

        if (connectedDevices.isEmpty()) {
            return startDevice()
        }

        return pickRunningDevice(connectedDevices)
    }

    private fun startDevice(): Device {
        val availableDevices = DeviceService.listAvailableForLaunchDevices()

        if (availableDevices.isEmpty()) {
            throw CliError("No devices available. To proceed, either install Android SDK or Xcode.")
        }

        return PickDeviceView.pickDeviceToStart(availableDevices)
    }

    private fun pickRunningDevice(devices: List<Device>): Device {
        return PickDeviceView.pickRunningDevice(devices)
    }

}

fun main() {
    println(PickDeviceInteractor.pickDevice())

    println("Ready")
    while (!Thread.interrupted()) {
        Thread.sleep(1000)
    }
}
