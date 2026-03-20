package maestro.device.serialization

import com.fasterxml.jackson.databind.module.SimpleModule
import maestro.device.DeviceSpec
import maestro.device.locale.DeviceLocale

class DeviceSpecModule : SimpleModule("DeviceSpecModule") {
  init {
    addSerializer(DeviceSpec::class.java, DeviceSpecSerializer())
    addDeserializer(DeviceSpec::class.java, DeviceSpecDeserializer())
    addSerializer(DeviceLocale::class.java, DeviceLocaleSerializer())
    addDeserializer(DeviceLocale::class.java, DeviceLocaleDeserializer())
  }
}
