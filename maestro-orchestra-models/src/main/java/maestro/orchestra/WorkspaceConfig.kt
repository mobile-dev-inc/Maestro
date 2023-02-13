package maestro.orchestra

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator

data class WorkspaceConfig(
    val flows: FlowList? = null,
) {

    @JsonAnySetter
    fun setOtherField(key: String, other: Any?) {
        // Do nothing
    }

    class FlowList : ArrayList<String>() {

        companion object {

            @Suppress("unused")
            @JvmStatic
            @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
            fun parse(string: String): FlowList {
                return FlowList().apply {
                    add(string)
                }
            }

        }

    }

}