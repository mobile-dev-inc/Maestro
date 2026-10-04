package maestro.orchestra.error

class FlowPathOutsideWorkspace(
    override val message: String,
    val path: String,
) : ValidationError(message)
