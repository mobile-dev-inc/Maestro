package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonAnySetter
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.InvalidInitFlowFile
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.MaestroInitFlow
import java.io.File

data class YamlConfig(
    val name: String?,
    val appId: String,
    val initFlow: YamlInitFlowUnion?,
) {

    private val ext = mutableMapOf<String, Any?>()

    @JsonAnySetter
    fun setOtherField(key: String, other: Any?) {
        ext[key] = other
    }

    fun toCommand(flowFile: File): MaestroCommand {
        val config = MaestroConfig(
            name = name,
            initFlow = initFlow(flowFile),
            ext = ext.toMap()
        )
        return MaestroCommand(applyConfigurationCommand = ApplyConfigurationCommand(config))
    }

    fun getInitFlowFile(flowFile: File): File? {
        if (initFlow == null) return null
        return when (initFlow) {
            is StringInitFlow -> getInitFlowFile(flowFile, initFlow.path)
            is YamlInitFlow -> null
        }
    }

    private fun initFlow(flowFile: File): MaestroInitFlow? {
        if (initFlow == null) return null

        val initCommands = when (initFlow) {
            is StringInitFlow -> stringInitCommands(initFlow, flowFile)
            is YamlInitFlow -> initFlow.commands.map { it.toCommand(appId) }
        }

        return MaestroInitFlow(
            appId = appId,
            commands = initCommands,
        )
    }

    companion object {

        private fun stringInitCommands(initFlow: StringInitFlow, flowFile: File): List<MaestroCommand> {
            val initFlowFile = getInitFlowFile(flowFile, initFlow.path)
            return YamlCommandReader.readCommands(initFlowFile)
        }

        private fun getInitFlowFile(flowFile: File, initFlowPath: String): File {
            val initFlowFile = File(initFlowPath)
            val absoluteInitFlowFile = if (initFlowFile.isAbsolute) {
                initFlowFile
            } else {
                flowFile.parentFile.resolve(initFlowFile)
            }
            if (absoluteInitFlowFile.compareTo(flowFile.absoluteFile) == 0) {
                throw InvalidInitFlowFile("initFlow file can't be the same as the Flow file", absoluteInitFlowFile)
            }
            if (!absoluteInitFlowFile.exists()) {
                throw InvalidInitFlowFile("initFlow file does not exist", absoluteInitFlowFile)
            }
            if (absoluteInitFlowFile.isDirectory) {
                throw InvalidInitFlowFile("initFlow file can't be a directory", absoluteInitFlowFile)
            }
            return absoluteInitFlowFile
        }
    }
}