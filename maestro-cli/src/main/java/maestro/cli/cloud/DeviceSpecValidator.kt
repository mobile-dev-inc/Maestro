package maestro.cli.cloud

import maestro.cli.util.PrintUtils
import maestro.device.DeviceSpec

/**
 * Validates and resolves a [DeviceSpec] against the list of supported devices from the
 * `v2/device/list` API.
 *
 * Resolution mirrors the backend's `SupportedDevices.validate()` logic so that invalid
 * configurations are caught locally before a round-trip to the server.
 */
object DeviceSpecValidator {

    class InvalidDeviceConfiguration(message: String) : RuntimeException(message)

    /**
     * Validates [deviceSpec] against [supportedDevices] and returns a copy with the model and OS
     * values resolved to their canonical forms.
     *
     * @param deviceSpec the spec to validate, typically produced by [DeviceSpec.fromRequest]
     * @param supportedDevices platform key ("android" / "ios" / "web") → model → list of OS version IDs
     * @throws InvalidDeviceConfiguration if the model or OS is not supported
     */
    fun validate(
        deviceSpec: DeviceSpec,
        supportedDevices: Map<String, Map<String, List<String>>>
    ): DeviceSpec {
        val platformKey = deviceSpec.platform.name.lowercase()
        val platformDevices = supportedDevices[platformKey]
            ?: throw InvalidDeviceConfiguration("Platform '$platformKey' is not supported")

        val resolvedModel = resolveModel(deviceSpec.model, platformDevices)
        val availableOsVersions = platformDevices[resolvedModel]
            ?: throw InvalidDeviceConfiguration("Model '$resolvedModel' has no OS versions")

        val resolvedOs = resolveOs(deviceSpec.os, availableOsVersions, platformKey)

        return copyWithResolvedValues(deviceSpec, resolvedModel, resolvedOs)
    }

    // ---- Model resolution ----

    private fun resolveModel(
        model: String,
        platformDevices: Map<String, List<String>>
    ): String {
        // Exact match (case-insensitive)
        platformDevices.keys.firstOrNull { it.equals(model, ignoreCase = true) }?.let { return it }

        throw InvalidDeviceConfiguration(
            "Device model '$model' is not supported. Supported models: ${platformDevices.keys.joinToString(", ")}"
        )
    }

    // ---- OS resolution ----

    private fun resolveOs(
        os: String,
        availableOsVersions: List<String>,
        platformKey: String
    ): String = when (platformKey) {
        "android" -> resolveAndroidOs(os, availableOsVersions)
        "ios" -> resolveIosOs(os, availableOsVersions)
        else -> resolveExactOs(os, availableOsVersions)
    }

    /**
     * Android resolution:
     * - Exact match
     * - Bare number "34" → "android-34" (deprecated)
     */
    private fun resolveAndroidOs(os: String, available: List<String>): String {
        available.firstOrNull { it == os }?.let { return it }
        resolveDeprecatedBareIntegerAndroid(os, available)?.let { return it }

        throw InvalidDeviceConfiguration(
            "Android OS '$os' is not supported. Available: $available"
        )
    }

    /**
     * iOS resolution:
     * - Exact match
     * - Bare major version "18" → first entry matching `iOS-18-.*` (deprecated)
     * - Prefix without minor "iOS-17" → first entry starting with "iOS-17-"
     */
    private fun resolveIosOs(os: String, available: List<String>): String {
        available.firstOrNull { it == os }?.let { return it }
        resolveDeprecatedBareIntegerIos(os, available)?.let { return it }

        // Prefix without minor version "iOS-17" → "iOS-17-*"
        val prefix = "$os-"
        available.firstOrNull { it.startsWith(prefix) }?.let { return it }

        throw InvalidDeviceConfiguration(
            "iOS OS '$os' is not supported for this model. Available: $available"
        )
    }

    // ---- Deprecated resolution helpers ----
    // TODO: Remove these once all CLI users have migrated to canonical formats.

    /**
     * Resolves bare integer Android OS format: "34" → "android-34".
     * @deprecated Use full format "android-34" instead of bare integer "34".
     */
    @Deprecated("Bare integer OS format will be removed in a future release. Use full format e.g. 'android-34'.")
    private fun resolveDeprecatedBareIntegerAndroid(os: String, available: List<String>): String? {
        if (os.toIntOrNull() == null) return null
        val candidate = "android-$os"
        return available.firstOrNull { it == candidate }?.also {
            PrintUtils.warn("Numeric OS format '$os' is deprecated. Use the full format instead. Available: ${available.joinToString(", ")}")
        }
    }

    /**
     * Resolves bare integer iOS OS format: "18" → first entry matching `iOS-18-.*`.
     * @deprecated Use full format "iOS-18-2" instead of bare integer "18".
     */
    @Deprecated("Bare integer OS format will be removed in a future release. Use full format e.g. 'iOS-18-2'.")
    private fun resolveDeprecatedBareIntegerIos(os: String, available: List<String>): String? {
        if (os.toIntOrNull() == null) return null
        val regex = Regex("^iOS-$os-.*")
        return available.firstOrNull { regex.matches(it) }?.also {
            PrintUtils.warn("Numeric OS format '$os' is deprecated. Use the full format instead. Available: ${available.joinToString(", ")}")
        }
    }

    /** Web (and any unknown platform): exact match only. */
    private fun resolveExactOs(os: String, available: List<String>): String {
        return available.firstOrNull { it == os }
            ?: throw InvalidDeviceConfiguration(
                "OS '$os' is not supported. Available: $available"
            )
    }

    // ---- Copy helpers ----

    private fun copyWithResolvedValues(spec: DeviceSpec, model: String, os: String): DeviceSpec =
        when (spec) {
            is DeviceSpec.Android -> spec.copy(model = model, os = os)
            is DeviceSpec.Ios -> spec.copy(model = model, os = os)
            is DeviceSpec.Web -> spec.copy(model = model, os = os)
        }
}
