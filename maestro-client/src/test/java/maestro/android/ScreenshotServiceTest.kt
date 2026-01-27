package maestro.android

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import maestro.utils.ScreenshotProvider
import maestro.utils.ScreenshotService
import maestro_android.MaestroAndroid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
/**
 * Tests for screenshot functionality in MaestroDriverService.
 *
 * These tests verify the behavior when screenshots fail:
 * 1. Internal screenshots (isFromTest=false): Should ignore failures and return empty response
 * 2. Test screenshots (isFromTest=true): Should fail with proper error message
 * 3. Successful screenshots: Should work in both cases
 */
class ScreenshotServiceTest {

    private lateinit var screenshotProvider: ScreenshotProvider
    private lateinit var screenshotService: ScreenshotService

    @BeforeEach
    fun setup() {
        screenshotProvider = mockk()
        screenshotService = ScreenshotService(screenshotProvider)
    }

    @Nested
    @DisplayName("When screenshot is triggered internally (not from test)")
    inner class InternalScreenshotTests {

        @Test
        @DisplayName("Should return empty response when takeScreenshot returns null")
        fun `internal screenshot returns empty response when takeScreenshot returns null`() {
            // Given
            every { screenshotProvider.takeScreenshot() } returns null

            val responseObserver = mockk<StreamObserver<MaestroAndroid.ScreenshotResponse>>(relaxed = true)
            val request = createScreenshotRequest()

            // When
            screenshotService.screenshot(request, responseObserver)

            // Then
            val responseSlot = slot<MaestroAndroid.ScreenshotResponse>()
            verify { responseObserver.onNext(capture(responseSlot)) }
            verify { responseObserver.onCompleted() }
            verify(exactly = 0) { responseObserver.onError(any()) }

            assertThat(responseSlot.captured.bytes.isEmpty).isTrue()
        }

        @Test
        @DisplayName("Should return empty response when takeScreenshot throws exception")
        fun `internal screenshot returns empty response when takeScreenshot throws exception`() {
            // Given
            every { screenshotProvider.takeScreenshot() } throws NullPointerException("Screenshot failed")

            val responseObserver = mockk<StreamObserver<MaestroAndroid.ScreenshotResponse>>(relaxed = true)
            val request = createScreenshotRequest()

            // When
            screenshotService.screenshot(request, responseObserver)

            // Then
            val responseSlot = slot<MaestroAndroid.ScreenshotResponse>()
            verify { responseObserver.onNext(capture(responseSlot)) }
            verify { responseObserver.onCompleted() }
            verify(exactly = 0) { responseObserver.onError(any()) }

            assertThat(responseSlot.captured.bytes.isEmpty).isTrue()
        }

        @Test
        @DisplayName("Should return screenshot bytes when takeScreenshot succeeds")
        fun `internal screenshot returns bytes when takeScreenshot succeeds`() {
            // Given
            val expectedBytes = byteArrayOf(1, 2, 3, 4, 5)
            every { screenshotProvider.takeScreenshot() } returns expectedBytes

            val responseObserver = mockk<StreamObserver<MaestroAndroid.ScreenshotResponse>>(relaxed = true)
            val request = createScreenshotRequest()

            // When
            screenshotService.screenshot(request, responseObserver)

            // Then
            val responseSlot = slot<MaestroAndroid.ScreenshotResponse>()
            verify { responseObserver.onNext(capture(responseSlot)) }
            verify { responseObserver.onCompleted() }
            verify(exactly = 0) { responseObserver.onError(any()) }

            assertThat(responseSlot.captured.bytes.toByteArray()).isEqualTo(expectedBytes)
        }
    }

