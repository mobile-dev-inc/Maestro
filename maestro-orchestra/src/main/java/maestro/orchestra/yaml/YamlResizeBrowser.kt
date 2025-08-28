package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlResizeBrowser(
    val width: Int,
    val height: Int,
    val label: String? = null,
    val optional: Boolean = false,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(size: String): YamlResizeBrowser {
            val parts = size.split("x", "X")
            require(parts.size == 2) { "Invalid size format. Use 'WIDTHxHEIGHT' (e.g., '1920x1080')" }
            return YamlResizeBrowser(
                width = parts[0].trim().toInt(),
                height = parts[1].trim().toInt()
            )
        }
    }
}