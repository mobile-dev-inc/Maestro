package maestro.device

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
        val language: String?,
        val country: String?,
        override val description: String,
        override val platform: Platform,
    ) : Device(description, platform)

}
