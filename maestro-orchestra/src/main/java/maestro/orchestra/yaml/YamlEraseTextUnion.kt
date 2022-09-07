package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlEraseText(
    val charactersToErase: Int
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(charactersToRemove: Int): YamlEraseText {
            return YamlEraseText(
                charactersToErase = charactersToRemove
            )
        }
    }
}