package dev.mobile.maestro.screenshot

import android.graphics.Bitmap
import android.util.Log
import com.google.protobuf.ByteString
import dev.mobile.maestro.internalError
import io.grpc.stub.StreamObserver
import maestro_android.MaestroAndroid
import maestro_android.screenshotResponse

/**
 * Service that handles screenshot requests with proper error handling.
 * Designed for testability by accepting a screenshot provider function.
 *
 * @param takeScreenshot Function that captures a screenshot and returns a Bitmap, or null on failure
 */
class ScreenshotService(
    private val takeScreenshot: () -> Bitmap?
) {

    /**
     * Takes a screenshot and sends the response to the observer.
     *
     * Behavior depends on [MaestroAndroid.ScreenshotRequest.getShouldFailOnError]:
     * - `true`: Failures are reported as gRPC INTERNAL errors
     * - `false`: Failures return an empty response silently
     */
    fun screenshot(
        request: MaestroAndroid.ScreenshotRequest,
        responseObserver: StreamObserver<MaestroAndroid.ScreenshotResponse>
    ) {
        val shouldFailOnError = try {
            request.shouldFailOnError
        } catch (e: Exception) {
            Log.w(TAG, "Error accessing shouldFailOnError, defaulting to false: ${e.message}")
            false
        }
       
        try {
            val bitmap = takeScreenshot()
            if (bitmap == null) {
                handleFailure(responseObserver, shouldFailOnError, "Screenshot capture returned null")
                return
            }

            val outputStream = ByteString.newOutput()
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                sendResponse(responseObserver, outputStream.toByteString())
            } else {
                handleFailure(responseObserver, shouldFailOnError, "Failed to compress bitmap")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Exception during screenshot: ${e.message}, Type: ${e.javaClass.name}")
            handleFailure(responseObserver, shouldFailOnError, e)
        }
    }

    private fun handleFailure(
        responseObserver: StreamObserver<MaestroAndroid.ScreenshotResponse>,
        shouldFailOnError: Boolean,
        errorMessage: String
    ) {
        if (shouldFailOnError) {
            responseObserver.onError(Throwable(errorMessage).internalError())
        } else {
            sendResponse(responseObserver, bytes = null)
        }
    }

    private fun handleFailure(
        responseObserver: StreamObserver<MaestroAndroid.ScreenshotResponse>,
        shouldFailOnError: Boolean,
        cause: Throwable
    ) {
        if (shouldFailOnError) {
            responseObserver.onError(cause.internalError())
        } else {
            sendResponse(responseObserver, bytes = null)
        }
    }

    private fun sendResponse(
        responseObserver: StreamObserver<MaestroAndroid.ScreenshotResponse>,
        bytes: ByteString?
    ) {
        responseObserver.onNext(screenshotResponse {
            if (bytes != null) {
                this.bytes = bytes
            }
        })
        responseObserver.onCompleted()
    }

    companion object {
        private const val TAG = "Maestro"
    }
}
