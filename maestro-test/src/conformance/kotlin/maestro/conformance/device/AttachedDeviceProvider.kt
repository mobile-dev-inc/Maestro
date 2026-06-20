package maestro.conformance.device

import dadb.Dadb
import maestro.drivers.AndroidDriver

/** BYO: run against an already-connected serial (e.g. emulator-5554). */
class AttachedDeviceProvider(private val serial: String) : DeviceProvider {
    override fun acquire(spec: DeviceSpec): DeviceHandle {
        println("⚠ user-supplied device $serial — state not managed by harness")
        val dadb = Dadb.list().find { it.toString() == serial }
            ?: error("Device $serial not found in `adb devices`")
        val driver = AndroidDriver(dadb, emulatorName = serial)
        driver.open()
        val api = Cmd.run("adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk")
            .stdout.trim().toInt()
        val abi = Cmd.run("adb", "-s", serial, "shell", "getprop", "ro.product.cpu.abi").stdout.trim()
        val deviceProfile = Cmd.run("adb", "-s", serial, "shell", "getprop", "ro.product.model").stdout.trim()
        return DeviceHandle(serial, driver, api, userSupplied = true,
            image = null, deviceProfile = deviceProfile.ifBlank { null }, abi = abi.ifBlank { null })
    }

    override fun release(handle: DeviceHandle) {
        handle.driver.close() // do NOT wipe/kill a user-supplied device
    }
}
