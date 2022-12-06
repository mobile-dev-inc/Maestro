package maestro.studio

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.files
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.http.content.static
import io.ktor.server.http.content.staticRootFolder
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import maestro.Maestro
import maestro.TreeNode
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory

data class Hierarchy(
    val screenshot: String,
    val tree: TreeNode,
)

object MaestroStudio {

    private val screenshotDir = getScreenshotDir()

    fun start(port: Int, maestro: Maestro) {
        embeddedServer(Netty, port = port) {
            routing {
                get("/api/hierarchy") {
                    val tree = maestro.viewHierarchy().root
                    val screenshot = takeScreenshot(maestro)
                    val hierarchy = Hierarchy(screenshot, tree)
                    val response = jacksonObjectMapper()
                        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(hierarchy)
                    call.respondText(response)
                }
                static("/screenshot") {
                    staticRootFolder = screenshotDir.toFile()
                    files(".")
                }
                singlePageApplication {
                    useResources = true
                    filesPath = "web"
                    defaultPage = "index.html"
                }
            }
        }.start()
    }

    private fun takeScreenshot(maestro: Maestro): String {
        val name = "${UUID.randomUUID()}.png"
        val screenshotFile = screenshotDir.resolve(name).toFile()
        screenshotFile.deleteOnExit()
        maestro.takeScreenshot(screenshotFile)
        return "/screenshot/$name"
    }

    private fun getScreenshotDir(): Path {
        val home = System.getProperty("user.home")
        val parent = if (home.isNullOrBlank()) createTempDirectory() else Path(home)
        val screenshotDir = parent.resolve(".maestro/studio/screenshots")
        screenshotDir.createDirectories()
        return screenshotDir
    }
}
