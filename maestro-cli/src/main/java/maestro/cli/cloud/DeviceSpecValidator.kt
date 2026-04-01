package maestro.cli.cloud

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
        // 1. Exact match (case-insensitive)
        platformDevices.keys.firstOrNull { it.equals(model, ignoreCase = true) }?.let { return it }

        // 2. Underscore ↔ hyphen backward-compatibility fallback:
        //    Convert underscores in the *supported* key to hyphens, then compare
        //    (handles cases where the user passes "pixel-6" but supported is "pixel_6")
        platformDevices.keys.firstOrNull { key ->
            key.replace('_', '-').equals(model.replace('_', '-'), ignoreCase = true)
        }?.let { return it }

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
     * - Bare number "34" → "android-34"
     */
    private fun resolveAndroidOs(os: String, available: List<String>): String {
        // 1. Exact match
        available.firstOrNull { it == os }?.let { return it }

        // 2. Shorthand: bare integer → "android-<N>"
        if (os.toIntOrNull() != null) {
            val candidate = "android-$os"
            available.firstOrNull { it == candidate }?.let { return it }
        }

        throw InvalidDeviceConfiguration(
            "Android OS '$os' is not supported. Available: $available"
        )
    }

    /**
     * iOS resolution (mirrors backend regex `(iOS|tvOS|watchOS)-$os-.*`):
     * - Exact match
     * - Bare major version "18" → first entry matching `(iOS|tvOS|watchOS)-18-.*`
     * - Prefix without minor "iOS-17" → first entry starting with "iOS-17-"
     */
    private fun resolveIosOs(os: String, available: List<String>): String {
        // 1. Exact match
        available.firstOrNull { it == os }?.let { return it }

        // 2. Bare integer major version – matches first "(iOS|tvOS|watchOS)-<N>-*"
        if (os.toIntOrNull() != null) {
            val regex = Regex("^(iOS|tvOS|watchOS)-$os-.*")
            available.firstOrNull { regex.matches(it) }?.let { return it }
        }

        // 3. Prefix without minor version "iOS-17" → "iOS-17-*"
        val prefix = "$os-"
        available.firstOrNull { it.startsWith(prefix) }?.let { return it }

        throw InvalidDeviceConfiguration(
            "iOS OS '$os' is not supported for this model. Available: $available"
        )
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
