package maestro.device

import maestro.Platform

/**
 * Sealed interface for device models to provide type-safe device model handling.
 * This allows platform-agnostic code to work with device models while keeping
 * Android, iOS, and Web types separate for platform-specific code.
 *
 * Implementations:
 * - AndroidModel (data class) - Android device models (Pixel, etc.)
 * - IosModel (data class) - iOS device models (iPhone, iPad)
 * - WebModel (object) - Web browser
 */
sealed interface DeviceModel {
    /**
     * Gets the model identifier (e.g., "pixel_6", "iPhone-11")
     */
    val modelId: String

    /**
     * Gets the display name for this device model (e.g., "Pixel 6", "iPhone 11")
     */
    val displayName: String

    /**
     * Gets the platform this device model is for.
     */
    val platform: Platform

    companion object {
        /**
         * Creates a DeviceModel from a model identifier and platform.
         * Uses default model if null is provided.
         * 
         * @param modelId The device model identifier (null for default)
         * @param platform The target platform
         * @return DeviceModel instance
         */
        fun fromModelId(modelId: String?, platform: Platform): DeviceModel {
            val finalModelId = modelId ?: getDefault(platform).modelId
            
            return when (platform) {
                Platform.ANDROID -> AndroidModel.fromModelId(finalModelId)
                Platform.IOS -> IosModel.fromModelId(finalModelId)
                Platform.WEB -> WebModel
            }
        }

        /**
         * Gets the default DeviceModel for a given platform.
         */
        fun getDefault(platform: Platform): DeviceModel {
            return when (platform) {
                Platform.ANDROID -> AndroidModel.DEFAULT
                Platform.IOS -> IosModel.DEFAULT
                Platform.WEB -> WebModel
            }
        }
        
        /**
         * Gets all common DeviceModel instances for a given platform.
         */
        fun getAllCommon(platform: Platform): List<DeviceModel> {
            return when (platform) {
                Platform.ANDROID -> AndroidModel.COMMON_MODELS
                Platform.IOS -> IosModel.COMMON_MODELS
                Platform.WEB -> listOf(WebModel)
            }
        }
    }
}

/**
 * Android device model implementation.
 */
data class AndroidModel(
    override val modelId: String,
    override val displayName: String
) : DeviceModel {
    override val platform: Platform = Platform.ANDROID

    companion object {
        // Common Android device models
        const val PIXEL_6_ID = "pixel_6"
        const val PIXEL_6_PRO_ID = "pixel_6_pro"
        const val PIXEL_5_ID = "pixel_5"
        const val PIXEL_4_ID = "pixel_4"
        const val PIXEL_ID = "pixel"

        val PIXEL_6 = AndroidModel(PIXEL_6_ID, "Pixel 6")
        val PIXEL_6_PRO = AndroidModel(PIXEL_6_PRO_ID, "Pixel 6 Pro")
        val PIXEL_5 = AndroidModel(PIXEL_5_ID, "Pixel 5")
        val PIXEL_4 = AndroidModel(PIXEL_4_ID, "Pixel 4")
        val PIXEL = AndroidModel(PIXEL_ID, "Pixel")

        val DEFAULT = PIXEL_6

        /**
         * Common device models list (prioritized)
         */
        val COMMON_MODELS = listOf(
            PIXEL_6,
            PIXEL_6_PRO,
            PIXEL_5,
            PIXEL_4,
            PIXEL
        )

        fun fromModelId(modelId: String): AndroidModel {
            return COMMON_MODELS.find { it.modelId == modelId }
                ?: AndroidModel(modelId, modelId.replace("_", " ").replaceFirstChar { it.uppercase() })
        }

        /**
         * Choose best matching Pixel device from available devices
         */
        fun <T> chooseBestPixel(devices: List<T>, nameIdExtractor: (T) -> String): T? {
            return devices.find { nameIdExtractor(it) == PIXEL_6_ID } ?:
                devices.find { nameIdExtractor(it) == PIXEL_6_PRO_ID } ?:
                devices.find { nameIdExtractor(it) == PIXEL_5_ID } ?:
                devices.find { nameIdExtractor(it) == PIXEL_4_ID } ?:
                devices.find { nameIdExtractor(it) == PIXEL_ID }
        }
    }
}

/**
 * iOS device model implementation.
 */
data class IosModel(
    override val modelId: String,
    override val displayName: String
) : DeviceModel {
    override val platform: Platform = Platform.IOS

    companion object {
        // Common iOS device models
        const val IPHONE_11_ID = "iPhone-11"
        const val IPHONE_12_ID = "iPhone-12"
        const val IPHONE_13_ID = "iPhone-13"
        const val IPHONE_14_ID = "iPhone-14"
        const val IPHONE_15_ID = "iPhone-15"

        val IPHONE_11 = IosModel(IPHONE_11_ID, "iPhone 11")
        val IPHONE_12 = IosModel(IPHONE_12_ID, "iPhone 12")
        val IPHONE_13 = IosModel(IPHONE_13_ID, "iPhone 13")
        val IPHONE_14 = IosModel(IPHONE_14_ID, "iPhone 14")
        val IPHONE_15 = IosModel(IPHONE_15_ID, "iPhone 15")

        val DEFAULT = IPHONE_11

        /**
         * Common device models list
         */
        val COMMON_MODELS = listOf(
            IPHONE_15,
            IPHONE_14,
            IPHONE_13,
            IPHONE_12,
            IPHONE_11
        )

        fun fromModelId(modelId: String): IosModel {
            return COMMON_MODELS.find { it.modelId == modelId }
                ?: IosModel(modelId, modelId.replace("-", " "))
        }
    }
}

/**
 * Web device model implementation (browser).
 */
object WebModel : DeviceModel {
    override val modelId: String = "browser"
    override val displayName: String = "Web Browser"
    override val platform: Platform = Platform.WEB
}
