package maestro.cli.idb

import maestro.cli.device.Device
import maestro.utils.MaestroTimer
import java.net.Socket

object IdbInstaller {

    fun setup(device: Device.Connected) {
        val idbProcessBuilder = ProcessBuilder("idb_companion", "--udid", device.instanceId)
        idbProcessBuilder.start()

        val idbHost = "localhost"
        val idbPort = 10882
        MaestroTimer.retryUntilTrue(timeoutMs = 30000, delayMs = 100) {
            Socket(idbHost, idbPort).use { true }
        }
    }
}