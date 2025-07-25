package maestro.js

object Js {

    val initScript = """
        function json(text) {
            return JSON.parse(text)
        }
        
        function relativePoint(x, y) {
            var xPercent = Math.ceil(x * 100) + '%'
            var yPercent = Math.ceil(y * 100) + '%'
            
            return xPercent + ',' + yPercent
        }
        
        const output = {}
        const maestro = {
            copiedText: '',
            platform: 'unknown'
        }
    """.trimIndent()

    fun initScriptWithPlatform(platform: String): String {
        return initScript.replace("platform: 'unknown'", "platform: '$platform'")
    }

    fun sanitizeJs(text: String): String {
        return text
            .replace("\n", "")
            .replace("'", "\\'")
    }

}