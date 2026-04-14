package maestro.roku

import org.slf4j.LoggerFactory

/**
 * Discovers Roku devices on the local network.
 *
 * Supports two discovery methods:
 * 1. Manual configuration via host/password (primary)
 * 2. SSDP network discovery (future enhancement)
 */
object RokuDeviceDiscovery {

    private val logger = LoggerFactory.getLogger(RokuDeviceDiscovery::class.java)

    /**
     * Discovers Roku devices from environment variables or manual configuration.
     * Returns a list of (host, password) pairs for reachable devices.
     */
    fun discoverDevices(): List<DiscoveredDevice> {
        val devices = mutableListOf<DiscoveredDevice>()

        // Check environment variables
        val envHost = System.getenv("MAESTRO_ROKU_HOST")
        val envPassword = System.getenv("MAESTRO_ROKU_PASSWORD") ?: ""

        if (!envHost.isNullOrBlank()) {
            val client = RokuEcpClient(host = envHost, password = envPassword)
            if (client.isReachable()) {
                val deviceInfo = client.getDeviceInfo()
                devices.add(DiscoveredDevice(
                    host = envHost,
                    password = envPassword,
                    modelName = deviceInfo?.modelName ?: "Roku Device",
                    friendlyName = deviceInfo?.friendlyName ?: envHost,
                    serialNumber = deviceInfo?.serialNumber ?: "",
                ))
                logger.info("Found Roku device at $envHost: ${deviceInfo?.friendlyName}")
            } else {
                logger.warn("Roku device at $envHost is not reachable")
            }
            client.close()
        }

        return devices
    }

    data class DiscoveredDevice(
        val host: String,
        val password: String,
        val modelName: String,
        val friendlyName: String,
        val serialNumber: String,
    )
}
