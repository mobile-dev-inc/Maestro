package conductor.orchestra.yaml

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
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
        val fluentCommands = try {
            mapper.readValue(
                source.buffer().inputStream(),
                object : TypeReference<List<YamlFluentCommand>>() {}
            )
        } catch (e: MismatchedInputException) {
            val message = e.message ?: throw e

            when {
                message.contains("Unrecognized field") -> throw CommandReader.SyntaxError
                message.contains("Cannot construct instance") -> throw CommandReader.SyntaxError
                message.contains("Cannot deserialize") -> throw CommandReader.SyntaxError
                message.contains("No content to map") -> throw CommandReader.NoInputException
                else -> throw e
            }
        }

        return fluentCommands
            .map { it.toCommand() }
    }
}
