package maestro.orchestra

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator

data class WorkspaceConfig(
    val flows: StringList? = null,
    val includeTags: StringList? = null,
    val excludeTags: StringList? = null,
    val local: Local? = null,
) {

    @JsonAnySetter
    fun setOtherField(key: String, other: Any?) {
        // Do nothing
    }

    data class Local(
        val deterministicOrder: Boolean? = null,
    )

    class StringList : ArrayList<String>() {

        companion object {

            @Suppress("unused")
            @JvmStatic
            @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
            fun parse(string: String): StringList {
                return StringList().apply {
                    add(string)
                }
            }

        }

    }

}