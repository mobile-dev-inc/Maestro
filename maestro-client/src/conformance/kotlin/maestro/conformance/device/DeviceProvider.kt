package maestro.conformance.device

import maestro.drivers.AndroidDriver

data class DeviceSpec(val apiLevel: Int)

class DeviceHandle(
    val serial: String,
    val driver: AndroidDriver,
    val apiLevel: Int,
    val userSupplied: Boolean,
)

interface DeviceProvider {
    fun acquire(spec: DeviceSpec): DeviceHandle
    fun release(handle: DeviceHandle)
}
