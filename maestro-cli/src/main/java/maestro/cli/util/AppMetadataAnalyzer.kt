package maestro.cli.util

import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.device.AppValidationResult
import maestro.device.Platform
import net.dongliu.apk.parser.ApkFile
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object AppMetadataAnalyzer {

    fun validateAppFile(file: File): AppValidationResult? {
        getWebMetadata(file)?.let {
            return AppValidationResult(Platform.WEB, it.url)
        }
        getIosAppMetadata(file)?.let {
            require(it.platformName == "iphonesimulator") {
                "App build target '${it.platformName}' not supported, set build target to 'iphonesimulator'"
            }
            return AppValidationResult(Platform.IOS, it.bundleId)
        }
        getAndroidAppMetadata(file)?.let {
            require(it.supportedArchitectures.isEmpty() || "arm64-v8a" in it.supportedArchitectures) {
                "APK does not support arm64-v8a architecture. Found: ${it.supportedArchitectures}"
            }
            return AppValidationResult(Platform.ANDROID, it.packageId)
        }
        return null
    }

    private val watchInfoBundleRegex = Regex(".*/Watch/.+\\.app/Info\\.plist$", RegexOption.IGNORE_CASE)

    fun getIosAppMetadata(appFile: File): IosAppMetadata? {
        try {
            val zipFile = ZipFile(appFile)
            val entries = zipFile.entries()
            var bestEntry: ZipEntry? = null
            var bestDepth = Int.MAX_VALUE
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val isWatch = watchInfoBundleRegex.matches(entry.name)
                if (!isWatch && entry.name.lowercase().endsWith(".app/info.plist")) {
                    val depth = entry.name.split("/").size
                    if (depth < bestDepth) { bestEntry = entry; bestDepth = depth }
                }
            }
            if (bestEntry != null) {
                zipFile.getInputStream(bestEntry).use { input ->
                    val root = PropertyListParser.parse(input) as NSDictionary
                    val name = root.objectForKey("CFBundleName")?.toString()
                        ?: root.objectForKey("CFBundleDisplayName")?.toString()
                        ?: throw NullPointerException("Unable to find app name")
                    return IosAppMetadata(
                        name = name,
                        bundleId = root.objectForKey("CFBundleIdentifier")?.toString()
                            ?: throw NullPointerException("Unable to find bundleId"),
                        platformName = root.objectForKey("DTPlatformName")?.toString()
                            ?: throw NullPointerException("Unable to find DTPlatformName"),
                        minimumOSVersion = root.objectForKey("MinimumOSVersion")?.toString()
                            ?: throw NullPointerException("Unable to find MinimumOSVersion"),
                    )
                }
            }
        } catch (_: IOException) {
            return null
        }
        return null
    }

    fun getAndroidAppMetadata(appFile: File): AndroidAppMetadata? {
        try {
            ApkFile(appFile).use { apk ->
                val meta = apk.apkMeta
                val archs = try {
                    ZipFile(appFile).use { zip ->
                        zip.entries().asSequence()
                            .map { it.name }
                            .filter { it.startsWith("lib/") && it.count { c -> c == '/' } == 2 }
                            .map { it.substringAfter("lib/").substringBefore("/") }
                            .distinct().toList()
                    }
                } catch (_: Exception) { emptyList() }
                return AndroidAppMetadata(meta.packageName, archs)
            }
        } catch (_: IOException) {
            return null
        }
    }

    fun getWebMetadata(appFile: File): WebAppMetadata? {
        return try {
            jacksonObjectMapper().readValue<WebAppMetadata>(appFile)
        } catch (_: IOException) { null }
    }
}

data class IosAppMetadata(
    val name: String,
    val bundleId: String,
    val platformName: String,
    val minimumOSVersion: String,
)

data class AndroidAppMetadata(
    val packageId: String,
    val supportedArchitectures: List<String>,
)

data class WebAppMetadata(val url: String)
