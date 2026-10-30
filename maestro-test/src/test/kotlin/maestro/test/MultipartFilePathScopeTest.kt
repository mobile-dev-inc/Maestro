package maestro.test

import com.google.common.truth.Truth.assertThat
import maestro.js.GraalJsEngine
import maestro.js.JsEvaluationException
import maestro.utils.FileAccessScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

/**
 * Exercises the real runtime object graph device-free: a GraalJsEngine built with
 * FileAccessScope.under(<workspace>) running JS `http.post(..., { multipartForm })`,
 * verifying the multipart filePath resolves through the workspace-bounded scope — an
 * out-of-workspace path is rejected before any network call, an in-workspace one is
 * allowed through.
 */
class MultipartFilePathScopeTest {

    @Test
    fun `out-of-workspace multipart filePath is rejected before any network call`() {
        val workspace = Files.createTempDirectory("workspace")
        // A path guaranteed to be OUTSIDE the temp workspace.
        val outside = "/etc/hostname"

        val engine = GraalJsEngine(scope = FileAccessScope.under(workspace))
        // Anchor relative lookups at the workspace, mirroring a real flow's scriptDir.
        engine.evaluateScript(
            "1",
            scriptDir = workspace.toFile().absolutePath,
        )

        // A localhost port that should refuse connections. If the scope FAILED to
        // reject, the POST would be attempted and we'd see a connection error, not
        // a PathOutsideScope error. That's the distinguishing signal.
        val ex = assertThrows<JsEvaluationException> {
            engine.evaluateScript(
                """
                http.post('http://127.0.0.1:1/upload', {
                    multipartForm: { file: { filePath: '$outside', mediaType: 'text/plain' } }
                })
                """.trimIndent(),
                scriptDir = workspace.toFile().absolutePath,
            )
        }

        val text = listOfNotNull(ex.error.message, ex.error.causeMessage).joinToString(" | ")
        assertThat(text).contains("outside the root")
        // Must NOT have reached the network.
        assertThat(text.lowercase()).doesNotContain("connect")
        assertThat(ex.error.isHostException).isTrue()
    }

    @Test
    fun `in-workspace multipart filePath is allowed and proceeds to the network`() {
        val workspace = Files.createTempDirectory("workspace")
        val inside = workspace.resolve("asset.txt")
        Files.writeString(inside, "hello")

        val engine = GraalJsEngine(scope = FileAccessScope.under(workspace))

        // Relative path anchored at the workspace -> resolves inside -> allowed by scope.
        // The scope passes, so the POST is attempted against a refused port, surfacing
        // a connection error (NOT a PathOutsideScope error).
        val ex = assertThrows<JsEvaluationException> {
            engine.evaluateScript(
                """
                http.post('http://127.0.0.1:1/upload', {
                    multipartForm: { file: { filePath: 'asset.txt', mediaType: 'text/plain' } }
                })
                """.trimIndent(),
                scriptDir = workspace.toFile().absolutePath,
            )
        }

        val text = listOfNotNull(ex.error.message, ex.error.causeMessage).joinToString(" | ")
        // Scope allowed the file: we got a network failure, not a scope rejection.
        assertThat(text).doesNotContain("outside the root")
    }
}
