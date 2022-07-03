package conductor.orchestra

import okio.Source

interface CommandReader {

    fun readCommands(source: Source): List<ConductorCommand>

    object SyntaxError : RuntimeException()

    object NoInputException : RuntimeException()

}
