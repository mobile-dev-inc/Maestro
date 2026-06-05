package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonIgnore
import maestro.orchestra.CustomCommandDef

data class YamlCustomCommandCall(
    val name: String,
    val args: Map<String, Any?>,
    @JsonIgnore val def: CustomCommandDef,
    val label: String? = null,
    val optional: Boolean = false,
)
