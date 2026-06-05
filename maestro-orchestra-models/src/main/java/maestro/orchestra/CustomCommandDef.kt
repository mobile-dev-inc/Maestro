package maestro.orchestra

import com.fasterxml.jackson.annotation.JsonIgnore
import java.nio.file.Path

enum class ArgumentType { STRING, NUMBER, BOOLEAN }

data class CustomCommandArgument(
    val name: String,
    val type: ArgumentType = ArgumentType.STRING,
    val required: Boolean = false,
    val default: String? = null,
)

data class CustomCommandDef(
    val name: String,
    @field:JsonIgnore val sourceFile: Path,
    val arguments: List<CustomCommandArgument>,
)
