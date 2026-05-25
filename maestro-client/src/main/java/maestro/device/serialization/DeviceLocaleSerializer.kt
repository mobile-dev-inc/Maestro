package maestro.device.serialization

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import maestro.device.Platform
import maestro.device.locale.AndroidLocale
import maestro.device.locale.DeviceLocale
import maestro.device.locale.IosLocale
import maestro.device.locale.WebLocale

class DeviceLocaleSerializer : StdSerializer<DeviceLocale>(DeviceLocale::class.java) {
  override fun serialize(value: DeviceLocale, gen: JsonGenerator, provider: SerializerProvider) {
    gen.writeStartObject()
    gen.writeStringField("code", value.code)
    gen.writeStringField("platform", value.platform.name)
    gen.writeEndObject()
  }
}

class DeviceLocaleDeserializer : StdDeserializer<DeviceLocale>(DeviceLocale::class.java) {
  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): DeviceLocale {
    val node = p.codec.readTree<JsonNode>(p)
    val code = node.get("code").asText()
    val platform = Platform.valueOf(node.get("platform").asText())
    return DeviceLocale.fromString(code, platform)
  }
}

class AndroidLocaleDeserializer : StdDeserializer<AndroidLocale>(AndroidLocale::class.java) {
  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): AndroidLocale {
    val node = p.codec.readTree<JsonNode>(p)
    val code = node.get("code").asText()
    return AndroidLocale.fromString(code)
  }
}

class IosLocaleDeserializer : StdDeserializer<IosLocale>(IosLocale::class.java) {
  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): IosLocale {
    val node = p.codec.readTree<JsonNode>(p)
    val code = node.get("code").asText()
    return IosLocale.fromString(code)
  }
}

class WebLocaleDeserializer : StdDeserializer<WebLocale>(WebLocale::class.java) {
  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): WebLocale {
    val node = p.codec.readTree<JsonNode>(p)
    val code = node.get("code").asText()
    return WebLocale.fromString(code)
  }
}
