package maestro.orchestra.yaml

data class YamlTravelCommand(
    val points: List<String>,
    val speed: Double? = null,
    val label: String? = null,
    val optional: Boolean = false,
)
