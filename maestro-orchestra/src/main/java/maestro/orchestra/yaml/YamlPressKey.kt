package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import maestro.KeyCode

data class YamlPressKey (
    val key: String? = null,
    val keys: List<String>? = null,
    val label: String? = null,
    val optional: Boolean = false,
){
    init {
        // Validate empty array - should not be allowed
        if (keys != null && keys.isEmpty()) {
            throw IllegalArgumentException("pressKey array cannot be empty")
        }
        
        // Validate key names if provided
        keys?.forEach { keyName ->
            validateKeyName(keyName)
        }
        
        key?.let { keyName ->
            validateKeyName(keyName)
        }
    }
    
    private fun validateKeyName(keyName: String) {
        try {
            // Convert key name to KeyCode format and validate
            val normalizedKeyName = keyName.uppercase()
                .replace(" ", "_")
                .replace("-", "_")
            KeyCode.valueOf(normalizedKeyName)
        } catch (e: IllegalArgumentException) {
            // Also try using the getByName method which handles description matching
            val keyByDescription = KeyCode.getByName(keyName)
            if (keyByDescription == null) {
                throw IllegalArgumentException("Invalid key name: $keyName")
            }
        }
    }
    
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(key: String) = YamlPressKey(
            key = key,
        )
        
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(keys: List<String>) = YamlPressKey(
            keys = keys,
        )
    }
}
