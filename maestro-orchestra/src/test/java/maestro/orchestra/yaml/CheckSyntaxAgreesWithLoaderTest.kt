package maestro.orchestra.yaml

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** The check passes a file exactly when `maestro test` would load it, over every YAML file (`.yaml` or `.yml`) under `e2e/`. */
class CheckSyntaxAgreesWithLoaderTest {

    @Test fun `every e2e yaml file passes the check exactly when it loads`() {
        // Set by maestro-orchestra's build for its tests; the repo root is its parent.
        val projectDir = System.getenv("PROJECT_DIR") ?: error("PROJECT_DIR is not set; run through Gradle")
        val e2e = Paths.get(projectDir).resolve("../e2e").normalize()
        val files = Files.walk(e2e).use { paths ->
            paths.filter { it.isRegularFile() && (it.toString().endsWith(".yaml") || it.toString().endsWith(".yml")) }.sorted().toList()
        }
        assertThat(files).isNotEmpty()

        val disagreements = files.mapNotNull { file ->
            val loads = runCatching { YamlCommandReader.readCommands(file) }.isSuccess
            val passes = YamlCommandReader.checkSyntaxReportingAllProblems(file.readText(), file).isEmpty()
            if (loads == passes) null else "${e2e.relativize(file)}: loads=$loads, passes the check=$passes"
        }
        assertThat(disagreements).isEmpty()
    }
}
