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

package conductor.cli.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import conductor.ElementLookupPredicate
import conductor.Predicates
import conductor.cli.util.ConductorFactory
import conductor.orchestra.Orchestra
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model
import picocli.CommandLine.Option
import picocli.CommandLine.Spec

@Command(
    name = "query",
)
class QueryCommand : Runnable {

    @Option(names = ["-t", "--target"])
    private var target: String? = null

    @Option(names = ["text"])
    private var text: String? = null

    @Option(names = ["id"])
    private var id: String? = null

    @Spec
    lateinit var commandSpec: Model.CommandSpec

    override fun run() {
        if (target !in setOf("android", "ios", null)) {
            throw CommandLine.ParameterException(
                commandSpec.commandLine(),
                "Target must be one of: android, ios"
            )
        }

        ConductorFactory.createConductor(target).use { conductor ->
            val predicates = mutableListOf<ElementLookupPredicate>()

            text?.let {
                predicates += Predicates.textMatches(it.toRegex(Orchestra.REGEX_OPTIONS))
            }

            id?.let {
                predicates += Predicates.idMatches(it.toRegex(Orchestra.REGEX_OPTIONS))
            }

            if (predicates.isEmpty()) {
                throw CommandLine.ParameterException(
                    commandSpec.commandLine(),
                    "Must specify at least one search criteria"
                )
            }

            val elements = conductor.allElementsMatching(
                Predicates.allOf(predicates)
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