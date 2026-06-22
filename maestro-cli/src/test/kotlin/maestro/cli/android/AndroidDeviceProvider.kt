package maestro.cli.android

import maestro.android.AndroidDeviceConnection

class AndroidDeviceProvider {

    fun local(): AndroidDeviceConnection {
        return AndroidDeviceConnection.adbServer(adbServerPort = 5037)
            ?: error("No adb server reachable")
    }
}
