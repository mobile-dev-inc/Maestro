package maestro.networkproxy.yaml

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.pathString

object YamlMappingRuleParser {

    private val YAML = YAMLFactory()

    private val MAPPER = ObjectMapper(YAML).apply {
        registerModule(KotlinModule.Builder().build())
    }

    fun readRules(dirOrFile: Path): List<YamlMappingRule> {
        return if (dirOrFile.isDirectory()) {
            dirOrFile.toAbsolutePath()
                .toFile()
                .walkTopDown()
                .asSequence()
                .filter { it.isFile && it.extension in setOf("yaml", "yml") }
                .flatMap { readRulesFromFile(it.toPath()) }
                .toList()
        } else {
            readRulesFromFile(dirOrFile.toAbsolutePath())
        }
    }

    fun saveRules(rules: List<YamlMappingRule>, file: Path) {
        MAPPER.writeValue(file.toFile(), rules)
    }

    private fun readRulesFromFile(file: Path): List<YamlMappingRule> {
        val rules = MAPPER.readValue(
            file.toFile(),
            object : TypeReference<List<YamlMappingRule>>() {}
        )

        return rules.map {
            it.copy(
                ruleFilePath = file.toAbsolutePath().pathString
            )
        }
    }

}