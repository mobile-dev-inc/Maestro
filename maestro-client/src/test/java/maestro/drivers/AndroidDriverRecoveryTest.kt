package maestro.drivers

import com.google.common.truth.Truth.assertThat
import dadb.Dadb
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import io.mockk.verify
import maestro.android.AdbSocketFactory
import maestro_android.MaestroAndroid.DeviceInfo
import maestro_android.MaestroAndroid.DeviceInfoRequest
import maestro_android.MaestroDriverGrpc
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Tests the recovery logic in [AndroidDriver.runDeviceCall].
 *
 * When a gRPC call fails with UNAVAILABLE caused by an IOException (e.g. ADB socket closed /
 * maestro-driver-android not running), the driver must call [AndroidDriver.recoverAndroidGrpcConnection]
 * and retry exactly once.
 *
 * IOException-cause tests use an OkHttp channel backed by an [AdbSocketFactory] that always throws
 * [IOException] — this mirrors the real production path where the ADB socket returns "closed" and
 * OkHttp wraps it as UNAVAILABLE with the IOException as its Java cause.
 *
 * Non-IOException tests use an in-process gRPC server (which strips the cause by design, matching
 * what a plain server-sent UNAVAILABLE looks like to the client).
 */
class AndroidDriverRecoveryTest {

    private lateinit var workingServerName: String
    private val serversToStop = mutableListOf<Server>()

    @BeforeEach
    fun setUp() {
        workingServerName = "maestro-recovery-${UUID.randomUUID()}"
        serversToStop += InProcessServerBuilder.forName(workingServerName)
            .addService(alwaysSucceedService())
            .directExecutor()
            .build()
            .start()
    }

    @AfterEach
    fun tearDown() {
        serversToStop.forEach { it.shutdownNow().awaitTermination(2, TimeUnit.SECONDS) }
        serversToStop.clear()
    }

    // --------------- IOException-cause tests (OkHttp transport failure) ---------------

    @Test
    fun `recovery is triggered when UNAVAILABLE is caused by IOException`() {
        val driver = driverWithBrokenChannel()
        every { driver.recoverAndroidGrpcConnection() } just runs

        assertThrows<StatusRuntimeException> { driver.deviceInfo() }

        verify(exactly = 1) { driver.recoverAndroidGrpcConnection() }
    }

    @Test
    fun `deviceInfo succeeds on retry after recovery replaces the channel`() {
        val driver = driverWithBrokenChannel()
        every { driver.recoverAndroidGrpcConnection() } answers call@{
            driver.channel = InProcessChannelBuilder.forName(workingServerName).directExecutor().build()
        }

        val info = driver.deviceInfo()

        assertThat(info.widthPixels).isEqualTo(1080)
        verify(exactly = 1) { driver.recoverAndroidGrpcConnection() }
    }

    // --------------- No-cause / other-status tests (in-process gRPC) ---------------

    @Test
    fun `recovery is NOT triggered when UNAVAILABLE has no IOException cause`() {
        val driver = driverWithInProcessService { obs ->
            obs.onError(Status.UNAVAILABLE.asRuntimeException())
        }
        every { driver.recoverAndroidGrpcConnection() } just runs

        assertThrows<StatusRuntimeException> { driver.deviceInfo() }

        verify(exactly = 0) { driver.recoverAndroidGrpcConnection() }
    }

    @Test
    fun `recovery is NOT triggered for DEADLINE_EXCEEDED`() {
        val driver = driverWithInProcessService { obs ->
            obs.onError(Status.DEADLINE_EXCEEDED.asRuntimeException())
        }
        every { driver.recoverAndroidGrpcConnection() } just runs

        assertThrows<StatusRuntimeException> { driver.deviceInfo() }

        verify(exactly = 0) { driver.recoverAndroidGrpcConnection() }
    }

    // --------------- Helpers ---------------

    private fun alwaysSucceedService() = object : MaestroDriverGrpc.MaestroDriverImplBase() {
        override fun deviceInfo(request: DeviceInfoRequest, observer: StreamObserver<DeviceInfo>) {
            observer.onNext(DeviceInfo.newBuilder().setWidthPixels(1080).setHeightPixels(1920).build())
            observer.onCompleted()
        }
    }

    /** Driver whose channel uses an AdbSocketFactory that always throws IOException on connect. */
    private fun driverWithBrokenChannel(): AndroidDriver {
        val driver = spyk(AndroidDriver(mockk<Dadb>(relaxed = true)))
        driver.channel = OkHttpChannelBuilder.forAddress("localhost", 7001)
            .usePlaintext()
            .socketFactory(AdbSocketFactory { _, port ->
                throw IOException("Command failed (tcp:$port): closed")
            })
            .build()
        return driver
    }

    /** Driver whose channel sends the given status for every deviceInfo call. */
    private fun driverWithInProcessService(
        behavior: (StreamObserver<DeviceInfo>) -> Unit,
    ): AndroidDriver {
        val name = "maestro-test-${UUID.randomUUID()}"
        val service = object : MaestroDriverGrpc.MaestroDriverImplBase() {
            override fun deviceInfo(req: DeviceInfoRequest, obs: StreamObserver<DeviceInfo>) = behavior(obs)
        }
        serversToStop += InProcessServerBuilder.forName(name).addService(service).directExecutor().build().start()
        val driver = spyk(AndroidDriver(mockk<Dadb>(relaxed = true)))
        driver.channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        return driver
    }
}
