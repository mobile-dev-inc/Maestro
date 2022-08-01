package maestro.android

import java.io.File
import java.util.regex.Pattern

internal object AndroidBuildToolsDirectory {

    fun findBuildToolsDir(androidHome: File): File {
        val buildToolsParent = File(androidHome, "build-tools")
        if (!buildToolsParent.exists()) {
            throw IllegalStateException("build-tools directory does not exist: $buildToolsParent")
        }

        val latestBuildToolsVersion = getLatestToolsVersion(buildToolsParent)
            ?: throw IllegalStateException("Could not find a valid build-tools subdirectory in $buildToolsParent")

        return File(buildToolsParent, latestBuildToolsVersion.toString())
    }

    private fun getLatestToolsVersion(buildToolsParent: File): BuildToolsVersion? {
        return buildToolsParent.listFiles()!!
            .mapNotNull { BuildToolsVersion.parse(it.name) }
            .maxOfOrNull { it }
    }

    private class BuildToolsVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
    ) : Comparable<BuildToolsVersion> {
        override fun toString(): String {
            return "$major.$minor.$patch"
        }

        override fun compareTo(other: BuildToolsVersion): Int {
            return VERSION_COMPARATOR.compare(this, other)
        }

        companion object {

            private val VERSION_PATTERN = Pattern.compile("([0-9]+)\\.([0-9]+)\\.([0-9]+)")

            private val VERSION_COMPARATOR = compareBy<BuildToolsVersion>(
                { v -> v.major },
                { v -> v.minor },
                { v -> v.patch },
            )

            fun parse(name: String): BuildToolsVersion? {
                val m = VERSION_PATTERN.matcher(name)
                if (!m.matches()) return null
                return BuildToolsVersion(
                    major = m.group(1).toInt(),
                    minor = m.group(2).toInt(),
                    patch = m.group(3).toInt(),
                )
            }
        }
    }
}
