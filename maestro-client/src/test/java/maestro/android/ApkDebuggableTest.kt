package maestro.android

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths

@Disabled("Local testing only")
internal class ApkDebuggableTest {

    private val home = System.getenv("HOME")

    @Test
    fun enable() {
        ApkDebuggable.enable(
            apkFile = File("$home/Downloads/reddit.apk"),
            apkOutFile = File("$home/Downloads/reddit-debuggable.apk"),
        )
    }

    @Test
    fun isDebuggable() {
        val manifestBytes = FileSystems.newFileSystem(Paths.get("$home/Downloads/reddit-debuggable.apk"), null).use { fs ->
            val manifestPath = fs.getPath("AndroidManifest.xml")
            Files.readAllBytes(manifestPath)
        }
        println("is debuggable: " + ApkDebuggable.isManifestDebuggable(manifestBytes))
    }
}
