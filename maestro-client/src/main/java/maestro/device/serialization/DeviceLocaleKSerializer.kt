package maestro.device.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import maestro.device.Platform
import maestro.device.locale.DeviceLocale

/**
 * kotlinx.serialization serializer for DeviceLocale.
 * Matches the Jackson serialization format: {"code": "en_US", "platform": "ANDROID"}
 */
object DeviceLocaleKSerializer : KSerializer<DeviceLocale> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("DeviceLocale") {
        element<String>("code")
        element<String>("platform")
    }

    override fun serialize(encoder: Encoder, value: DeviceLocale) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.code)
            encodeStringElement(descriptor, 1, value.platform.name)
        }
    }

    override fun deserialize(decoder: Decoder): DeviceLocale {
        return decoder.decodeStructure(descriptor) {
            var code = ""
            var platform = ""
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> code = decodeStringElement(descriptor, 0)
                    1 -> platform = decodeStringElement(descriptor, 1)
                    else -> break
                }
            }
            DeviceLocale.fromString(code, Platform.valueOf(platform))
        }
    }
}
