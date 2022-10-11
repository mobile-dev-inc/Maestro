package maestro.cli.util

import org.fusesource.jansi.Ansi
import java.io.IOException
import kotlin.system.exitProcess

object PrintUtils {

    fun message(message: String) {
        println(Ansi.ansi().render("@|cyan \n$message |@"))
    }

    fun prompt(message: String): String {
        print(Ansi.ansi().render("\n@|yellow,bold $message\n> |@"))
        try {
            return readln().trim()
        } catch (e: IOException) {
            exitProcess(1)
        }
    }

    fun err(message: String) {
        println(
            Ansi.ansi()
                .render("\n")
                .fgRed()
                .render(message)
        )
    }

}