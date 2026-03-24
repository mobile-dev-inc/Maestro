package maestro.device.serialization

import com.fasterxml.jackson.databind.module.SimpleModule
import maestro.device.locale.DeviceLocale

class DeviceSpecModule : SimpleModule("DeviceSpecModule") {
    init {
        addSerializer(DeviceLocale::class.java, DeviceLocaleSerializer())
        addDeserializer(DeviceLocale::class.java, DeviceLocaleDeserializer())
    }
}
