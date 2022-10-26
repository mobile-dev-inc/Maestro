package maestro.cli.device

sealed class Device(
    open val description: String,
    open val platform: Platform,
) {

    data class Connected(
        val instanceId: String,
        override val description: String,
        override val platform: Platform,
    ) : Device(description, platform)

    data class AvailableForLaunch(
        val modelId: String,
        override val description: String,
        override val platform: Platform,
    ) : Device(description, platform)

}