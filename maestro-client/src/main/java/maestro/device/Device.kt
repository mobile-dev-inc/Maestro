package maestro.device

sealed class Device(
    open val description: String,
    open val platform: Platform,
    open val deviceType: DeviceType
) {

    enum class DeviceType {
        REAL,
        SIMULATOR,
        EMULATOR,
        BROWSER
    }

    data class Connected(
        val instanceId: String,
        override val description: String,
        override val platform: Platform,
        override val deviceType: DeviceType,
    ) : Device(description, platform, deviceType)

    data class AvailableForLaunch(
        val modelId: String,
        val language: String?,
        val country: String?,
        override val description: String,
        override val platform: Platform,
        override val deviceType: DeviceType,
    ) : Device(description, platform, deviceType)

}
