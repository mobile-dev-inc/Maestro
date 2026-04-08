package maestro.cli.report

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import org.junit.jupiter.api.Test

class HtmlTestSuiteReporterTest : TestSuiteReporterTest() {

    @Test
    fun `HTML - Test passed`() {
        // Given
        val testee = HtmlTestSuiteReporter()
        val sink = Buffer()

        // When
        testee.report(
            summary = testSuccessWithWarning,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
            <html>
              <head>
                <title>Maestro Test Report</title>
                <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
              </head>
              <body>
                <div class="card mb-4">
                  <div class="card-body">
                    <h1 class="mt-5 text-center">Flow Execution Summary</h1>
            <br>Test Result: PASSED<br>Duration: 31m 55.947s<br>Start Time: $nowAsIso<br><br>
                    <div class="card-group mb-4">
                      <div class="card">
                        <div class="card-body">
                          <h5 class="card-title text-center">Total number of Flows</h5>
                          <h3 class="card-text text-center">2</h3>
                        </div>
                      </div>
                      <div class="card text-white bg-danger">
                        <div class="card-body">
                          <h5 class="card-title text-center">Failed Flows</h5>
                          <h3 class="card-text text-center">0</h3>
                        </div>
                      </div>
                      <div class="card text-white bg-success">
                        <div class="card-body">
                          <h5 class="card-title text-center">Successful Flows</h5>
                          <h3 class="card-text text-center">2</h3>
                        </div>
                      </div>
                    </div>
                    <div class="card mb-4">
                      <div class="card-header">
                        <h5 class="mb-0"><button class="btn btn-success" type="button" data-bs-toggle="collapse" data-bs-target="#flow-0-Flow-A" aria-expanded="false" aria-controls="flow-0-Flow-A">Flow A : SUCCESS</button></h5>
                      </div>
                      <div class="collapse" id="flow-0-Flow-A">
                        <div class="card-body">
                          <p class="card-text">Status: SUCCESS<br>Duration: 7m 1.573s<br>Start Time: $nowPlus1AsIso<br>File Name: flow_a<br></p>
                        </div>
                      </div>
                    </div>
                    <div class="card mb-4">
                      <div class="card-header">
                        <h5 class="mb-0"><button class="btn btn-success" type="button" data-bs-toggle="collapse" data-bs-target="#flow-1-Flow-B" aria-expanded="false" aria-controls="flow-1-Flow-B">Flow B : WARNING</button></h5>
                      </div>
                      <div class="collapse" id="flow-1-Flow-B">
                        <div class="card-body">
                          <p class="card-text">Status: WARNING<br>Duration: 24m 54.749s<br>Start Time: $nowPlus2AsIso<br>File Name: flow_b<br></p>
                        </div>
                      </div>
                    </div>
                  </div>
                  <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.min.js"></script>
                </div>
              </body>
            </html>

            """.trimIndent()
        )
    }

    @Test
    fun `HTML - Test failed`() {
        // Given
        val testee = HtmlTestSuiteReporter()
        val sink = Buffer()

        // When
        testee.report(
            summary = testSuccessWithError,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
            <html>
              <head>
                <title>Maestro Test Report</title>
                <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
              </head>
              <body>
                <div class="card mb-4">
                  <div class="card-body">
                    <h1 class="mt-5 text-center">Flow Execution Summary</h1>
            <br>Test Result: FAILED<br>Duration: 9m 12.743s<br>Start Time: $nowAsIso<br><br>
                    <div class="card-group mb-4">
                      <div class="card">
                        <div class="card-body">
                          <h5 class="card-title text-center">Total number of Flows</h5>
                          <h3 class="card-text text-center">2</h3>
                        </div>
                      </div>
                      <div class="card text-white bg-danger">
                        <div class="card-body">
                          <h5 class="card-title text-center">Failed Flows</h5>
                          <h3 class="card-text text-center">1</h3>
                        </div>
                      </div>
                      <div class="card text-white bg-success">
                        <div class="card-body">
                          <h5 class="card-title text-center">Successful Flows</h5>
                          <h3 class="card-text text-center">1</h3>
                        </div>
                      </div>
                    </div>
                    <div class="card border-danger mb-3">
                      <div class="card-body text-danger"><b>Failed Flow</b><br>
                        <p class="card-text">Flow B<br></p>
                      </div>
                    </div>
                    <div class="card mb-4">
                      <div class="card-header">
                        <h5 class="mb-0"><button class="btn btn-success" type="button" data-bs-toggle="collapse" data-bs-target="#flow-0-Flow-A" aria-expanded="false" aria-controls="flow-0-Flow-A">Flow A : SUCCESS</button></h5>
                      </div>
                      <div class="collapse" id="flow-0-Flow-A">
                        <div class="card-body">
                          <p class="card-text">Status: SUCCESS<br>Duration: 7m 1.573s<br>Start Time: $nowPlus1AsIso<br>File Name: flow_a<br></p>
                        </div>
                      </div>
                    </div>
                    <div class="card mb-4">
                      <div class="card-header">
                        <h5 class="mb-0"><button class="btn btn-danger" type="button" data-bs-toggle="collapse" data-bs-target="#flow-1-Flow-B" aria-expanded="false" aria-controls="flow-1-Flow-B">Flow B : ERROR</button></h5>
                      </div>
                      <div class="collapse" id="flow-1-Flow-B">
                        <div class="card-body">
                          <p class="card-text">Status: ERROR<br>Duration: 2m 11.846s<br>Start Time: $nowPlus2AsIso<br>File Name: flow_b<br></p>
                          <p class="card-text text-danger">Error message</p>
                        </div>
                      </div>
                    </div>
                  </div>
                  <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.min.js"></script>
                </div>
              </body>
            </html>

            """.trimIndent()
        )
    }

