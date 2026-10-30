package maestro.test

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.MaestroException
import maestro.orchestra.AddMediaCommand
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.AssertCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.AssertDarkModeCommand
import maestro.orchestra.AssertLightModeCommand
import maestro.orchestra.AssertNoDefectsWithAICommand
import maestro.orchestra.AssertScreenshotCommand
import maestro.orchestra.AssertWithAICommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearKeychainCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.Command
import maestro.orchestra.CopyTextFromCommand
import maestro.orchestra.DefineVariablesCommand
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.EvalScriptCommand
import maestro.orchestra.ExtractTextWithAICommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputRandomCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.PasteTextCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.RepeatCommand
import maestro.orchestra.RetryCommand
import maestro.orchestra.RunFlowCommand
import maestro.orchestra.RunScriptCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.SetAirplaneModeCommand
import maestro.orchestra.SetClipboardCommand
import maestro.orchestra.SetDarkModeCommand
import maestro.orchestra.SetLocationCommand
import maestro.orchestra.SetOrientationCommand
import maestro.orchestra.SetPermissionsCommand
import maestro.orchestra.StartRecordingCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.StopRecordingCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.ToggleAirplaneModeCommand
import maestro.orchestra.ToggleDarkModeCommand
import maestro.orchestra.TravelCommand
import maestro.orchestra.WaitForAnimationToEndCommand
import maestro.test.drivers.FakeDriver
import maestro.utils.FileAccessScope
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.reflect.KClass

/**
 * A run reads inputs from the workspace and writes outputs into the artifacts bundle, registered in
 * `manifest.json`. A caller may keep only what the manifest lists, so an output written anywhere else
 * can be lost — which is how assertScreenshot diffs went missing. The workspace and the bundle are
 * separate folders here, with reads confined to the workspace, so a stray write shows up in either.
 */
class RunOutputStaysInBundleTest {

    @TempDir
    lateinit var tempDir: Path

    private val workspace: Path by lazy { Files.createDirectories(tempDir.resolve("workspace")) }
    private val flows: Path by lazy { Files.createDirectories(workspace.resolve("flows")) }
    private val artifactsDir: Path by lazy { Files.createDirectories(tempDir.resolve("artifacts")) }

