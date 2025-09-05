package maestro.js

interface JsEngine : AutoCloseable {
    fun onLogMessage(callback: (String) -> Unit)
    fun enterScope()
    fun leaveScope()
    fun putEnv(key: String, value: String)
    fun setCopiedText(text: String?)
    fun evaluateScript(
        script: String,
        env: Map<String, String> = emptyMap(),
        sourceName: String = "inline-script",
        runInSubScope: Boolean = false,
    ): Any?
    
    fun enterEnvScope()
    fun leaveEnvScope()
}
