package maestro.cli.report

import maestro.cli.model.TestExecutionSummary
import okio.Sink
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

interface TestSuiteReporter {

    /**
     * Writes the report for [summary] to [out] in the format specified by the implementation.
     */
    fun report(
        summary: TestExecutionSummary,
        out: Sink,
    )

    companion object {
        val NOOP: TestSuiteReporter = object : TestSuiteReporter {
            override fun report(summary: TestExecutionSummary, out: Sink) {
                // no-op
            }
        }
    }


    /**
     * Judging from https://github.com/testmoapp/junitxml?tab=readme-ov-file#complete-junit-xml-example,
     * the output of timestamp needs to be an ISO 8601 local date time instead of an ISO 8601 offset date
     * time (it would be ideal to use ISO 8601 offset date time it needs to be confirmed if it's valid)
     *
     * Due to having to use LocalDateTime, we need to get the offset from the client (i.e. the machine running
     * maestro-cli) using ZoneId.systemDefault() so we can display the time relative to the client machine
     */
    fun millisToCurrentLocalDateTime(milliseconds: Long): String {
        val localDateTime = Instant.ofEpochMilli(milliseconds).atZone(ZoneId.systemDefault()).toLocalDateTime()
        return localDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }
}
