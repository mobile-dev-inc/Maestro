package maestro.device

import maestro.Platform

/**
 * Sealed interface for device operating systems to provide type-safe OS version handling.
 * This allows platform-agnostic code to work with OS versions while keeping
 * Android, iOS, and Web types separate for platform-specific code.
 *
 * Implementations:
 * - AndroidOS (data class) - Android API levels
 * - IosOS (data class) - iOS version numbers
 * - WebOS (object) - Web doesn't have OS versions
 */
sealed interface DeviceOS {
    /**
     * Gets the version number of the OS.
     * Returns 0 for Web platform.
     */
    val version: Int

    /**
     * Gets the platform this OS version is for.
     */
    val platform: Platform

    /**
     * Gets a display name for this OS version.
     */
    val displayName: String

    companion object {
        /**
         * Creates a DeviceOS from a version number and platform.
         * Uses default version if null is provided.
         * 
         * @param version The OS version number (null for default)
         * @param platform The target platform
         * @return DeviceOS instance
         * @throws IllegalArgumentException if version is not supported for the platform
         */
        fun fromVersion(version: Int?, platform: Platform): DeviceOS {
            val finalVersion = version ?: getDefault(platform).version
            
            return when (platform) {
                Platform.ANDROID -> AndroidOS.fromVersion(finalVersion)
                Platform.IOS -> IosOS.fromVersion(finalVersion)
                Platform.WEB -> WebOS
            }
        }

        /**
         * Gets the default DeviceOS for a given platform.
         */
        fun getDefault(platform: Platform): DeviceOS {
            return when (platform) {
                Platform.ANDROID -> AndroidOS.DEFAULT
                Platform.IOS -> IosOS.DEFAULT
                Platform.WEB -> WebOS
            }
        }

        /**
         * Gets all supported versions for a platform.
         */
        fun getSupportedVersions(platform: Platform): List<Int> {
            return when (platform) {
                Platform.ANDROID -> AndroidOS.SUPPORTED_VERSIONS
                Platform.IOS -> IosOS.SUPPORTED_VERSIONS
                Platform.WEB -> emptyList()
            }
        }

        /**
         * Checks if a version is supported for the given platform.
         */
        fun isVersionSupported(version: Int, platform: Platform): Boolean {
            return when (platform) {
                Platform.ANDROID -> AndroidOS.isSupported(version)
                Platform.IOS -> IosOS.isSupported(version)
                Platform.WEB -> true
            }
        }
        
        /**
         * Gets all supported DeviceOS instances for a given platform.
         */
        fun getAllSupported(platform: Platform): List<DeviceOS> {
            return when (platform) {
                Platform.ANDROID -> AndroidOS.SUPPORTED_VERSIONS.map { AndroidOS(it) }
                Platform.IOS -> IosOS.SUPPORTED_VERSIONS.map { IosOS(it) }
                Platform.WEB -> listOf(WebOS)
            }
        }
    }
}

/**
 * Android OS implementation using API levels.
 */
data class AndroidOS(override val version: Int) : DeviceOS {
    override val platform: Platform = Platform.ANDROID
    override val displayName: String = "Android API $version"

    init {
        require(isSupported(version)) {
            "Android API level $version is not supported. Supported versions: $SUPPORTED_VERSIONS"
        }
    }

    companion object {
        val SUPPORTED_VERSIONS = listOf(34, 33, 31, 30, 29, 28)
        const val DEFAULT_VERSION = 30
        val DEFAULT = AndroidOS(DEFAULT_VERSION)

        fun fromVersion(version: Int): AndroidOS = AndroidOS(version)

        fun isSupported(version: Int): Boolean = SUPPORTED_VERSIONS.contains(version)
    }
}

/**
 * iOS OS implementation.
 */
data class IosOS(override val version: Int) : DeviceOS {
    override val platform: Platform = Platform.IOS
    override val displayName: String = "iOS $version"

    init {
        require(isSupported(version)) {
            "iOS version $version is not supported. Supported versions: $SUPPORTED_VERSIONS"
        }
    }

    /**
     * Gets the runtime identifier for this iOS version.
     */
    val runtime: String
        get() = RUNTIMES[version] ?: throw IllegalStateException("No runtime found for iOS $version")

    companion object {
        val SUPPORTED_VERSIONS = listOf(15, 16, 17, 18, 26)
        const val DEFAULT_VERSION = 16
        val DEFAULT = IosOS(DEFAULT_VERSION)

        val RUNTIMES = mapOf(
            15 to "iOS-15-5",
            16 to "iOS-16-2",
            17 to "iOS-17-0",
            18 to "iOS-18-2",
            26 to "iOS-26-0"
        )

        fun fromVersion(version: Int): IosOS = IosOS(version)

        fun isSupported(version: Int): Boolean = SUPPORTED_VERSIONS.contains(version)
    }
}

/**
 * Web OS implementation (Web doesn't have OS versions).
 */
object WebOS : DeviceOS {
    override val version: Int = 0
    override val platform: Platform = Platform.WEB
    override val displayName: String = "Web"
}
