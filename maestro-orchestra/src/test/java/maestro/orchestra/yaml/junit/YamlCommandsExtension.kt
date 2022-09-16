package maestro.orchestra.yaml.junit

import maestro.orchestra.Command
import maestro.orchestra.MaestroCommand
import maestro.orchestra.yaml.YamlCommandReader
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

class YamlCommandsExtension : ParameterResolver {
    private interface ListOfCommands : List<Command>

    override fun supportsParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Boolean {
        val parameterizedType = parameterContext.parameter
            .parameterizedType.typeName.replace("? extends ", "")
        val supportedType = ListOfCommands::class.java
            .genericInterfaces.first().typeName
        return parameterizedType == supportedType
    }

    override fun resolveParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Any {
        val yamlFileAnnotation = parameterContext.findAnnotation(YamlFile::class.java)
            .orElseThrow { IllegalArgumentException("No @YamlFile annotation found") }

        return YamlCommandReader.readCommands(YamlResourceFile(yamlFileAnnotation.name).path)
            .map(MaestroCommand::asCommand)
    }
}
