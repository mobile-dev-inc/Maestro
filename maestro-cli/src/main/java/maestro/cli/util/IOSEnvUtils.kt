package maestro.cli.util

import java.io.IOException
import kotlin.io.path.Path

object IOSEnvUtils {

    val simulatorRuntimes: List<String>
        get() {
            // See also: https://stackoverflow.com/a/78755176/7009800

            val topLevelDirs = Path("/Library/Developer/CoreSimulator/VolumesX").toFile()
                .listFiles()
                ?.filter { it.exists() } ?: emptyList()

            val installedRuntimes = topLevelDirs
                .map { it.resolve("Library/Developer/CoreSimulator/Profiles/RuntimesD") }
                .map { it.listFiles() }
                .reduceOrNull { acc, list -> acc + list }
                ?.map { file -> file.nameWithoutExtension }

            return installedRuntimes ?: emptyList()
        }

    val xcodeVersion: String?
        get() {
            val lines = try {
                runProcess("xcodebuild", "-version")
            } catch (e: IOException) {
                // Xcode toolchain is probably not installed
                return null
            }

            if (lines.size == 2 && lines.first().contains(' ')) {
                // Correct xcodebuild invocation is always 2 lines. Example:
                //   $ xcodebuild -version
                //   Xcode 15.4
                //   Build version 15F31d
                return lines.first().split(' ')[1]
            }

            return null
        }
}
