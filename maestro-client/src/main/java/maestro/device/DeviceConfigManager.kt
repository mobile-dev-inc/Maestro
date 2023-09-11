package maestro.device

import maestro.Maestro

class DeviceConfigManager(private val maestro: Maestro) {

    fun resetMedia() {
        maestro.removeMedia()
    }
}