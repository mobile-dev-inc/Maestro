package maestro.cli.device

import maestro.cli.CliError
import maestro.device.CPU_ARCHITECTURE
import maestro.device.DeviceSpec
import maestro.device.Platform
import maestro.device.locale.AndroidLocale
import maestro.device.locale.IosLocale
import maestro.device.locale.LocaleValidationException
import maestro.device.locale.WebLocale

/**
 * Chooses a device from a list of devices (Maestro Cloud's, or what this machine can create),
 * so the CLI never falls back to a built-in default.
 *
 * Whatever the user leaves out is picked the way a person reading the list would: the newest
 * phone of the main family (`iPhone-N`, `pixel_N`) and its newest OS. A shorthand OS (`34`,
 * `iOS-18`) becomes the matching OS in the list.
 */
object DevicePicker {

    data class Picked(val model: String, val os: String)

    /** Where the devices come from, for error messages. */
    data class Source(val name: String, val modelFlag: String, val osFlag: String, val hint: String)

    private const val WEB_MODEL = "chromium"
    private const val WEB_OS = "default"

    private val mainFamily = mapOf(
        Platform.IOS to Regex("""iPhone-(\d+)"""),
        Platform.ANDROID to Regex("""pixel_(\d+)"""),
    )

    /**
     * [devices] is the platform's model to OS ids. Web has one device and never reads it.
     * When both [model] and [os] are given, the read is best effort and only fixes their case
     * or expands a shorthand OS; a pair the list doesn't have is returned as given.
     */
    fun pick(platform: Platform, model: String?, os: String?, source: Source, devices: () -> Map<String, List<String>>): Picked {
        if (platform == Platform.WEB) return Picked(model ?: WEB_MODEL, os ?: WEB_OS)

        if (model != null && os != null) {
            val listed = try { devices() } catch (e: CliError) { return Picked(model, os) }
            val listedModel = findModel(listed, model)
            val listedOs = listedModel?.let { matchOs(listed.getValue(it), os) }
            return if (listedModel != null && listedOs != null) Picked(listedModel, listedOs) else Picked(model, os)
        }

        val listed = devices()
        if (model != null) {
            val listedModel = findModel(listed, model)
                ?: throw CliError("$model is not available on ${source.name}. Pass ${source.osFlag} too, or ${source.hint}")
            return Picked(listedModel, newest(listed.getValue(listedModel)))
        }

        val phones = newestPhones(platform, listed.keys)
        if (os != null) {
            return phones.firstNotNullOfOrNull { phone -> matchOs(listed.getValue(phone), os)?.let { Picked(phone, it) } }
                ?: throw CliError("No ${familyName(platform)} on ${source.name} offers $os. Pass ${source.modelFlag} too, or ${source.hint}")
        }
        val phone = phones.firstOrNull()
            ?: throw CliError("No ${familyName(platform)} is available on ${source.name}. Pass ${source.modelFlag} and ${source.osFlag}, or ${source.hint}")
        return Picked(phone, newest(listed.getValue(phone)))
    }

    /**
     * [systemImage] is a full Android `system-images;<os>;<tag>;<abi>` path; [device]'s OS must be
     * its `<os>`. Without [cpuArchitecture], Android uses the image's abi, else arm64.
     */
    fun toDeviceSpec(
        platform: Platform,
        device: Picked,
        locale: String?,
        systemImage: String? = null,
        cpuArchitecture: CPU_ARCHITECTURE? = null,
    ): DeviceSpec = try {
        when (platform) {
            Platform.ANDROID -> DeviceSpec.Android(
                model = device.model,
                os = device.os,
                systemImageOverride = systemImage,
                cpuArchitecture = cpuArchitecture
                    ?: systemImage?.let { image -> CPU_ARCHITECTURE.entries.firstOrNull { it.value == image.split(";")[3] } }
                    ?: CPU_ARCHITECTURE.ARM64,
            ).let { spec -> locale?.let { spec.copy(locale = AndroidLocale.fromString(it)) } ?: spec }
            Platform.IOS -> DeviceSpec.Ios(model = device.model, os = device.os)
                .let { spec -> locale?.let { spec.copy(locale = IosLocale.fromString(it)) } ?: spec }
            Platform.WEB -> DeviceSpec.Web(model = device.model, os = device.os)
                .let { spec -> locale?.let { spec.copy(locale = WebLocale.fromString(it)) } ?: spec }
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
}
