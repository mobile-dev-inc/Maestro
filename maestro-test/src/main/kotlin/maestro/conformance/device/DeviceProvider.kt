package maestro.conformance.device

import maestro.drivers.AndroidDriver

data class DeviceSpec(val apiLevel: Int)

class DeviceHandle(
    val serial: String,
    val driver: AndroidDriver,
    val apiLevel: Int,
    val userSupplied: Boolean,
    val image: String? = null,
    val deviceProfile: String? = null,
    val abi: String? = null,
)

interface DeviceProvider {
    fun acquire(spec: DeviceSpec): DeviceHandle
    fun release(handle: DeviceHandle)
}
