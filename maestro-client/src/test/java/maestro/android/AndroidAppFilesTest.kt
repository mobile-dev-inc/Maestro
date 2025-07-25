package maestro.android

import dadb.Dadb
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

@Disabled("Local testing only")
internal class AndroidAppFilesTest {

    private val home = System.getenv("HOME")

    private lateinit var dadb: Dadb

    @BeforeEach
    fun setUp() {
        dadb = Dadb.discover("localhost") ?: throw IllegalStateException("Could not find local emulator")
    }

    @Test
    fun pull() {
        val appZipFile = File("$home/Downloads/com.reddit.frontpage.zip")
        AndroidAppFiles.pull(dadb, "com.reddit.frontpage", appZipFile)
    }

    @Test
    fun push() {
        val appZipFile = File("$home/Downloads/com.reddit.frontpage.zip")
        AndroidAppFiles.push(dadb, "com.reddit.frontpage", appZipFile)
    }
}
