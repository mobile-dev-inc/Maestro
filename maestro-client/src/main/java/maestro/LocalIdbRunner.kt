package maestro

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.runCatching
import idb.CompanionServiceGrpc
import idb.HIDEventKt
import idb.Idb
import idb.hIDEvent
import idb.point
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import ios.grpc.BlockingStreamObserver
import ios.idb.IdbRunner
import maestro.debuglog.DebugLogStore
import maestro.utils.MaestroTimer
import org.slf4j.LoggerFactory
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

class LocalIdbRunner(
    val host: String,
    val port: Int,
    val deviceId: String,
) : IdbRunner {
    override fun stop(channel: ManagedChannel) {
        channel.shutdownNow()

        if (!channel.awaitTermination(10, TimeUnit.SECONDS)) {
            throw TimeoutException("Couldn't close Maestro iOS driver due to gRPC timeout")
        }
    }

    override fun start(): ManagedChannel {
        logger.info("startIDBCompanion on $deviceId")

        // idb is associated with a device, it can't be assumed that a running idb_companion is
        // associated with the device under test: Shut down before starting a fresh idb if needed.
        if (isSocketAvailable(host, port)) {
            ProcessBuilder(listOf("killall", "idb_companion")).start().waitFor()
        }

        val idbProcessBuilder = ProcessBuilder("idb_companion", "--udid", deviceId)
        DebugLogStore.logOutputOf(idbProcessBuilder)
        val idbProcess = idbProcessBuilder.start()

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            idbProcess.destroy()
        })

        logger.warn("Waiting for idb service to start..")
        MaestroTimer.retryUntilTrue(timeoutMs = 60000, delayMs = 100) {
            Socket(host, port).use { true }
        } || error("idb_companion did not start in time")

        // The first time a simulator boots up, it can
        // take 10's of seconds to complete.
        logger.warn("Waiting for Simulator to boot..")
        MaestroTimer.retryUntilTrue(timeoutMs = 120000, delayMs = 100) {
            val process = ProcessBuilder("xcrun", "simctl", "bootstatus", deviceId)
                .start()
            process
                .waitFor(1000, TimeUnit.MILLISECONDS)
            process.exitValue() == 0
        } || error("Simulator failed to boot")

        val channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build()

        // Test if idb can get accessibility info elements with non-zero frame width
        logger.warn("Waiting for successful taps")
        MaestroTimer.retryUntilTrue(timeoutMs = 20000, delayMs = 100) {
            testPressAction(channel) is Ok
        } || error("idb_companion is not able dispatch successful tap events")

        logger.warn("Simulator ready")

        return channel
    }

    private fun testPressAction(channel: ManagedChannel): Result<Unit, Throwable> {
        val x = 0
        val y = 0
        val holdDelay = 50L
        val asyncStub = CompanionServiceGrpc.newStub(channel)

        return runCatching {
            val responseObserver = BlockingStreamObserver<Idb.HIDResponse>()
            val stream = asyncStub.hid(responseObserver)

            val pressAction = HIDEventKt.hIDPressAction {
                touch = HIDEventKt.hIDTouch {
                    point = point {
                        this.x = x.toDouble()
                        this.y = y.toDouble()
                    }
                }
            }

            stream.onNext(
                hIDEvent {
                    press = HIDEventKt.hIDPress {
                        action = pressAction
                        direction = Idb.HIDEvent.HIDDirection.DOWN
                    }
                }
            )

            Thread.sleep(holdDelay)

            stream.onNext(
                hIDEvent {
                    press = HIDEventKt.hIDPress {
                        action = pressAction
                        direction = Idb.HIDEvent.HIDDirection.UP
                    }
                }
            )
            stream.onCompleted()

            responseObserver.awaitResult()
        }
    }

    private fun isSocketAvailable(host: String, port: Int): Boolean {
        return try {
            Socket(host, port).use { true }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        val logger = LoggerFactory.getLogger(LocalIdbRunner::class.java)
    }
}
