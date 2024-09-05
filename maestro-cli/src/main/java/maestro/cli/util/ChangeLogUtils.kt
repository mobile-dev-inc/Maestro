package maestro.cli.util

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import maestro.cli.util.EnvUtils.CLI_VERSION

typealias ChangeLog = List<String>?

object ChangeLogUtils {

    fun formatBody(content: String?, version: String): ChangeLog = content
        ?.split("\n## ")?.map { it.lines() }
        ?.firstOrNull { it.firstOrNull()?.startsWith(version) == true }
        ?.drop(1)
        ?.map { it.trim().replace("**", "") }
        ?.map { it.replace("\\[(.*?)]\\(.*?\\)".toRegex(), "$1") }
        ?.filter { it.isNotEmpty() && it.startsWith("- ") }

    fun fetchContent(): String? {
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/mobile-dev-inc/maestro/main/CHANGELOG.md")
            .build()
        return OkHttpClient().newCall(request).execute().body?.string()
    }

    fun print(changelog: ChangeLog): String =
        changelog?.let { "\n${it.joinToString("\n")}\n" }.orEmpty()
}

// Helper launcher to play around with presentation
fun main() {
    val changelogFile = File(System.getProperty("user.dir"), "CHANGELOG.md")
    val content = changelogFile.readText()
    val unreleased = ChangeLogUtils.formatBody(content, "Unreleased")
    val current = ChangeLogUtils.formatBody(content, CLI_VERSION.toString())
    val changelog = unreleased ?: current
    println("## ${unreleased?.let { "Unreleased" } ?: CLI_VERSION.toString()}")
    println("-".repeat(100))
    println(ChangeLogUtils.print(changelog))
    println("-".repeat(100))
}