    /** One of each command that creates a file itself, the failing assertScreenshot last so the rest run. */
    private fun fileWritingFlow() = listOf(
        MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand(path = "shot")),
        MaestroCommand(startRecordingCommand = StartRecordingCommand(path = "clip")),
        MaestroCommand(stopRecordingCommand = StopRecordingCommand()),
        MaestroCommand(
            assertScreenshotCommand = AssertScreenshotCommand(
                path = "../reference.png",
                thresholdPercentage = "99",
                flowPath = flows,
            ),
        ),
    )

    @Test
    fun `a run leaves the workspace untouched and registers every file it writes`() {
        FakeDriver().apply { open() }.let { driver ->
            Maestro(driver).use { maestro ->
                seedMismatchingReference(maestro, workspace.resolve("reference.png"))
                val workspaceBefore = filesUnder(workspace)

                val orchestra = Orchestra(
                    maestro,
                    artifactsDir = artifactsDir,
                    lookupTimeoutMs = 0L,
                    optionalLookupTimeoutMs = 0L,
                    scope = FileAccessScope.under(workspace),
                )
                assertThrows<MaestroException.AssertionFailure> {
                    runBlocking { orchestra.runFlow(fileWritingFlow()) }
                }

                assertWithMessage("A run wrote into the workspace, which is input only. Allocate outputs through ArtifactsGenerator.")
                    .that(filesUnder(workspace)).isEqualTo(workspaceBefore)
                assertWithMessage("A run wrote next to the workspace and the bundle.")
                    .that(Files.list(tempDir).use { it.map { p -> p.fileName.toString() }.toList() })
                    .containsExactly("workspace", "artifacts")

                val registered = manifestPaths()
                val unregistered = filesUnder(artifactsDir)
                    .filter { it != "manifest.json" }
                    .filterNot { file -> registered.any { file == it || file.startsWith("$it/") } }
                assertWithMessage("Files in the bundle but not in manifest.json, so nothing reading the manifest sees them.")
                    .that(unregistered).isEmpty()
            }
        }
    }

    @Test
    fun `every command declares whether it creates files itself`() {
        val unclassified = concreteSubclasses(Command::class) - CREATES_FILES - CREATES_NO_FILES
        assertWithMessage(
            "Classify each new command. If its implementation creates a file, add it to CREATES_FILES and " +
                "to fileWritingFlow(), and allocate the file through ArtifactsGenerator; otherwise add it to CREATES_NO_FILES."
        ).that(unclassified.map { it.simpleName }).isEmpty()

        assertWithMessage("Every CREATES_FILES command must be exercised by fileWritingFlow().")
            .that(fileWritingFlow().map { it.asCommand()!!::class }.toSet())
            .containsAtLeastElementsIn(CREATES_FILES)
    }

    /** The reference differs from what FakeDriver renders by a block big enough for the diff renderer to keep. */
    private fun seedMismatchingReference(maestro: Maestro, target: Path) {
        val shot = Buffer().also { runBlocking { maestro.takeScreenshot(it, false) } }
        val image = ImageIO.read(shot.inputStream())
        image.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, image.width / 2, image.height / 2)
            dispose()
        }
        ImageIO.write(image, "png", target.toFile())
    }

    private fun filesUnder(root: Path): Set<String> = Files.walk(root).use { paths ->
        paths.filter { it.isRegularFile() }.map { it.relativeTo(root).invariantSeparatorsPathString }.toList().toSet()
    }

    private fun manifestPaths(): List<String> = jacksonObjectMapper()
        .readTree(artifactsDir.resolve("manifest.json").toFile())["entries"]
        .map { it["relativePath"].asText() }

    private fun concreteSubclasses(type: KClass<*>): Set<KClass<*>> =
        type.sealedSubclasses.flatMap { if (it.isSealed) concreteSubclasses(it) else listOf(it) }.toSet()

    companion object {
        /** Commands whose implementation picks a file to write. */
        private val CREATES_FILES: Set<KClass<*>> = setOf(
            TakeScreenshotCommand::class,
            StartRecordingCommand::class,
            AssertScreenshotCommand::class,
        )

        /**
         * Reviewed: they create no file themselves. The AI commands are here on purpose — they hand
         * image bytes to the bundle writer, which registers the file.
         */
        private val CREATES_NO_FILES: Set<KClass<*>> = setOf(
            AddMediaCommand::class,
            ApplyConfigurationCommand::class,
            AssertCommand::class,
            AssertConditionCommand::class,
            AssertDarkModeCommand::class,
            AssertLightModeCommand::class,
            AssertNoDefectsWithAICommand::class,
            AssertWithAICommand::class,
            BackPressCommand::class,
            ClearKeychainCommand::class,
            ClearStateCommand::class,
            CopyTextFromCommand::class,
            DefineVariablesCommand::class,
            EraseTextCommand::class,
            EvalScriptCommand::class,
            ExtractTextWithAICommand::class,
            HideKeyboardCommand::class,
            InputRandomCommand::class,
            InputTextCommand::class,
            KillAppCommand::class,
            LaunchAppCommand::class,
            OpenLinkCommand::class,
            PasteTextCommand::class,
            PressKeyCommand::class,
            RepeatCommand::class,
            RetryCommand::class,
            RunFlowCommand::class,
            RunScriptCommand::class,
            ScrollCommand::class,
            ScrollUntilVisibleCommand::class,
            SetAirplaneModeCommand::class,
            SetClipboardCommand::class,
            SetDarkModeCommand::class,
            SetLocationCommand::class,
            SetOrientationCommand::class,
            SetPermissionsCommand::class,
            StopAppCommand::class,
            StopRecordingCommand::class,
            SwipeCommand::class,
            TapOnElementCommand::class,
            TapOnPointCommand::class,
            TapOnPointV2Command::class,
            ToggleAirplaneModeCommand::class,
            ToggleDarkModeCommand::class,
            TravelCommand::class,
            WaitForAnimationToEndCommand::class,
        )
    }
}
