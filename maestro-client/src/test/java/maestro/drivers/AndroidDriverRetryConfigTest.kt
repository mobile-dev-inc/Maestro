package maestro.drivers

import com.google.common.truth.Truth.assertThat
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import maestro_android.MaestroAndroid.DeviceInfo
import maestro_android.MaestroAndroid.DeviceInfoRequest
import maestro_android.MaestroDriverGrpc
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Validates the gRPC channel retry policy applied in [AndroidDriver]: with
 * [io.grpc.ManagedChannelBuilder.enableRetry] plus the service config in
 * [AndroidDriver.Companion.GRPC_RETRY_SERVICE_CONFIG], an UNAVAILABLE response is retried
 * up to maxAttempts before bubbling up to the caller.
 *
 * Uses an in-process gRPC server with a fake [MaestroDriverGrpc.MaestroDriverImplBase] so we
 * exercise the real retry machinery without standing up a TCP server or a device.
 */
class AndroidDriverRetryConfigTest {

    private lateinit var server: Server
    private lateinit var serverName: String
    private val attempts = AtomicInteger(0)
    private var failTimes = 0
    private var failWith: Status = Status.UNAVAILABLE

    @BeforeEach
    fun setUp() {
        serverName = "maestro-test-${UUID.randomUUID()}"
        val service = object : MaestroDriverGrpc.MaestroDriverImplBase() {
            override fun deviceInfo(request: DeviceInfoRequest, observer: StreamObserver<DeviceInfo>) {
                val attempt = attempts.incrementAndGet()
                if (attempt <= failTimes) {
                    observer.onError(failWith.withDescription("forced failure attempt $attempt").asRuntimeException())
                    return
                }
                observer.onNext(DeviceInfo.newBuilder().setWidthPixels(100).setHeightPixels(200).build())
                observer.onCompleted()
            }
        }
        server = InProcessServerBuilder.forName(serverName).addService(service).directExecutor().build().start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun newChannelWithRetryConfig() = InProcessChannelBuilder
        .forName(serverName)
        .directExecutor()
        .enableRetry()
        .defaultServiceConfig(AndroidDriver.GRPC_RETRY_SERVICE_CONFIG)
        .build()

    @Test
    fun `UNAVAILABLE is retried and call succeeds before maxAttempts`() {
        failTimes = 2 // succeed on attempt 3, within maxAttempts=4
        val channel = newChannelWithRetryConfig()
        try {
            val stub = MaestroDriverGrpc.newBlockingStub(channel)
            val response = stub.deviceInfo(DeviceInfoRequest.getDefaultInstance())
            assertThat(response.widthPixels).isEqualTo(100)
            assertThat(attempts.get()).isEqualTo(3)
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `UNAVAILABLE retries exhaust at maxAttempts and bubble the last failure`() {
        failTimes = Int.MAX_VALUE // never succeed
        val channel = newChannelWithRetryConfig()
        try {
            val stub = MaestroDriverGrpc.newBlockingStub(channel)
            val ex = assertThrows<StatusRuntimeException> {
                stub.deviceInfo(DeviceInfoRequest.getDefaultInstance())
            }
            assertThat(ex.status.code).isEqualTo(Status.Code.UNAVAILABLE)
            // maxAttempts=4 in GRPC_RETRY_SERVICE_CONFIG: initial + 3 retries.
            assertThat(attempts.get()).isEqualTo(4)
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `non-retryable status codes are not retried`() {
        failTimes = Int.MAX_VALUE
        failWith = Status.INVALID_ARGUMENT
        val channel = newChannelWithRetryConfig()
        try {
            val stub = MaestroDriverGrpc.newBlockingStub(channel)
            val ex = assertThrows<StatusRuntimeException> {
                stub.deviceInfo(DeviceInfoRequest.getDefaultInstance())
            }
            assertThat(ex.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
            assertThat(attempts.get()).isEqualTo(1)
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
        }
    }
}
