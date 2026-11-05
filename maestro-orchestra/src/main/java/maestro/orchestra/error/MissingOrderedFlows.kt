package maestro.orchestra.error

class MissingOrderedFlows(
    override val message: String,
    val flowNames: List<String>,
) : ValidationError(message)
