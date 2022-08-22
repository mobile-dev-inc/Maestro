package maestro.orchestra

import maestro.orchestra.util.Env.injectEnv

// Note: The appId config is only a yaml concept for now. It'll be a larger migration to get to a point
// where appId is part of MaestroConfig (and factored out of MaestroCommands - eg: LaunchAppCommand).
data class MaestroConfig(
    val appId: String? = null,
    val name: String? = null,
    val initFlow: MaestroInitFlow? = null,
    val ext: Map<String, Any?> = emptyMap(),
) {

    fun injectEnv(env: Map<String, String>): MaestroConfig {
        return copy(
            appId = appId?.injectEnv(env),
            name = name?.injectEnv(env),
            initFlow = initFlow?.injectEnv(env),
        )
    }

}

data class MaestroInitFlow(
    val appId: String,
    val commands: List<MaestroCommand>,
) {

    fun injectEnv(env: Map<String, String>): MaestroInitFlow {
        return copy(
            appId = appId.injectEnv(env),
            commands = commands.map { it.injectEnv(env) },
        )
    }

}
