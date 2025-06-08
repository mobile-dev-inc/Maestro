package maestro.cli.web

import maestro.cli.util.FileUtils.isWebFlow
import maestro.orchestra.yaml.YamlCommandReader
import java.io.File

object WebInteractor {

    fun createManifestFromWorkspace(workspaceFile: File): File? {
        val appId = inferAppId(workspaceFile) ?: return null

        val manifest = """
            {
                "url": "$appId"
            }
        """.trimIndent()

        val manifestFile = File.createTempFile("manifest", ".json")
        manifestFile.writeText(manifest)
        return manifestFile
    }

    private fun inferAppId(file: File): String? {
        if (file.isDirectory) {
            return file.listFiles()
                ?.firstNotNullOfOrNull { inferAppId(it) }
        }

        if (!file.isWebFlow()) {
            return null
        }

        return file.readText()
            .let { YamlCommandReader.readConfig(file.toPath()) }
            .appId
    }

}