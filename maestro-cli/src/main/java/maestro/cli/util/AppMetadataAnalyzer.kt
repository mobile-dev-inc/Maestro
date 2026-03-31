package maestro.cli.util

import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.device.Platform
import net.dongliu.apk.parser.ApkFile
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private val logger = LoggerFactory.getLogger(AppMetadataAnalyzer::class.java)

object AppMetadataAnalyzer {

    private val watchInfoBundleRegex = Regex(".*/Watch/.+\\.app/Info\\.plist$", RegexOption.IGNORE_CASE)

    fun inferPlatform(file: File): Platform? {
        try {
            if (getWebMetadata(file) != null) return Platform.WEB
        } catch (_: Exception) {}

        try {
            if (getIosAppMetadata(file) != null) return Platform.IOS
        } catch (_: Exception) {}

        try {
            if (getAndroidAppMetadata(file) != null) return Platform.ANDROID
        } catch (_: Exception) {}

        logger.error("Failed to infer platform for ${file.name}")
        return null
    }

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
        } catch (e: Exception) {
            logger.error("Unexpected error reading iOS metadata: ${e.message}", e)
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
        } catch (e: Exception) {
            logger.error("Unexpected error reading Android metadata: ${e.message}", e)
        }
        return null
    }

    fun getWebMetadata(appFile: File): WebAppMetadata? {
        return try {
            jacksonObjectMapper().readValue<WebAppMetadata>(appFile)
        } catch (_: IOException) { null
        } catch (e: Exception) {
            logger.error("Unexpected error reading web metadata: ${e.message}", e)
            null
        }
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
