package maestro.js

import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

class JsScope(
    private val root: Boolean,
) : ScriptableObject() {

    override fun get(name: String?, start: Scriptable?): Any? {
        if (!root) {
            return super.get(name, start)
        }

        val original = super.get(name, start)

        if (original == NOT_FOUND) {
            return null
        }

        return original
    }

    override fun get(index: Int, start: Scriptable?): Any? {
        if (!root) {
            return super.get(index, start)
        }

        val original = super.get(index, start)

        if (original == NOT_FOUND) {
            return null
        }

        return original
    }

    override fun get(key: Any?): Any? {
        if (!root) {
            return super.get(key)
        }

        val original = super.get(key)

        if (original == NOT_FOUND) {
            return null
        }

        return original
    }

    override fun getClassName(): String {
        return "JsScope"
    }

}