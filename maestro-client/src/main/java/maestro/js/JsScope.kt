package maestro.js

import org.mozilla.javascript.ScriptableObject

class JsScope : ScriptableObject() {
    override fun getClassName(): String {
        return "JsScope"
    }
}