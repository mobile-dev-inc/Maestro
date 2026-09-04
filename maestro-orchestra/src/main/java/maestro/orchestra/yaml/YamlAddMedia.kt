package maestro.orchestra.yaml

import maestro.orchestra.yaml.schema.YamlRequiresOneOf

import com.fasterxml.jackson.annotation.JsonCreator

@YamlRequiresOneOf("files")
data class YamlAddMedia(
    val files: List<String?>? = null,
    val label: String? = null,
    val optional: Boolean = false,
) {
    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(files: List<String>) = YamlAddMedia(
            files = files,
        )
    }
}
