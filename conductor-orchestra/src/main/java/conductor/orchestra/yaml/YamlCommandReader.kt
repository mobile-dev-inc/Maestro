package conductor.orchestra.yaml

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import conductor.orchestra.CommandReader
import conductor.orchestra.ConductorCommand
import okio.Source
import okio.buffer

class YamlCommandReader : CommandReader {

    private val mapper by lazy {
        ObjectMapper(YAMLFactory())
            .apply {
                registerModule(KotlinModule.Builder().build())
            }
    }

    override fun readCommands(source: Source): List<ConductorCommand> {
        val fluentCommands = mapper.readValue(
            source.buffer().inputStream(),
            object : TypeReference<List<YamlFluentCommand>>() {}
        )

        return fluentCommands
            .map { it.toCommand() }
    }
}
