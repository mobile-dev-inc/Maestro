package maestro.cli.view

import org.fusesource.jansi.Ansi

fun String.magenta(): String {
    return "@|magenta $this|@".render()
}

fun String.red(): String {
    return "@|red $this|@".render()
}

fun String.brightRed(): String {
    return "\u001B[91m$this\u001B[0m"
}

fun String.green(): String {
    return "@|green $this|@".render()
}

fun String.blue(): String {
    return "@|blue $this|@".render()
}

fun String.bold(): String {
    return "@|bold $this|@".render()
}

fun String.yellow(): String {
    return "@|yellow $this|@".render()
}

fun String.cyan(): String {
    return "@|cyan $this|@".render()
}

fun String.faint(): String {
    return "@|faint $this|@".render()
}

fun String.box(): String {
    return boxWithColor { it.magenta() }
}

fun String.greenBox(): String {
    return boxWithColor { it.green() }
}

private fun String.boxWithColor(colorize: (String) -> String): String {
    val lines = this.lines()

    val messageWidth = lines.map { it.replace(Regex("\u001B\\[[\\d;]*[^\\d;]"),"") }.maxOf { it.length }
    val paddingX = 3
    val paddingY = 1
    val width = messageWidth + paddingX * 2

    val tl = colorize("╭")
    val tr = colorize("╮")
    val bl = colorize("╰")
    val br = colorize("╯")
    val hl = colorize("─")
    val vl = colorize("│")

    val py = "$vl${" ".repeat(width)}$vl\n".repeat(paddingY)
    val px = " ".repeat(paddingX)
    val l = "$vl$px"
    val r = "$px$vl"

    val sb = StringBuilder()
    sb.appendLine("$tl${hl.repeat(width)}$tr")
    sb.append(py)
    lines.forEach { line ->
        sb.appendLine("$l${padRight(line, messageWidth)}$r")
    }
    sb.append(py)
    sb.appendLine("$bl${hl.repeat(width)}$br")

    return sb.toString()
}

fun String.render(): String {
    return Ansi.ansi().render(this).toString()
}

private fun padRight(s: String, width: Int): String {
    // Strip ANSI escape sequences to compute the visible width
    val visible = s.replace(Regex("\u001B\\[[\\d;]*[^\\d;]"), "")
    val pad = (width - visible.length).coerceAtLeast(0)
    return s + " ".repeat(pad)
}