    @Nested
    @DisplayName("When screenshot is triggered from Maestro test")
    inner class TestScreenshotTests {

        @Test
        @DisplayName("Should fail with error when takeScreenshot returns null")
        fun `test screenshot fails with error when takeScreenshot returns null`() {
            // Given
            every { screenshotProvider.takeScreenshot() } returns null

            val responseObserver = mockk<StreamObserver<MaestroAndroid.ScreenshotResponse>>(relaxed = true)
            val request = createScreenshotRequest(isFromTest = true)

            // When
            screenshotService.screenshot(request, responseObserver)

            // Then
            verify(exactly = 0) { responseObserver.onNext(any()) }
            verify(exactly = 0) { responseObserver.onCompleted() }

            val errorSlot = slot<Throwable>()
            verify { responseObserver.onError(capture(errorSlot)) }

            val error = errorSlot.captured
            assertThat(error).isInstanceOf(StatusException::class.java)
            val statusException = error as StatusException
            assertThat(statusException.status.code).isEqualTo(Status.Code.INTERNAL)
            assertThat(statusException.status.description).contains("Screenshot failed")
        }

        @Test
        @DisplayName("Should fail with error when takeScreenshot throws exception")
        fun `test screenshot fails with error when takeScreenshot throws exception`() {
            // Given
            every { screenshotProvider.takeScreenshot() } throws NullPointerException("Device screenshot returned null")

            val responseObserver = mockk<StreamObserver<MaestroAndroid.ScreenshotResponse>>(relaxed = true)
            val request = createScreenshotRequest(isFromTest = true)

            // When
            screenshotService.screenshot(request, responseObserver)

            // Then
            verify(exactly = 0) { responseObserver.onNext(any()) }
            verify(exactly = 0) { responseObserver.onCompleted() }

            val errorSlot = slot<Throwable>()
            verify { responseObserver.onError(capture(errorSlot)) }

            val error = errorSlot.captured
            assertThat(error).isInstanceOf(StatusException::class.java)
            val statusException = error as StatusException
            assertThat(statusException.status.code).isEqualTo(Status.Code.INTERNAL)
            assertThat(statusException.status.description).contains("Device screenshot returned null")
        }

        @Test
        @DisplayName("Should return screenshot bytes when takeScreenshot succeeds")
        fun `test screenshot returns bytes when takeScreenshot succeeds`() {
            // Given
            val expectedBytes = byteArrayOf(1, 2, 3, 4, 5)
            every { screenshotProvider.takeScreenshot() } returns expectedBytes

            val responseObserver = mockk<StreamObserver<MaestroAndroid.ScreenshotResponse>>(relaxed = true)
            val request = createScreenshotRequest(isFromTest = true)

            // When
            screenshotService.screenshot(request, responseObserver)

            // Then
            val responseSlot = slot<MaestroAndroid.ScreenshotResponse>()
            verify { responseObserver.onNext(capture(responseSlot)) }
            verify { responseObserver.onCompleted() }
            verify(exactly = 0) { responseObserver.onError(any()) }

            assertThat(responseSlot.captured.bytes.toByteArray()).isEqualTo(expectedBytes)
        }
    }

    @Nested
    @DisplayName("When bitmap compression fails")
    inner class CompressionFailureTests {

        @Test
        @DisplayName("Internal screenshot should return empty response when compression fails")
        fun `internal screenshot returns empty response when compression fails`() {
            // Given
            every { screenshotProvider.takeScreenshot() } throws RuntimeException("Failed to compress bitmap")

            val responseObserver = mockk<StreamObserver<MaestroAndroid.ScreenshotResponse>>(relaxed = true)
            val request = createScreenshotRequest()

            // When
            screenshotService.screenshot(request, responseObserver)

            // Then
            val responseSlot = slot<MaestroAndroid.ScreenshotResponse>()
            verify { responseObserver.onNext(capture(responseSlot)) }
            verify { responseObserver.onCompleted() }
            verify(exactly = 0) { responseObserver.onError(any()) }

            assertThat(responseSlot.captured.bytes.isEmpty).isTrue()
        }

        @Test
        @DisplayName("Test screenshot should fail with error when compression fails")
        fun `test screenshot fails with error when compression fails`() {
            // Given
            every { screenshotProvider.takeScreenshot() } throws RuntimeException("Failed to compress bitmap")

            val responseObserver = mockk<StreamObserver<MaestroAndroid.ScreenshotResponse>>(relaxed = true)
            val request = createScreenshotRequest(isFromTest = true)

            // When
            screenshotService.screenshot(request, responseObserver)

            // Then
            verify(exactly = 0) { responseObserver.onNext(any()) }
            verify(exactly = 0) { responseObserver.onCompleted() }

            val errorSlot = slot<Throwable>()
            verify { responseObserver.onError(capture(errorSlot)) }

            val error = errorSlot.captured
            assertThat(error).isInstanceOf(StatusException::class.java)
            val statusException = error as StatusException
            assertThat(statusException.status.code).isEqualTo(Status.Code.INTERNAL)
            assertThat(statusException.status.description).contains("Failed to compress bitmap")
        }
    }

    private fun createScreenshotRequest(isFromTest: Boolean = false): MaestroAndroid.ScreenshotRequest {
        return MaestroAndroid.ScreenshotRequest.newBuilder()
            .setIsFromTest(isFromTest)
            .build()
    }
}
