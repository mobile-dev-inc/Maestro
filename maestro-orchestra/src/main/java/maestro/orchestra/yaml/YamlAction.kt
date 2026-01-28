package maestro.orchestra.yaml

data class YamlActionBack(
    val label: String? = null,
    val optional: Boolean = false,
)

data class YamlActionClearKeychain(
    val label: String? = null,
    val optional: Boolean = false,
)

data class YamlActionHideKeyboard(
    val commands: List<YamlFluentCommand>? = null,
    val label: String? = null,
    val optional: Boolean = false,
)

data class YamlActionPasteText(
    val label: String? = null,
    val optional: Boolean = false,
)

data class YamlActionScroll(
    val label: String? = null,
    val optional: Boolean = false,
)
