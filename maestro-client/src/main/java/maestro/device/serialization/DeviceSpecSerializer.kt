package maestro.device.serialization

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import maestro.device.CPU_ARCHITECTURE
import maestro.device.DeviceOrientation
import maestro.device.DeviceSpec
import maestro.device.Platform
import maestro.device.locale.DeviceLocale

class DeviceSpecSerializer : StdSerializer<DeviceSpec>(DeviceSpec::class.java) {
    override fun serialize(value: DeviceSpec, gen: JsonGenerator, provider: SerializerProvider) {
        gen.writeStartObject()
        gen.writeStringField("platform", value.platform.name)
        gen.writeStringField("model", value.model)
        gen.writeStringField("os", value.os)
        gen.writeObjectField("locale", value.locale)

        when (value) {
            is DeviceSpec.Android -> {
                gen.writeStringField("orientation", value.orientation.name)
                gen.writeBooleanField("disableAnimations", value.disableAnimations)
                gen.writeStringField("cpuArchitecture", value.cpuArchitecture.name)
            }
            is DeviceSpec.Ios -> {
                gen.writeStringField("orientation", value.orientation.name)
                gen.writeBooleanField("disableAnimations", value.disableAnimations)
                gen.writeBooleanField("snapshotKeyHonorModalViews", value.snapshotKeyHonorModalViews)
            }
            is DeviceSpec.Web -> {}
        }
        gen.writeEndObject()
    }
}

class DeviceSpecDeserializer : StdDeserializer<DeviceSpec>(DeviceSpec::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): DeviceSpec {
        val node = p.codec.readTree<JsonNode>(p)
        val platform = Platform.valueOf(node.get("platform").asText())
        val model = node.get("model").asText()
        val os = node.get("os").asText()

        val localeNode = node.get("locale")
        val locale = DeviceLocale.fromString(
            localeNode.get("code").asText(),
            Platform.valueOf(localeNode.get("platform").asText())
        )

        return when (platform) {
            Platform.ANDROID -> DeviceSpec.Android(
                model = model,
                os = os,
                locale = locale,
                orientation = node.get("orientation")?.asText()?.let { DeviceOrientation.valueOf(it) }
                    ?: DeviceOrientation.PORTRAIT,
                disableAnimations = node.get("disableAnimations")?.asBoolean() ?: false,
                cpuArchitecture = node.get("cpuArchitecture")?.asText()?.let { CPU_ARCHITECTURE.valueOf(it) }
                    ?: CPU_ARCHITECTURE.ARM64,
            )
            Platform.IOS -> DeviceSpec.Ios(
                model = model,
                os = os,
                locale = locale,
                orientation = node.get("orientation")?.asText()?.let { DeviceOrientation.valueOf(it) }
                    ?: DeviceOrientation.PORTRAIT,
                disableAnimations = node.get("disableAnimations")?.asBoolean() ?: false,
                snapshotKeyHonorModalViews = node.get("snapshotKeyHonorModalViews")?.asBoolean() ?: false,
            )
            Platform.WEB -> DeviceSpec.Web(
                model = model,
                os = os,
                locale = locale,
            )
        }
    }
}
