/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.cli.command

import maestro.cli.runner.TestRunner
import maestro.cli.util.MaestroFactory
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "test",
)
class TestCommand : Callable<Int> {

    @CommandLine.ParentCommand
    private val parent: MaestroParentCommand? = null

    @CommandLine.Parameters
    private lateinit var flowFile: File

    @Option(names = ["-c", "--continuous"])
    private var continuous: Boolean = false

    @CommandLine.Spec
    lateinit var commandSpec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        if (!flowFile.exists()) {
            throw CommandLine.ParameterException(
                commandSpec.commandLine(),
                "File not found: $flowFile"
            )
        }

        val maestro = MaestroFactory.createMaestro(parent?.platform, parent?.host, parent?.port)

        if (!continuous) return TestRunner.runSingle(maestro, flowFile)

        TestRunner.runContinuous(maestro, flowFile)
    }
}
