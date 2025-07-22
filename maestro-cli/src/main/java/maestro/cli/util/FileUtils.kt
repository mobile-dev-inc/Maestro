package maestro.cli.util

import maestro.js.RhinoJsEngine
import maestro.orchestra.util.Env.evaluateScripts
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.orchestra.yaml.YamlCommandReader
import maestro.utils.StringUtils.toRegexSafe
import java.io.File
import java.util.zip.ZipInputStream

object FileUtils {

    fun File.isZip(): Boolean {
        return try {
            ZipInputStream(inputStream()).close()
            true
        } catch (ignored: Exception) {
            false
        }
    }

    fun File.isWebFlow(): Boolean {
        if (isDirectory) {
            return listFiles()
                ?.any { it.isWebFlow() }
                ?: false
        }

        val isYaml =
            name.endsWith(".yaml", ignoreCase = true) ||
            name.endsWith(".yml", ignoreCase = true)

        if (
            !isYaml ||
            name.equals("config.yaml", ignoreCase = true) ||
            name.equals("config.yml", ignoreCase = true)
        ) {
            return false
        }

        val config = YamlCommandReader.readConfig(toPath())
        
        // Quick check: if no JavaScript expressions, use simple regex
        if (!config.appId.contains("\${")) {
            return Regex("https?://").containsMatchIn(config.appId)
        }
        
        // Only create JS engine if JavaScript expressions are present
        val jsEngine = RhinoJsEngine()
        
        try {
            // Evaluate environment variables in the appId
            val env = emptyMap<String, String>()
                .withInjectedShellEnvVars()
                .withDefaultEnvVars(this)
            env.forEach { (key, value) ->
                jsEngine.putEnv(key, value)
            }
            
            val evaluatedAppId = try {
                config.appId.evaluateScripts(jsEngine)
            } catch (e: Exception) {
                config.appId // Fall back to original if evaluation fails
            }
            
            return Regex("https?://").containsMatchIn(evaluatedAppId)
        } finally {
            jsEngine.close()
        }
    }

}
