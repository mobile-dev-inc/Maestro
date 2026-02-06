package maestro.cli.report

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator
import com.fasterxml.jackson.module.kotlin.KotlinModule
import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import okio.Sink
import okio.buffer
import kotlin.time.DurationUnit

class JUnitTestSuiteReporter(
    private val mapper: ObjectMapper,
    private val testSuiteName: String?
) : TestSuiteReporter {

    private fun suiteResultToTestSuite(suite: TestExecutionSummary.SuiteResult) = TestSuite(
        name = testSuiteName ?: "Test Suite",
        device = suite.deviceName,
        failures = suite.failures().size,
        time = suite.duration?.toDouble(DurationUnit.SECONDS)?.toString(),
        timestamp = suite.startTime?.let { millisToCurrentLocalDateTime(it) },
        tests = suite.flows.size,
        testCases = suite.flows
            .map { flow ->
                // Combine flow properties and tags into a single properties list
                val allProperties = mutableListOf<TestCaseProperty>()
                
                // Add custom properties
                flow.properties?.forEach { (key, value) ->
                    allProperties.add(TestCaseProperty(key, value))
                }
                
                // Add tags as a comma-separated property
                flow.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                    allProperties.add(TestCaseProperty("tags", tags.joinToString(", ")))
                }
                
                TestCase(
                    id = flow.name,
                    name = flow.name,
                    classname = flow.name,
                    failure = flow.failure?.let { failure ->
                        Failure(
                            message = failure.message,
                        )
                    },
                    time = flow.duration?.toDouble(DurationUnit.SECONDS)?.toString(),
                    timestamp = flow.startTime?.let { millisToCurrentLocalDateTime(it) },
                    status = flow.status,
                    properties = allProperties.takeIf { it.isNotEmpty() }
                )
            }
    )


    override fun report(
        summary: TestExecutionSummary,
        out: Sink
    ) {
        mapper
            .writerWithDefaultPrettyPrinter()
            .writeValue(
                out.buffer().outputStream(),
                TestSuites(
                    suites = summary
                        .suites
                        .map { suiteResultToTestSuite(it) }
                )
            )
    }

    @JacksonXmlRootElement(localName = "testsuites")
    private data class TestSuites(
        @JacksonXmlElementWrapper(useWrapping = false)
        @JsonProperty("testsuite")
        val suites: List<TestSuite>,
    )

    @JacksonXmlRootElement(localName = "testsuite")
    private data class TestSuite(
        @JacksonXmlProperty(isAttribute = true) val name: String,
        @JacksonXmlProperty(isAttribute = true) val device: String?,
        @JacksonXmlProperty(isAttribute = true) val tests: Int,
        @JacksonXmlProperty(isAttribute = true) val failures: Int,
        @JacksonXmlProperty(isAttribute = true) val time: String? = null,
        @JacksonXmlProperty(isAttribute = true) val timestamp: String? = null,
        @JacksonXmlElementWrapper(useWrapping = false)
        @JsonProperty("testcase")
        val testCases: List<TestCase>,
    )

    private data class TestCase(
        @JacksonXmlProperty(isAttribute = true) val id: String,
        @JacksonXmlProperty(isAttribute = true) val name: String,
        @JacksonXmlProperty(isAttribute = true) val classname: String,
        @JacksonXmlProperty(isAttribute = true) val time: String? = null,
        @JacksonXmlProperty(isAttribute = true) val timestamp: String? = null,
        @JacksonXmlProperty(isAttribute = true) val status: FlowStatus,
        @JacksonXmlElementWrapper(localName = "properties")
        @JacksonXmlProperty(localName = "property")
        val properties: List<TestCaseProperty>? = null,
        val failure: Failure? = null,
    )

    private data class Failure(
        @JacksonXmlText val message: String,
    )

    private data class TestCaseProperty(
        @JacksonXmlProperty(isAttribute = true) val name: String,
        @JacksonXmlProperty(isAttribute = true) val value: String,
    )

    companion object {

        fun xml(testSuiteName: String? = null) = JUnitTestSuiteReporter(
            mapper = XmlMapper().apply {
                registerModule(KotlinModule.Builder().build())
                setSerializationInclusion(JsonInclude.Include.NON_NULL)
                configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true)
            },
            testSuiteName = testSuiteName
        )

    }

}
