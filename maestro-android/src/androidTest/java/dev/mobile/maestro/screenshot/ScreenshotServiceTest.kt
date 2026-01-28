package dev.mobile.maestro.screenshot

import android.graphics.Bitmap
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import dev.mobile.maestro.screenshot.ScreenshotService
import maestro_android.MaestroAndroid
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for ScreenShotService error handling.
 *
 * Verifies behavior for:
 * - Null bitmap returns
 * - Exception during screenshot capture
 * - Successful screenshot capture
 *
 * With both shouldFailOnError=true (test mode) and shouldFailOnError=false (internal mode).
 */
class ScreenshotServiceTest {

    private class TestStreamObserver : StreamObserver<MaestroAndroid.ScreenshotResponse> {
        var onNextCalled = false
        var onErrorCalled = false
        var onCompletedCalled = false
        var capturedResponse: MaestroAndroid.ScreenshotResponse? = null
        var capturedError: Throwable? = null

        override fun onNext(value: MaestroAndroid.ScreenshotResponse) {
            onNextCalled = true
            capturedResponse = value
        }

        override fun onError(t: Throwable) {
            onErrorCalled = true
            capturedError = t
        }

        override fun onCompleted() {
            onCompletedCalled = true
        }
    }

    private fun createRequest(shouldFailOnError: Boolean): MaestroAndroid.ScreenshotRequest {
        return MaestroAndroid.ScreenshotRequest.newBuilder()
            .setShouldFailOnError(shouldFailOnError)
            .build()
    }

    @Test
    fun nullBitmap_withShouldFailOnErrorTrue_propagatesError() {
        val service = ScreenshotService { null }
        val responseObserver = TestStreamObserver()

        service.screenshot(createRequest(shouldFailOnError = true), responseObserver)

        assertFalse(responseObserver.onNextCalled)
        assertFalse(responseObserver.onCompletedCalled)
        assertTrue(responseObserver.onErrorCalled)

        val error = responseObserver.capturedError as StatusException
        assertEquals(Status.Code.INTERNAL, error.status.code)
        assertTrue(error.status.description?.contains("null", ignoreCase = true) == true)
    }

    @Test
    fun nullBitmap_withShouldFailOnErrorFalse_returnsEmptyResponse() {
        val service = ScreenshotService { null }
        val responseObserver = TestStreamObserver()

        service.screenshot(createRequest(shouldFailOnError = false), responseObserver)

        assertTrue(responseObserver.onNextCalled)
        assertTrue(responseObserver.onCompletedCalled)
        assertFalse(responseObserver.onErrorCalled)
        assertTrue(responseObserver.capturedResponse!!.bytes.isEmpty)
    }

    @Test
    fun exception_withShouldFailOnErrorTrue_propagatesError() {
        val testMessage = "Test exception message"
        val service = ScreenshotService { throw RuntimeException(testMessage) }
        val responseObserver = TestStreamObserver()

        service.screenshot(createRequest(shouldFailOnError = true), responseObserver)

        assertFalse(responseObserver.onNextCalled)
        assertFalse(responseObserver.onCompletedCalled)
        assertTrue(responseObserver.onErrorCalled)

        val error = responseObserver.capturedError as StatusException
        assertEquals(Status.Code.INTERNAL, error.status.code)
        assertTrue(error.status.description?.contains(testMessage) == true)
    }

    @Test
    fun exception_withShouldFailOnErrorFalse_returnsEmptyResponse() {
        val service = ScreenshotService { throw RuntimeException("Test exception") }
        val responseObserver = TestStreamObserver()

        service.screenshot(createRequest(shouldFailOnError = false), responseObserver)

        assertTrue(responseObserver.onNextCalled)
        assertTrue(responseObserver.onCompletedCalled)
        assertFalse(responseObserver.onErrorCalled)
        assertTrue(responseObserver.capturedResponse!!.bytes.isEmpty)
    }

    @Test
    fun successfulBitmap_withShouldFailOnErrorTrue_returnsImage() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val service = ScreenshotService { bitmap }
        val responseObserver = TestStreamObserver()

        service.screenshot(createRequest(shouldFailOnError = true), responseObserver)

        assertTrue(responseObserver.onNextCalled)
        assertTrue(responseObserver.onCompletedCalled)
        assertFalse(responseObserver.onErrorCalled)
        assertTrue(responseObserver.capturedResponse!!.bytes.size() > 0)
    }

    @Test
    fun successfulBitmap_withShouldFailOnErrorFalse_returnsImage() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val service = ScreenshotService { bitmap }
        val responseObserver = TestStreamObserver()

        service.screenshot(createRequest(shouldFailOnError = false), responseObserver)

        assertTrue(responseObserver.onNextCalled)
        assertTrue(responseObserver.onCompletedCalled)
        assertFalse(responseObserver.onErrorCalled)
        assertTrue(responseObserver.capturedResponse!!.bytes.size() > 0)
    }
}
