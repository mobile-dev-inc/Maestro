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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.ElementFilter
import maestro.Filters
import maestro.Filters.asFilter
import maestro.cli.App
import maestro.cli.util.MaestroFactory
import maestro.orchestra.Orchestra
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model
import picocli.CommandLine.Option
import picocli.CommandLine.Spec

@Command(
    name = "query",
)
class QueryCommand : Runnable {

    @CommandLine.ParentCommand
    private val parent: App? = null

    @Option(names = ["text"])
    private var text: String? = null

    @Option(names = ["id"])
    private var id: String? = null

    @Spec
    lateinit var commandSpec: Model.CommandSpec

    override fun run() {

        MaestroFactory.createMaestro(parent?.platform, parent?.host, parent?.port).use { maestro ->
            val filters = mutableListOf<ElementFilter>()

            text?.let {
                filters += Filters.textMatches(it.toRegex(Orchestra.REGEX_OPTIONS)).asFilter()
            }

            id?.let {
                filters += Filters.idMatches(it.toRegex(Orchestra.REGEX_OPTIONS))
            }

            if (filters.isEmpty()) {
                throw CommandLine.ParameterException(
                    commandSpec.commandLine(),
                    "Must specify at least one search criteria"
                )
            }

            val elements = maestro.allElementsMatching(
                Filters.intersect(filters)
            )

            val mapper = jacksonObjectMapper()
                .writerWithDefaultPrettyPrinter()

            println("Matches: ${elements.size}")
            elements.forEach {
                println(
                    mapper.writeValueAsString(it)
                )
            }
        }
    }

}
