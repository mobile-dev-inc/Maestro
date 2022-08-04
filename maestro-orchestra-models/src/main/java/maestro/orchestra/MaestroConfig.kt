package maestro.orchestra

// Note: The appId config is only a yaml concept for now. It'll be a larger migration to get to a point
// where appId is part of MaestroConfig (and factored out of MaestroCommands - eg: LaunchAppCommand).
data class MaestroConfig(
    val name: String? = null,
    val initFlow: MaestroInitFlow? = null,
    val ext: Map<String, Any?> = emptyMap(),
)

data class MaestroInitFlow(
    val appId: String,
    val commands: List<MaestroCommand>,
)
