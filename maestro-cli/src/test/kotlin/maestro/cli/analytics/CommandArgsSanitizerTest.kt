package maestro.cli.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CommandArgsSanitizerTest {

    @Test
    fun `worked example - cloud with api-key, ios-version, device-os, and positionals`() {
        val argv = arrayOf(
            "cloud",
            "--api-key=secret",
            "--ios-version=18",
            "--device-os=ios-26-2",
            "app.apk",
            "flows/"
        )

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro cloud --api-key=<REDACTED> --ios-version=18 --device-os=ios-26-2")
    }

    @Test
    fun `worked example - test with -e space-separated env, include-tags, and positional`() {
        val argv = arrayOf(
            "test",
            "-e",
            "API_TOKEN=xyz",
            "--include-tags",
            "smoke",
            "flows/"
        )

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro test -e <REDACTED> --include-tags smoke")
    }

    @Test
    fun `worked example - cloud with android-api-level and positionals`() {
        val argv = arrayOf("cloud", "--android-api-level=30", "app.apk", "flows/")

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro cloud --android-api-level=30")
    }

    @Test
    fun `worked example - test with async boolean flag and positional`() {
        val argv = arrayOf("test", "--async", "flows/")

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro test --async")
    }

    @Test
    fun `sensitive flag value redacted in equals form`() {
        val argv = arrayOf("cloud", "--api-key=supersecret")

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro cloud --api-key=<REDACTED>")
    }

    @Test
    fun `sensitive flag value redacted in space-separated form`() {
        val argv = arrayOf("cloud", "--api-key", "supersecret")

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro cloud --api-key <REDACTED>")
    }

    @Test
    fun `multiple -e and --env occurrences each redacted`() {
        val argv = arrayOf(
            "test",
            "-e", "API_TOKEN=xyz",
            "--env", "DB_PASSWORD=hunter2",
            "-e", "OTHER=value"
        )

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro test -e <REDACTED> --env <REDACTED> -e <REDACTED>")
    }

    @Test
    fun `positional single file dropped`() {
        val argv = arrayOf("test", "myflow.yaml")

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro test")
    }

    @Test
    fun `positional directory dropped`() {
        val argv = arrayOf("test", "flows/")

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro test")
    }

    @Test
    fun `multiple positionals dropped`() {
        val argv = arrayOf("test", "flow1.yaml", "flow2.yaml", "flows/")

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro test")
    }

    @Test
    fun `both deprecated flags preserved with values`() {
        val argv = arrayOf(
            "cloud",
            "--android-api-level=30",
            "--ios-version=18"
        )

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro cloud --android-api-level=30 --ios-version=18")
    }

    @Test
    fun `boolean flags pass through`() {
        val argv = arrayOf("test", "--async", "--fail-on-cancellation")

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro test --async --fail-on-cancellation")
    }

    @Test
    fun `mixed sensitive non-sensitive positional and boolean`() {
        val argv = arrayOf(
            "cloud",
            "--api-key=secret",
            "--async",
            "--ios-version=18",
            "-e", "API_TOKEN=xyz",
            "app.apk",
            "--include-tags", "smoke",
            "flows/"
        )

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo(
            "maestro cloud --api-key=<REDACTED> --async --ios-version=18 -e <REDACTED> --include-tags smoke"
        )
    }

    @Test
    fun `empty argv returns maestro`() {
        val argv = emptyArray<String>()

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro")
    }

    @Test
    fun `single subcommand token returns maestro plus subcommand`() {
        val argv = arrayOf("cloud")

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro cloud")
    }

    @Test
    fun `apiKey camelCase alias redacted in both forms`() {
        val argv = arrayOf("cloud", "--apiKey", "supersecret", "--apiKey=anothersecret")

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro cloud --apiKey <REDACTED> --apiKey=<REDACTED>")
    }

    @Test
    fun `boolean flag followed by another flag does not consume the next flag`() {
        val argv = arrayOf("test", "--async", "--include-tags", "smoke")

        val result = CommandArgsSanitizer.sanitize(argv)

        assertThat(result).isEqualTo("maestro test --async --include-tags smoke")
    }
}
