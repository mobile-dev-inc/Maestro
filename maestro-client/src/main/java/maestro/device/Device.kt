package maestro.device

sealed class Device(
    open val description: String,
    open val platform: Platform,
    open val deviceType: DeviceType,
    open val deviceSpec: DeviceSpec
) {

    enum class DeviceType {
        REAL,
        SIMULATOR,
        EMULATOR,
        BROWSER,
        STREAMING_DEVICE
    }

    data class Connected(
        val instanceId: String,
        override val deviceSpec: DeviceSpec,
        override val description: String,
        override val platform: Platform,
        override val deviceType: DeviceType,
    ) : Device(description, platform, deviceType, deviceSpec)

    data class AvailableForLaunch(
        val modelId: String,
        override val deviceSpec: DeviceSpec,
        override val description: String,
        override val platform: Platform,
        override val deviceType: DeviceType,
    ) : Device(description, platform, deviceType, deviceSpec)

}
