package conductor.cli

import conductor.Conductor
import conductor.orchestra.CommandReader
import conductor.orchestra.ConductorCommand
import conductor.orchestra.Orchestra
import conductor.orchestra.yaml.YamlCommandReader
import dadb.Dadb
import okio.source
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.ParameterException
import picocli.CommandLine.Spec
import java.io.File
import java.util.concurrent.Callable
import kotlin.system.exitProcess

@Command(name = "test")
class App : Callable<Int> {

    @CommandLine.Parameters
    private lateinit var os: String

    @CommandLine.Parameters
    private lateinit var testFile: File

    @Spec
    lateinit var commandSpec: CommandSpec

    override fun call(): Int {
        if (os !in setOf("android", "ios")) {
            throw ParameterException(
                commandSpec.commandLine(),
                "OS must be one of: android, ios"
            )
        }

        if (!testFile.exists()) {
            throw ParameterException(
                commandSpec.commandLine(),
                "File not found: $testFile"
            )
        }

        val commands = readCommands(testFile)

        createConductor(os).use {
            try {
                Orchestra(it).executeCommands(commands)
            } catch (e: Conductor.NotFoundException) {
                println("Element not found")
                println(e.message)

                println("FAILURE")
                return 1
            }
        }

        println("SUCCESS")

        return 0
    }

    private fun readCommands(file: File): List<ConductorCommand> {
        val reader = resolveCommandReader(file)

        return file.source().use {
            reader.readCommands(file.source())
        }
    }

    private fun resolveCommandReader(file: File): CommandReader {
        if (file.extension == "yaml") {
            return YamlCommandReader()
        }

        throw ParameterException(
            commandSpec.commandLine(),
            "Test file extension is not supported: ${file.extension}"
        )
    }

    private fun createConductor(os: String): Conductor {
        return when (os) {
            "android" -> {
                val dadb = Dadb.discover("localhost")
                if (dadb == null) {
                    println("No Android devices found")
                    throw IllegalStateException()
                }

                Conductor.android(dadb)
            }
            "ios" -> {
                Conductor.ios("localhost", 10883)
            }
            else -> throw IllegalStateException("Unknown os: $os")
        }
    }
}

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    val exitCode = CommandLine(App())
        .setExitCodeExceptionMapper { 1 }
        .execute(*args)
    exitProcess(exitCode)
}
