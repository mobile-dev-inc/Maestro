package maestro.cli.report

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import org.junit.jupiter.api.Test

class JUnitTestSuiteReporterTest : TestSuiteReporterTest() {

    @Test
    fun `XML - Test passed`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml()
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
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" device="iPhone 15" tests="2" failures="0" time="1915.947" timestamp="$nowAsIso">
                    <testcase id="Flow A" name="Flow A" classname="Flow A" time="421.573" timestamp="$nowPlus1AsIso" status="SUCCESS"/>
                    <testcase id="Flow B" name="Flow B" classname="Flow B" time="1494.749" timestamp="$nowPlus2AsIso" status="WARNING"/>
                  </testsuite>
                </testsuites>
                
            """.trimIndent()
        )
    }

    @Test
    fun `XML - Test failed`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml()
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
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" tests="2" failures="1" time="552.743" timestamp="$nowAsIso">
                    <testcase id="Flow A" name="Flow A" classname="Flow A" time="421.573" timestamp="$nowPlus1AsIso" status="SUCCESS"/>
                    <testcase id="Flow B" name="Flow B" classname="Flow B" time="131.846" timestamp="$nowPlus2AsIso" status="ERROR">
                      <failure>Error message</failure>
                    </testcase>
                  </testsuite>
                </testsuites>
                
            """.trimIndent()
        )
    }

    @Test
    fun `XML - Custom test suite name is used when present`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml("Custom test suite name")
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
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Custom test suite name" device="iPhone 15" tests="2" failures="0" time="1915.947" timestamp="$nowAsIso">
                    <testcase id="Flow A" name="Flow A" classname="Flow A" time="421.573" timestamp="$nowPlus1AsIso" status="SUCCESS"/>
                    <testcase id="Flow B" name="Flow B" classname="Flow B" time="1494.749" timestamp="$nowPlus2AsIso" status="WARNING"/>
                  </testsuite>
                </testsuites>
                
            """.trimIndent()
        )
    }

    @Test
    fun `XML - Tags and properties are included in output`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml()
        val sink = Buffer()

        // When
        testee.report(
            summary = testWithTagsAndProperties,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" tests="2" failures="0" time="6.0" timestamp="$nowAsIso">
                    <testcase id="Login Flow" name="Login Flow" classname="Login Flow" time="2.5" timestamp="$nowPlus1AsIso" status="SUCCESS">
                      <properties>
                        <property name="testCaseId" value="TC-001"/>
                        <property name="xray-test-key" value="PROJ-123"/>
                        <property name="priority" value="P0"/>
                        <property name="tags" value="smoke, critical, auth"/>
                      </properties>
                    </testcase>
                    <testcase id="Checkout Flow" name="Checkout Flow" classname="Checkout Flow" time="3.5" timestamp="$nowPlus2AsIso" status="SUCCESS">
                      <properties>
                        <property name="testCaseId" value="TC-002"/>
                        <property name="testrail-case-id" value="C456"/>
                        <property name="tags" value="regression, e2e"/>
                      </properties>
                    </testcase>
                  </testsuite>
                </testsuites>
                
            """.trimIndent()
        )
    }

}
