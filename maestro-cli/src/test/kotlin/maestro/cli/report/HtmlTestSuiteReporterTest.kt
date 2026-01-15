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
                        <h5 class="mb-0"><button class="btn btn-success" type="button" data-bs-toggle="collapse" data-bs-target="#Flow A" aria-expanded="false" aria-controls="Flow A">Flow A : SUCCESS</button></h5>
                      </div>
                      <div class="collapse" id="Flow A">
                        <div class="card-body">
                          <p class="card-text">Status: SUCCESS<br>Duration: 7m 1.573s<br>Start Time: $nowPlus1AsIso<br>File Name: flow_a</p>
                        </div>
                      </div>
                    </div>
                    <div class="card mb-4">
                      <div class="card-header">
                        <h5 class="mb-0"><button class="btn btn-success" type="button" data-bs-toggle="collapse" data-bs-target="#Flow B" aria-expanded="false" aria-controls="Flow B">Flow B : WARNING</button></h5>
                      </div>
                      <div class="collapse" id="Flow B">
                        <div class="card-body">
                          <p class="card-text">Status: WARNING<br>Duration: 24m 54.749s<br>Start Time: $nowPlus2AsIso<br>File Name: flow_b</p>
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
                        <h5 class="mb-0"><button class="btn btn-success" type="button" data-bs-toggle="collapse" data-bs-target="#Flow A" aria-expanded="false" aria-controls="Flow A">Flow A : SUCCESS</button></h5>
                      </div>
                      <div class="collapse" id="Flow A">
                        <div class="card-body">
                          <p class="card-text">Status: SUCCESS<br>Duration: 7m 1.573s<br>Start Time: $nowPlus1AsIso<br>File Name: flow_a</p>
                        </div>
                      </div>
                    </div>
                    <div class="card mb-4">
                      <div class="card-header">
                        <h5 class="mb-0"><button class="btn btn-danger" type="button" data-bs-toggle="collapse" data-bs-target="#Flow B" aria-expanded="false" aria-controls="Flow B">Flow B : ERROR</button></h5>
                      </div>
                      <div class="collapse" id="Flow B">
                        <div class="card-body">
                          <p class="card-text">Status: ERROR<br>Duration: 2m 11.846s<br>Start Time: $nowPlus2AsIso<br>File Name: flow_b</p>
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
}
