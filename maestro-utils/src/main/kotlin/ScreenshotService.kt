package maestro.utils

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.stub.StreamObserver
import maestro_android.MaestroAndroid

/**
 * Interface for taking screenshots, allowing for easy mocking and testing.
 * This abstracts away platform-specific dependencies (Android Bitmap, etc.).
 */
interface ScreenshotProvider {
    /**
     * Takes a screenshot and returns the PNG bytes.
     * @return PNG bytes of the screenshot, or null if screenshot failed
     * @throws Exception if screenshot capture fails
     */
    fun takeScreenshot(): ByteArray?
}

/**
 * Service class that handles screenshot requests with proper error handling.
 * This class is designed to be testable by accepting a ScreenshotProvider.
 * 
 * This is a JVM-compatible implementation that can be used in both Android and JVM tests.
 */
class ScreenshotService(
    private val screenshotProvider: ScreenshotProvider
) {
    /**
     * Takes a screenshot and sends the response to the observer.
     *
     * @param request The screenshot request containing isFromTest flag
     * @param responseObserver The gRPC response observer
     *
     * Behavior depends on request.isFromTest:
     * - If true (from Maestro test): failures are reported as gRPC errors
     * - If false (internal use): failures return an empty response silently
     */
    fun screenshot(
        request: MaestroAndroid.ScreenshotRequest,
        responseObserver: StreamObserver<MaestroAndroid.ScreenshotResponse>
    ) {
        if (request.isFromTest) {
            screenshotForTest(responseObserver)
        } else {
            screenshotInternal(responseObserver)
        }
    }

    private fun screenshotForTest(responseObserver: StreamObserver<MaestroAndroid.ScreenshotResponse>) {
        try {
            val bytes = screenshotProvider.takeScreenshot()
            if (bytes == null) {
                responseObserver.onError(
                    Status.INTERNAL.withDescription("Screenshot failed").asException()
                )
                return
            }
            sendSuccessResponse(bytes, responseObserver)
        } catch (e: Exception) {
            responseObserver.onError(
                Status.INTERNAL.withDescription(e.message ?: "Screenshot failed").asException()
            )
        }
    }

    private fun screenshotInternal(responseObserver: StreamObserver<MaestroAndroid.ScreenshotResponse>) {
        try {
            val bytes = screenshotProvider.takeScreenshot()
            if (bytes == null) {
                sendEmptyResponse(responseObserver)
                return
            }
            sendSuccessResponse(bytes, responseObserver)
        } catch (e: Exception) {
            sendEmptyResponse(responseObserver)
        }
    }

    private fun sendSuccessResponse(
        bytes: ByteArray,
        responseObserver: StreamObserver<MaestroAndroid.ScreenshotResponse>
    ) {
        val response = MaestroAndroid.ScreenshotResponse.newBuilder()
            .setBytes(ByteString.copyFrom(bytes))
            .build()
        responseObserver.onNext(response)
        responseObserver.onCompleted()
    }

    private fun sendEmptyResponse(responseObserver: StreamObserver<MaestroAndroid.ScreenshotResponse>) {
        val response = MaestroAndroid.ScreenshotResponse.newBuilder().build()
        responseObserver.onNext(response)
        responseObserver.onCompleted()
    }
}
