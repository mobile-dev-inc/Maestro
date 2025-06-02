package maestro.orchestra

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator

data class WorkspaceConfig(
    val flows: StringList? = null,
    val includeTags: StringList? = null,
    val excludeTags: StringList? = null,
    val local: Local? = null,
    val executionOrder: ExecutionOrder? = null,
    val baselineBranch: String? = null,
    val notifications: MaestroNotificationConfiguration? = null,
    @Deprecated("not supported now by default on cloud") val disableRetries: Boolean = false,
    val platform: PlatformConfiguration? = PlatformConfiguration(
        android = PlatformConfiguration.AndroidConfiguration(disableAnimations = false),
        ios = PlatformConfiguration.IOSConfiguration(disableAnimations = false)
    ),
) {

    data class MaestroNotificationConfiguration(
        val email: EmailConfig? = null,
        val slack: SlackConfig? = null,
    ) {
        data class EmailConfig(
            val recipients: List<String>,
            val enabled: Boolean = true,
            val onSuccess: Boolean = false,
        )

        data class SlackConfig(
            val channels: List<String>,
            val apiKey: String,
            val enabled: Boolean = true,
            val onSuccess: Boolean = false,
        )
    }

    data class PlatformConfiguration(
        val android: AndroidConfiguration? = null,
        val ios: IOSConfiguration? = null
    ) {
        data class AndroidConfiguration(
            val disableAnimations: Boolean = false,
        )

        data class IOSConfiguration(
            val disableAnimations: Boolean = false,
            val snapshotKeyHonorModalViews: Boolean? = null,
        )
    }

    @JsonAnySetter
    fun setOtherField(key: String, other: Any?) {
        // Do nothing
    }

    @Deprecated("Use ExecutionOrder instead")
    data class Local(
        val deterministicOrder: Boolean? = null,
    )

    data class ExecutionOrder(
        val continueOnFailure: Boolean? = true,
        val flowsOrder: List<String> = emptyList()
    )

    class StringList : ArrayList<String>() {

        companion object {

            @Suppress("unused")
            @JvmStatic
            @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
            fun parse(string: String): StringList {
                return StringList().apply {
                    add(string)
                }
            }
        }
    }
}
