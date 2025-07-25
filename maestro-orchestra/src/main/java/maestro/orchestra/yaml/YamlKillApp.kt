package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlKillApp(
    val appId: String? = null,
    val label: String? = null,
    val optional: Boolean = false,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(appId: String) = YamlKillApp(
            appId = appId,
        )

    }

}
