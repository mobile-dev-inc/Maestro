package maestro.orchestra.yaml

import maestro.orchestra.AssertEqual
import maestro.orchestra.error.SyntaxError

data class YamlAssertEqual(
    val value1: String? = null,
    val value2: String? = null,
    val label: String? = null,
    val optional: Boolean = false,
) {
    fun toModel(commandName: String): AssertEqual {
        return AssertEqual(
            value1 = value1 ?: throw SyntaxError("$commandName requires value1"),
            value2 = value2 ?: throw SyntaxError("$commandName requires value2"),
        )
    } 
}
