package maestro.cli.device

import maestro.cli.util.EnvUtils
import maestro.device.CPU_ARCHITECTURE
import maestro.device.DeviceService
import maestro.device.DeviceSpec
import maestro.device.Platform
import util.SimctlList

/**
 * The devices `start-device` can create on this machine without downloading anything, as
 * model to OS ids for [DevicePicker]: iOS device types per installed runtime, and Android Pixel
 * profiles with every installed system image the CLI would create them from.
 */
object LocalDevices {

    val SOURCE = DevicePicker.Source(
        name = "this machine",
        modelFlag = "--device-model",
        osFlag = "--device-os",
        hint = "install an iOS simulator runtime in Xcode, or an Android system image with sdkmanager.",
    )

    /** With [systemImage] (a full Android image), every Pixel profile offers that image's OS. */
    fun forPlatform(platform: Platform, systemImage: String? = null): Map<String, List<String>> = when (platform) {
        Platform.IOS -> DeviceService.listSimulators()?.let(::ios).orEmpty()
        Platform.ANDROID -> android(
            installedImages = systemImage?.let(::listOf) ?: DeviceService.listInstalledSystemImages(),
            profiles = pixelProfiles(),
            abi = EnvUtils.getMacOSArchitecture(),
            anyTag = systemImage != null,
        )
        Platform.WEB -> emptyMap()
    }

    // "com.apple.CoreSimulator.SimDeviceType.iPhone-17" -> "iPhone-17", "...SimRuntime.iOS-26-2" -> "iOS-26-2",
    // the ids `simctl create` takes back (see DeviceService.createIosDevice).
    internal fun ios(simulators: SimctlList): Map<String, List<String>> = simulators.runtimes
        .filter { it.isAvailable && it.identifier.substringAfterLast(".").startsWith("iOS-") }
        .flatMap { runtime ->
            val os = runtime.identifier.substringAfterLast(".")
            runtime.supportedDeviceTypes.map { it.identifier.substringAfterLast(".") to os }
        }
        .groupBy({ it.first }, { it.second })

    // An image counts only if it's the one a spec for its OS creates from (the default tag for
    // this host's abi), so picking it never prompts a download. [anyTag] keeps an explicit image.
    internal fun android(
        installedImages: List<String>,
        profiles: List<String>,
        abi: CPU_ARCHITECTURE,
        anyTag: Boolean = false,
    ): Map<String, List<String>> {
        val osIds = installedImages
            .mapNotNull { image ->
                val os = image.split(";").getOrNull(1)?.takeIf { it.startsWith("android-") } ?: return@mapNotNull null
                os.takeIf { anyTag || DeviceSpec.Android(model = "pixel", os = os, cpuArchitecture = abi).systemImage == image }
            }
            .distinct()
        return if (osIds.isEmpty()) emptyMap() else profiles.associateWith { osIds }
    }

    // `avdmanager list device`. Without avdmanager no emulator can be created, so there's nothing to offer.
    private fun pixelProfiles(): List<String> = try {
        DeviceService.getAvailablePixelDevices().map { it.nameId }
    } catch (e: Exception) {
        emptyList()
    }
}
