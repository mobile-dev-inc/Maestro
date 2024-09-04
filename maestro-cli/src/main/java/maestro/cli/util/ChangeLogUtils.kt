package maestro.cli.util

import okhttp3.OkHttpClient
import okhttp3.Request

typealias ChangeLog = List<String>?

object ChangeLogUtils {

    fun formatBody(content: String?, version: String): ChangeLog = content
        ?.split("\n## ")?.map { it.lines() }
        ?.first { it.first().startsWith(version) }
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
