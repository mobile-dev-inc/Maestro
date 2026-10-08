package maestro.cli.web

import maestro.orchestra.yaml.YamlCommandReader
import java.io.File

object WebInteractor {

    fun createManifest(webFlow: File): File? {
        val appId = YamlCommandReader.readConfig(webFlow.toPath()).appId ?: return null

        val manifest = """
            {
                "url": "$appId"
            }
        """.trimIndent()

        val manifestFile = File.createTempFile("manifest", ".json")
        manifestFile.writeText(manifest)
        return manifestFile
    }

}
