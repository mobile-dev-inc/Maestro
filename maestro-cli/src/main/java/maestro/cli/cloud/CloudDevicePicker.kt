package maestro.cli.cloud

import maestro.cli.CliError
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
 * A shorthand OS (`34`, `iOS-18`) becomes the newest matching OS in the list.
 */
object CloudDevicePicker {

    data class CloudDevice(val model: String, val os: String)

    private val mainFamily = mapOf(
        Platform.IOS to Regex("""iPhone-(\d+)"""),
        Platform.ANDROID to Regex("""pixel_(\d+)"""),
    )

    /**
     * @param devices model to OS ids for [platform], as `/v2/device/list` returns them.
     *   Only read when the user left something out or used a shorthand.
     */
    fun pick(platform: Platform, model: String?, os: String?, devices: () -> Map<String, List<String>>): CloudDevice {
        if (model != null && os != null) {
            // Canonicalize when the pair is in the list; otherwise send it as given and let
            // Cloud validate it (orgs with hidden devices run models that aren't listed).
            // Best effort: the user named both, so a failed list fetch must not block the upload.
            val listed = try { devices() } catch (e: CliError) { return CloudDevice(model, os) }
            val canonicalModel = listed.keys.firstOrNull { it.equals(model, ignoreCase = true) }
            val canonicalOs = canonicalModel?.let { matchOs(listed.getValue(it), os) }
            return if (canonicalModel != null && canonicalOs != null) CloudDevice(canonicalModel, canonicalOs) else CloudDevice(model, os)
        }

        val listed = devices()
        if (listed.isEmpty()) throw CliError("Maestro Cloud offers no ${platform.description} devices.")

        if (model != null) {
            val canonicalModel = listed.keys.firstOrNull { it.equals(model, ignoreCase = true) }
                ?: throw CliError("$model is not available on Maestro Cloud. Pass --device-os too, or $LIST_HINT")
            return CloudDevice(canonicalModel, newest(listed.getValue(canonicalModel)))
        }

        val models = byPreference(platform, listed.keys)
        if (os != null) {
            return models.firstNotNullOfOrNull { m -> matchOs(listed.getValue(m), os)?.let { CloudDevice(m, it) } }
                ?: throw CliError("$os is not available on Maestro Cloud. Pass --device-model too, or $LIST_HINT")
        }
        val chosen = models.first()
        return CloudDevice(chosen, newest(listed.getValue(chosen)))
    }

    /**
     * The model for a full Android system image given without a model: the newest main-family
     * phone that offers its OS, else the newest phone (the image may be one Cloud doesn't list).
     */
    fun modelForSystemImage(os: String, devices: () -> Map<String, List<String>>): String {
        val listed = devices()
        val models = byPreference(Platform.ANDROID, listed.keys)
        return models.firstOrNull { matchOs(listed.getValue(it), os) != null }
            ?: models.firstOrNull()
            ?: throw CliError("Maestro Cloud offers no Android devices.")
    }

    fun toDeviceSpec(platform: Platform, device: CloudDevice, locale: String?): DeviceSpec = try {
        when (platform) {
            Platform.ANDROID -> DeviceSpec.Android(
                model = device.model,
                os = device.os,
                locale = locale?.let { AndroidLocale.fromString(it) } ?: DeviceSpec.Android.DEFAULT.locale,
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

    // Newest main-family phone first, then every other model in list order.
    private fun byPreference(platform: Platform, models: Collection<String>): List<String> {
        val family = mainFamily[platform] ?: return models.toList()
        val phones = models
            .mapNotNull { m -> family.matchEntire(m)?.let { m to it.groupValues[1].toInt() } }
            .sortedByDescending { it.second }
            .map { it.first }
        return phones + (models - phones.toSet())
    }

    // Exact id (any case) first. Else a shorthand such as `34` or `iOS-18`: the OS with that
    // exact version (`android-34`), or the newest one starting with it (`iOS-18-2`).
    private fun matchOs(osIds: List<String>, os: String): String? {
        osIds.firstOrNull { it.equals(os, ignoreCase = true) }?.let { return it }
        val wanted = versionOf(os).takeIf { it.isNotEmpty() } ?: return null
        osIds.firstOrNull { versionOf(it) == wanted }?.let { return it }
        return osIds.filter { versionOf(it).take(wanted.size) == wanted }.maxWithOrNull(byVersion)
    }

    private fun newest(osIds: List<String>): String = osIds.maxWith(byVersion)

    private fun versionOf(os: String): List<Int> = Regex("""\d+""").findAll(os).map { it.value.toInt() }.toList()

    private val byVersion = Comparator<String> { a, b ->
        val va = versionOf(a)
        val vb = versionOf(b)
        (0 until maxOf(va.size, vb.size))
            .map { (va.getOrNull(it) ?: 0).compareTo(vb.getOrNull(it) ?: 0) }
            .firstOrNull { it != 0 } ?: 0
    }

    private const val LIST_HINT = "run `maestro list-cloud-devices` to see the available devices."
}
