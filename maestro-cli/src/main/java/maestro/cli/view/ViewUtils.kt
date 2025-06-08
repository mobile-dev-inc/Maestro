package maestro.cli.view

import org.fusesource.jansi.Ansi

fun String.magenta(): String {
    return "@|magenta $this|@".render()
}

fun String.red(): String {
    return "@|red $this|@".render()
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

fun String.faint(): String {
    return "@|faint $this|@".render()
}

fun String.box(): String {
    val lines = this.lines()

    val messageWidth = lines.map { it.replace(Regex("\u001B\\[[\\d;]*[^\\d;]"),"") }.maxOf { it.length }
    val paddingX = 3
    val paddingY = 1
    val width = messageWidth + paddingX * 2

    val tl = "╭".magenta()
    val tr = "╮".magenta()
    val bl = "╰".magenta()
    val br = "╯".magenta()
    val hl = "─".magenta()
    val vl = "│".magenta()

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
    return String.format("%-${width}s", s)
}
