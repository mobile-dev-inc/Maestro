package maestro.orchestra.yaml

import maestro.orchestra.EqualityCondition

data class YamlAssertNotEqual(
    val value1: String? = null,
    val value2: String? = null,
    val label: String? = null,
    val optional: Boolean = false,
) {
    fun toCondition(): EqualityCondition {
        return EqualityCondition(
            value1 = value1,
            value2 = value2,
        )
    }
}
