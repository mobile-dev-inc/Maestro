package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlBrowserConfig(
    val width: Int = 1024,
    val height: Int = 768,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(size: String): YamlBrowserConfig {
            val parts = size.split("x", "X")
            require(parts.size == 2) { "Invalid browser size format. Use 'WIDTHxHEIGHT' (e.g., '1920x1080')" }
            return YamlBrowserConfig(
                width = parts[0].trim().toInt(),
                height = parts[1].trim().toInt()
            )
        }
    }
}