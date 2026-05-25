package maestro.drivers

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CdpWebDriverTest {

    private fun argsFor(
        extensions: List<String>? = null,
        chromeProfile: String? = null,
        profileDirectory: String? = null,
    ): List<String> {
        val driver = CdpWebDriver(
            isStudio = false,
            isHeadless = false,
            screenSize = null,
            extensions = extensions,
            chromeProfile = chromeProfile,
            profileDirectory = profileDirectory,
        )
        val options = driver.buildChromeOptions()
        @Suppress("UNCHECKED_CAST")
        val googChromeOptions = options.asMap()["goog:chromeOptions"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        return googChromeOptions["args"] as List<String>
    }

    @Test
    fun `buildChromeOptions adds --load-extension when extensions populated`() {
        val args = argsFor(extensions = listOf("/tmp/ext1"))
        assertThat(args).contains("--load-extension=/tmp/ext1")
    }

    @Test
    fun `buildChromeOptions joins multiple extensions with comma`() {
        val args = argsFor(extensions = listOf("/tmp/ext1", "/tmp/ext2"))
        assertThat(args).contains("--load-extension=/tmp/ext1,/tmp/ext2")
    }

    @Test
    fun `buildChromeOptions adds --user-data-dir when chromeProfile populated`() {
        val args = argsFor(chromeProfile = "/tmp/profile")
        assertThat(args).contains("--user-data-dir=/tmp/profile")
    }

    @Test
    fun `buildChromeOptions adds --profile-directory when profileDirectory populated`() {
        val args = argsFor(chromeProfile = "/tmp/profile", profileDirectory = "Profile 1")
        assertThat(args).contains("--profile-directory=Profile 1")
    }

    @Test
    fun `buildChromeOptions adds nothing extra when all new params are null`() {
        val args = argsFor()
        assertThat(args.none { it.startsWith("--load-extension") }).isTrue()
        assertThat(args.none { it.startsWith("--user-data-dir") }).isTrue()
        assertThat(args.none { it.startsWith("--profile-directory") }).isTrue()
    }

    @Test
    fun `buildChromeOptions filters empty extension entries`() {
        val args = argsFor(extensions = listOf("", "/tmp/ext1", ""))
        assertThat(args).contains("--load-extension=/tmp/ext1")
    }

    @Test
    fun `buildChromeOptions omits --load-extension when only empty entries`() {
        val args = argsFor(extensions = listOf("", ""))
        assertThat(args.none { it.startsWith("--load-extension") }).isTrue()
    }

    @Test
    fun `buildChromeOptions treats empty chromeProfile as null`() {
        val args = argsFor(chromeProfile = "")
        assertThat(args.none { it.startsWith("--user-data-dir") }).isTrue()
    }

    @Test
    fun `buildChromeOptions treats empty profileDirectory as null`() {
        val args = argsFor(chromeProfile = "/tmp/profile", profileDirectory = "")
        assertThat(args.none { it.startsWith("--profile-directory") }).isTrue()
    }

    @Test
    fun `buildChromeOptions omits --profile-directory when chromeProfile set but profileDirectory null`() {
        val args = argsFor(chromeProfile = "/tmp/profile", profileDirectory = null)
        assertThat(args).contains("--user-data-dir=/tmp/profile")
        assertThat(args.none { it.startsWith("--profile-directory") }).isTrue()
    }

    @Test
    fun `buildChromeOptions adds all three groups in order when all params set`() {
        val args = argsFor(
            extensions = listOf("/tmp/ext1", "/tmp/ext2"),
            chromeProfile = "/tmp/profile",
            profileDirectory = "Profile 1",
        )
        val loadExtIdx = args.indexOfFirst { it.startsWith("--load-extension=") }
        val userDataIdx = args.indexOfFirst { it.startsWith("--user-data-dir=") }
        val profDirIdx = args.indexOfFirst { it.startsWith("--profile-directory=") }
        assertThat(loadExtIdx).isAtLeast(0)
        assertThat(userDataIdx).isGreaterThan(loadExtIdx)
        assertThat(profDirIdx).isGreaterThan(userDataIdx)
        assertThat(args).contains("--load-extension=/tmp/ext1,/tmp/ext2")
        assertThat(args).contains("--user-data-dir=/tmp/profile")
        assertThat(args).contains("--profile-directory=Profile 1")
    }
}
