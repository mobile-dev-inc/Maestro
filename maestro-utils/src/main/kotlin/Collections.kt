package maestro.utils

import java.io.File
import java.nio.file.Path
import kotlin.io.path.isRegularFile

val Collection<File>.isSingleFile get() =
    size == 1 && first().isDirectory().not()

val Collection<Path>.isRegularFile get() =
    size == 1 && first().isRegularFile()
