package maestro.orchestra.yaml.junit

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.yaml.YamlCommandReader
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

class YamlExceptionExtension : ParameterResolver {

    override fun supportsParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Boolean {
        return RuntimeException::class.java.isAssignableFrom(parameterContext.parameter.type)
    }

    override fun resolveParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Any {
        val yamlFileAnnotation = parameterContext.findAnnotation(YamlFile::class.java)
            .orElseThrow { IllegalArgumentException("No @YamlFile annotation found") }

        return try {
            YamlCommandReader.readCommands(YamlResourceFile(yamlFileAnnotation.name).path)
        } catch (e: Throwable) {
            assertThat(e).isInstanceOf(parameterContext.parameter.type)
            return e
        }
    }
}
