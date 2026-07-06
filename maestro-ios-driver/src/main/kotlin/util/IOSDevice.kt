package util

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

enum class IOSDeviceType {
    REAL,
    SIMULATOR
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceCtlResponse(
    val result: Result
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Result(
        val devices: List<Device>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Device(
        val identifier: String,
        val deviceProperties: DeviceProperties?,
        val hardwareProperties: HardwareProperties?,
        val connectionProperties: ConnectionProperties,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ConnectionProperties(
        val tunnelState: String,
        val transportType: String? = null,
        val pairingState: String? = null,
    ) {
        companion object {
            const val CONNECTED  = "connected"
            const val PAIRED = "paired"
        }

        /**
         * Whether the device is usable for automation. An idle wired device reports
         * tunnelState "disconnected" — the CoreDevice tunnel is established lazily on the
         * first devicectl/xcodebuild interaction — so a paired device on a wired transport
         * counts as reachable even without an active tunnel.
         */
        val isReachable: Boolean
            get() = tunnelState == CONNECTED ||
                    (pairingState == PAIRED && transportType.equals("wired", ignoreCase = true))
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DeviceProperties(
        val name: String?,
        val osVersionNumber: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HardwareProperties(
        val udid: String?
    )
}