    @Test
    fun `HTML - Pretty mode with successful test and steps`() {
        // Given
        val testee = HtmlTestSuiteReporter(detailed = true)
        val sink = Buffer()

        // When
        testee.report(
            summary = testSuccessWithSteps,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        // Verify key elements are present
        assertThat(resultStr).contains("Test Steps (3)")
        assertThat(resultStr).contains("✅")
        assertThat(resultStr).contains("1. Launch app")
        assertThat(resultStr).contains("2. Tap on button")
        assertThat(resultStr).contains("3. Assert visible")
        assertThat(resultStr).contains("1.2s")
        assertThat(resultStr).contains("500ms")
        assertThat(resultStr).contains("100ms")
        assertThat(resultStr).contains(".step-item")
        assertThat(resultStr).contains(".step-header")
        assertThat(resultStr).contains(".step-name")

        // Verify proper HTML structure
        assertThat(resultStr).contains("<html>")
        assertThat(resultStr).contains("</html>")
        assertThat(resultStr).contains("Flow Execution Summary")
        assertThat(resultStr).contains("Test Result: PASSED")
    }

    @Test
    fun `HTML - Pretty mode with failed test and steps with various statuses`() {
        // Given
        val testee = HtmlTestSuiteReporter(detailed = true)
        val sink = Buffer()

        // When
        testee.report(
            summary = testErrorWithSteps,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        // Verify key elements and various step statuses
        assertThat(resultStr).contains("Test Steps (4)")
        assertThat(resultStr).contains("✅") // COMPLETED
        assertThat(resultStr).contains("⚠️") // WARNED
        assertThat(resultStr).contains("❌") // FAILED
        assertThat(resultStr).contains("⏭️") // SKIPPED
        assertThat(resultStr).contains("1. Launch app")
        assertThat(resultStr).contains("2. Tap on optional element")
        assertThat(resultStr).contains("3. Tap on button")
        assertThat(resultStr).contains("4. Assert visible")
        assertThat(resultStr).contains("Element not found")
        assertThat(resultStr).contains(".step-item")
        assertThat(resultStr).contains(".step-header")
        assertThat(resultStr).contains(".step-name")

        // Verify proper HTML structure
        assertThat(resultStr).contains("<html>")
        assertThat(resultStr).contains("</html>")
        assertThat(resultStr).contains("Flow Execution Summary")
        assertThat(resultStr).contains("Test Result: FAILED")
        assertThat(resultStr).contains("Failed Flow")
    }

    @Test
    fun `HTML - Basic mode does not show steps even when present`() {
        // Given
        val testee = HtmlTestSuiteReporter(detailed = false)
        val sink = Buffer()

        // When
        testee.report(
            summary = testSuccessWithSteps,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        // Should not contain step details
        assertThat(resultStr).doesNotContain("Test Steps")
        assertThat(resultStr).doesNotContain("Launch app")
        assertThat(resultStr).doesNotContain("step-item")
        assertThat(resultStr).doesNotContain("step-header")

        // Should contain basic flow information
        assertThat(resultStr).contains("Flow A")
        assertThat(resultStr).contains("Status: SUCCESS")
        assertThat(resultStr).contains("File Name: flow_a")
    }

    @Test
    fun `HTML - Tags and properties are displayed`() {
        // Given
        val testee = HtmlTestSuiteReporter(detailed = false)
        val sink = Buffer()

        // When
        testee.report(
            summary = testWithTagsAndProperties,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        // Verify tags are displayed
        assertThat(resultStr).contains("Tags:")
        assertThat(resultStr).contains("smoke")
        assertThat(resultStr).contains("critical")
        assertThat(resultStr).contains("auth")
        assertThat(resultStr).contains("regression")
        assertThat(resultStr).contains("e2e")
        assertThat(resultStr).contains("badge bg-primary")

        // Verify properties section and table
        assertThat(resultStr).contains("Properties")
        assertThat(resultStr).contains("testCaseId")
        assertThat(resultStr).contains("TC-001")
        assertThat(resultStr).contains("xray-test-key")
        assertThat(resultStr).contains("PROJ-123")
        assertThat(resultStr).contains("priority")
        assertThat(resultStr).contains("P0")
        assertThat(resultStr).contains("TC-002")
        assertThat(resultStr).contains("testrail-case-id")
        assertThat(resultStr).contains("C456")
        assertThat(resultStr).contains("table table-sm table-bordered")

        // Verify flow names
        assertThat(resultStr).contains("Login Flow")
        assertThat(resultStr).contains("Checkout Flow")
    }
}
