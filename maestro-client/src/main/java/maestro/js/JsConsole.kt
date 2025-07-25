package maestro.js

import org.mozilla.javascript.ScriptableObject

class JsConsole(
    private val onLogMessage: (String) -> Unit,
) : ScriptableObject() {

    fun log(message: String) {
        onLogMessage(message)
    }

    override fun getClassName(): String {
        return "JsConsole"
    }
}