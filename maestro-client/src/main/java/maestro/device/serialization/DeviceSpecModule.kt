package maestro.device.serialization

import com.fasterxml.jackson.databind.module.SimpleModule
import maestro.device.DeviceSpec
import maestro.device.locale.AndroidLocale
import maestro.device.locale.DeviceLocale
import maestro.device.locale.IosLocale
import maestro.device.locale.WebLocale

class DeviceSpecModule : SimpleModule("DeviceSpecModule") {
    init {
        addSerializer(DeviceSpec::class.java, DeviceSpecSparseSerializer())
        addSerializer(DeviceLocale::class.java, DeviceLocaleSerializer())
        addDeserializer(AndroidLocale::class.java, AndroidLocaleDeserializer())
        addDeserializer(IosLocale::class.java, IosLocaleDeserializer())
        addDeserializer(WebLocale::class.java, WebLocaleDeserializer())
    }
}
