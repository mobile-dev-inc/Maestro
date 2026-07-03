package maestro.utils

fun String.chunkStringByWordCount(chunkSize: Int): List<String> {
    val words = trim().split("\\s+".toRegex())
    val chunkedStrings = mutableListOf<String>()
    var currentChunk = StringBuilder()

    for (word in words) {
        if (currentChunk.isNotEmpty()) {
            currentChunk.append(" ")
        }
        currentChunk.append(word)

        if (currentChunk.toString().count { it == ' ' } + 1 == chunkSize) {
            chunkedStrings.add(currentChunk.toString())
            currentChunk = StringBuilder()
        }
    }

    if (currentChunk.isNotEmpty()) {
        chunkedStrings.add(currentChunk.toString())
    }

    return chunkedStrings
}
