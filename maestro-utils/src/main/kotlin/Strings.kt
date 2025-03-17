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

fun drawTextBox(text: String, maxWidth: Int): String {
    // Ensure maxWidth is reasonable (at least 4 to fit "╭─╮" with at least one character)
    val effectiveMaxWidth = maxOf(4, maxWidth)

    // Calculate available content width (accounting for borders and spacing)
    val contentMaxWidth = effectiveMaxWidth - 4 // -4 for "│ " and " │"

    // Split the text by newlines first, then handle word wrapping for each paragraph
    val paragraphs = text.split("\n")
    val lines = mutableListOf<String>()

    for (paragraph in paragraphs) {
        // If paragraph is empty, add an empty line
        if (paragraph.isEmpty()) {
            lines.add("")
            continue
        }

        // Split the paragraph into words for wrapping
        val words = paragraph.split(" ")

        var currentLine = ""
        for (_word in words) {
            val word = _word.replace("\u00A0", " ") // Replace non-breaking spaces with regular spaces
            // Check if word is longer than the maximum content width
            if (word.length > contentMaxWidth) {
                // If we have content on the current line, add it first
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                    currentLine = ""
                }

                // Split the long word into chunks
                var remainingWord = word
                while (remainingWord.isNotEmpty()) {
                    val segment = remainingWord.take(contentMaxWidth)
                    lines.add(segment)
                    remainingWord = remainingWord.drop(contentMaxWidth)
                }
            } else if (currentLine.isEmpty()) {
                // First word on the line
                currentLine = word
            } else if (currentLine.length + word.length + 1 <= contentMaxWidth) {
                // Word fits on current line
                currentLine += " $word"
            } else {
                // Word doesn't fit, start a new line
                lines.add(currentLine)
                currentLine = word
            }
        }

        // Add the last line if not empty
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }
    }

    // Find the width of the box
    val contentWidth = minOf(
        contentMaxWidth,
        (lines.maxOfOrNull { it.length } ?: 0)
    )
    val boxWidth = contentWidth + 2 // +2 for spacing inside the box

    // Build the box
    val result = StringBuilder()

    // Top border
    result.append("╭").append("─".repeat(boxWidth)).append("╮\n")

    // Content lines
    for (line in lines) {
        result.append("│ ")
        result.append(line)
        // Padding to align right border
        val padding = boxWidth - line.length - 1
        if (padding > 0) {
            result.append(" ".repeat(padding))
        }
        result.append("│\n")
    }

    // Bottom border
    result.append("╰").append("─".repeat(boxWidth)).append("╯")

    return result.toString()
}