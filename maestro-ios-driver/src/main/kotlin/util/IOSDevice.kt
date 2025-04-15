package util

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

enum class IOSDeviceType {
    REAL,
    SIMULATOR
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class XCDevice(
    val modelCode: String,
    val simulator: Boolean,
    val modelName: String,
    val identifier: String,
    val platform: String,
    val name: String,
    val operatingSystemVersion: String,
    val modelUTI: String,
    val architecture: String,
) {
    companion object {
        const val IPHONE_PLATFORM = "com.apple.platform.iphoneos"
    }
}