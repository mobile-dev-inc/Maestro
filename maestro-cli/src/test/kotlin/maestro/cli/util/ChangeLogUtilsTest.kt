package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import maestro.cli.util.EnvUtils.CLI_VERSION
import org.junit.jupiter.api.Test
import java.io.File


class ChangeLogUtilsTest {

    private val changelogFile = File(System.getProperty("user.dir"), "../CHANGELOG.md")

    @Test
    fun `test format last version`() {
        val content = changelogFile.readText()

        val changelog = ChangeLogUtils.formatBody(content, CLI_VERSION.toString())

        assertThat(changelog).isNotNull()
        assertThat(changelog).isNotEmpty()
    }

    @Test
    fun `test format unknown version`() {
        val content = changelogFile.readText()

        val changelog = ChangeLogUtils.formatBody(content, "x.yy.z")

        assertThat(changelog).isNull()
    }

    @Test
    fun `test format short version`() {
        val content = changelogFile.readText()

        val changelog = ChangeLogUtils.formatBody(content, "1.38.1")

        assertThat(changelog).containsExactly(
            "- New experimental AI-powered commands for screenshot testing: assertWithAI and assertNoDefectsWithAI (#1906)",
            "- Enable basic support for Maestro uploads while keeping Maestro Cloud functioning (#1970)",
        )
    }

    @Test
    fun `test format link and no paragraph`() {
        val content = changelogFile.readText()

        val changelog = ChangeLogUtils.formatBody(content, "1.37.9")

        assertThat(changelog).containsExactly(
            "- Revert iOS landscape mode fix (#1916)",
        )
    }

    @Test
    fun `test format no subheader`() {
        val content = changelogFile.readText()

        val changelog = ChangeLogUtils.formatBody(content, "1.37.1")

        assertThat(changelog).containsExactly(
            "- Fix crash when `flutter` or `xcodebuild` is not installed (#1839)",
        )
    }

    @Test
    fun `test format strong no paragraph and no sublist`() {
        val content = changelogFile.readText()

        val changelog = ChangeLogUtils.formatBody(content, "1.37.0")

        assertThat(changelog).containsExactly(
            "- Sharding tests for parallel execution on many devices 🎉 (#1732 by Kaan)",
            "- Reports in HTML (#1750 by Depa Panjie Purnama)",
            "- Homebrew is back!",
            "- Current platform exposed in JavaScript (#1747 by Dan Caseley)",
            "- Control airplane mode (#1672 by NyCodeGHG)",
            "- New `killApp` command (#1727 by Alexandre Favre)",
            "- Fix cleaning up retries in iOS driver (#1669)",
            "- Fix some commands not respecting custom labels (#1762 by Dan Caseley)",
            "- Fix “Protocol family unavailable” when rerunning iOS tests (#1671 by Stanisław Chmiela)",
        )
    }

    @Test
    fun `test print`() {
        val content = changelogFile.readText()
        val changelog = ChangeLogUtils.formatBody(content, "1.17.1")

        val printed = ChangeLogUtils.print(changelog)

        assertThat(printed).isEqualTo(
            "\n" +
            "- Tweak: Remove Maestro Studio icon from Mac dock\n" +
            "- Tweak: Prefer port 9999 for Maestro Studio app\n" +
            "- Fix: Fix Maestro Studio conditional code snippet\n"
        )
    }
}
