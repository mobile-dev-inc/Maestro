package maestro.orchestra.yaml.junit

import java.nio.file.Path
import java.nio.file.Paths

class YamlResourceFile(val name: String) {
    val path: Path get() {
        val resource = this::class.java.getResource("/YamlCommandReaderTest/${name}")!!
        return Paths.get(resource.toURI())
    }
}
