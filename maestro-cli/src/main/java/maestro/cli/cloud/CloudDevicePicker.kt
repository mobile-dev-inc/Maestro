package maestro.cli.cloud

import maestro.cli.CliError
import maestro.cli.api.ApiClient
import maestro.device.CPU_ARCHITECTURE
import maestro.device.DeviceSpec
import maestro.device.Platform
import maestro.device.locale.AndroidLocale
import maestro.device.locale.IosLocale
import maestro.device.locale.LocaleValidationException
import maestro.device.locale.WebLocale

/**
 * Chooses the Maestro Cloud device for an upload, so the CLI always sends a complete
 * [DeviceSpec] and Cloud never fills in a default.
 *
 * Whatever the user leaves out is picked from Maestro Cloud's device list the way a person
 * would: the newest phone of the main family (`iPhone-N`, `pixel_N`) and its newest OS.
 * A shorthand OS (`34`, `iOS-18`) becomes the matching OS in the list.
 */
object CloudDevicePicker {

    data class CloudDevice(val model: String, val os: String)

    /** How the caller names the device options, for error messages. */
    data class Flags(val model: String, val os: String) {
        companion object {
            val CLI = Flags("--device-model", "--device-os")
            val MCP = Flags("device_model", "device_os")
        }
    }

    private val mainFamily = mapOf(
        Platform.IOS to Regex("""iPhone-(\d+)"""),
        Platform.ANDROID to Regex("""pixel_(\d+)"""),
    )

    /** `/v2/device/list` for [platform]: model to OS ids, without models that offer no OS. */
    fun listedDevices(client: ApiClient, platform: Platform, flags: Flags): Map<String, List<String>> = try {
        client.listCloudDevices()[platform.name.lowercase()].orEmpty().filterValues { it.isNotEmpty() }
    } catch (e: Exception) {
        val reason = (e as? ApiClient.ApiException)?.let { api -> api.statusCode?.let { "HTTP $it" } ?: "network error" }
            ?: e.javaClass.simpleName
        throw CliError("Could not get the device list from Maestro Cloud ($reason). Pass ${flags.model} and ${flags.os}, or try again.")
    }

    /**
     * Web has one device and never reads the list. Otherwise [devices] is read even when both
     * [model] and [os] are given, to fix their case and expand a shorthand OS; that read is best
     * effort, and a pair the list doesn't have (orgs with hidden devices) is sent as given.
     */
    fun pick(platform: Platform, model: String?, os: String?, flags: Flags, devices: () -> Map<String, List<String>>): CloudDevice {
        if (platform == Platform.WEB) {
            return CloudDevice(model ?: DeviceSpec.Web.DEFAULT.model, os ?: DeviceSpec.Web.DEFAULT.os)
        }

        if (model != null && os != null) {
            val listed = try { devices() } catch (e: CliError) { return CloudDevice(model, os) }
            val listedModel = findModel(listed, model)
            val listedOs = listedModel?.let { matchOs(listed.getValue(it), os) }
            return if (listedModel != null && listedOs != null) CloudDevice(listedModel, listedOs) else CloudDevice(model, os)
        }

        val listed = devices()
        if (model != null) {
            val listedModel = findModel(listed, model)
                ?: throw CliError("$model is not available on Maestro Cloud. Pass ${flags.os} too, or $LIST_HINT")
            return CloudDevice(listedModel, newest(listed.getValue(listedModel)))
        }

        val phones = newestPhones(platform, listed.keys)
        if (os != null) {
            return phones.firstNotNullOfOrNull { phone -> matchOs(listed.getValue(phone), os)?.let { CloudDevice(phone, it) } }
                ?: throw CliError("No ${familyName(platform)} on Maestro Cloud offers $os. Pass ${flags.model} too, or $LIST_HINT")
        }
        val phone = phones.firstOrNull()
            ?: throw CliError("Maestro Cloud lists no ${familyName(platform)}. Pass ${flags.model} and ${flags.os}, or $LIST_HINT")
        return CloudDevice(phone, newest(listed.getValue(phone)))
    }

    /** [systemImage] is a full Android `system-images;<os>;<tag>;<abi>` path; [device]'s OS must be its `<os>`. */
    fun toDeviceSpec(platform: Platform, device: CloudDevice, locale: String?, systemImage: String? = null): DeviceSpec = try {
        when (platform) {
            Platform.ANDROID -> DeviceSpec.Android(
                model = device.model,
                os = device.os,
                systemImageOverride = systemImage,
                locale = locale?.let { AndroidLocale.fromString(it) } ?: DeviceSpec.Android.DEFAULT.locale,
                cpuArchitecture = systemImage?.let { image -> CPU_ARCHITECTURE.entries.firstOrNull { it.value == image.split(";")[3] } }
                    ?: CPU_ARCHITECTURE.ARM64,
            )
            Platform.IOS -> DeviceSpec.Ios(
                model = device.model,
                os = device.os,
                locale = locale?.let { IosLocale.fromString(it) } ?: DeviceSpec.Ios.DEFAULT.locale,
            )
            Platform.WEB -> DeviceSpec.Web(
                model = device.model,
                os = device.os,
                locale = locale?.let { WebLocale.fromString(it) } ?: DeviceSpec.Web.DEFAULT.locale,
            )
        }
    } catch (e: LocaleValidationException) {
        throw CliError(e.message ?: "Invalid device locale: $locale")
    }

    private fun findModel(listed: Map<String, List<String>>, model: String): String? =
        listed.keys.firstOrNull { it.equals(model, ignoreCase = true) }

    // Main-family phones only, highest number first.
    private fun newestPhones(platform: Platform, models: Collection<String>): List<String> {
        val family = mainFamily.getValue(platform)
        return models
            .mapNotNull { m -> family.matchEntire(m)?.let { m to it.groupValues[1].toInt() } }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun familyName(platform: Platform) = if (platform == Platform.IOS) "iPhone" else "Pixel phone"

    // Exact id (any case) first. Else a shorthand such as `34` or `iOS-18`: the OS with that
    // exact version (`android-34`), or the newest one starting with it (`iOS-18-2`).
    private fun matchOs(osIds: List<String>, os: String): String? {
        osIds.firstOrNull { it.equals(os, ignoreCase = true) }?.let { return it }
        val wanted = versionOf(os).takeIf { it.isNotEmpty() } ?: return null
        osIds.firstOrNull { versionOf(it) == wanted }?.let { return it }
        return osIds.filter { versionOf(it).take(wanted.size) == wanted }.maxWithOrNull(byVersion)
    }

    private fun newest(osIds: List<String>): String = osIds.maxWith(byVersion)

    private val digits = Regex("""\d+""")

    private fun versionOf(os: String): List<Int> = digits.findAll(os).map { it.value.toInt() }.toList()

    // OS ids carry at most major and minor (`iOS-18-2`, `android-37.1`).
    private val byVersion = compareBy<String>({ versionOf(it).getOrElse(0) { 0 } }, { versionOf(it).getOrElse(1) { 0 } })

    private const val LIST_HINT = "run `maestro list-cloud-devices` to see the available devices."
}
