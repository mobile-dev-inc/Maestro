package maestro.cli.runner

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import maestro.cli.CliError
import maestro.cli.model.TestExecutionSummary
import maestro.cli.report.ReportFormat
import maestro.cli.report.TestSuiteReporter
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.workspace.WorkspaceExecutionPlanner.ExecutionPlan
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Distributes flows across devices using a shared queue (work-stealing).
 *
 * Each device runs as an independent worker: it opens a single Maestro session and consumes
 * flows from the shared queue one at a time until the queue is empty. Devices that finish
 * early pick up remaining flows rather than sitting idle.
 *
 * Fail-fast: when the number of alive workers drops below [minHealthyDevices], the run is
 * aborted with a [CliError] to prevent a single surviving device from running all remaining flows.
 *
 * Re-enqueue: if a worker's session crashes mid-flow, the in-progress flow is returned to the
 * queue and another worker will pick it up (as long as enough healthy devices remain).
 */
class DynamicShardScheduler(
    private val plan: ExecutionPlan,
    private val deviceIds: List<String>,
    private val minHealthyDevices: Int,
    private val env: Map<String, String>,
    private val debugOutputPath: Path,
    private val testOutputDir: Path?,
    private val host: String?,
    private val port: Int?,
    private val teamId: String?,
    private val platform: String?,
    private val isHeadless: Boolean,
    private val screenSize: String?,
    private val reinstallDriver: Boolean,
    private val reporter: TestSuiteReporter,
    private val captureSteps: Boolean,
) {

    private val logger = LoggerFactory.getLogger(DynamicShardScheduler::class.java)

    suspend fun run(): List<TestExecutionSummary> = coroutineScope {
        val flowQueue = Channel<Path>(Channel.UNLIMITED)
        val pending = AtomicInteger(plan.flowsToRun.size)
        val aliveWorkers = AtomicInteger(deviceIds.size)
        val cancelled = AtomicBoolean(false)
        val cancellationReason = AtomicReference<String?>()
        val summaries = Collections.synchronizedList(mutableListOf<TestExecutionSummary>())

        plan.flowsToRun.forEach { flowQueue.trySend(it) }

        val jobs = deviceIds.mapIndexed { workerIndex, deviceId ->
            async(Dispatchers.IO + CoroutineName("worker-$workerIndex")) {
                if (cancelled.get()) return@async

                val driverHostPort = selectPort()
                try {
                    MaestroSessionManager.newSession(
                        host = host,
                        port = port,
                        teamId = teamId,
                        driverHostPort = driverHostPort,
                        deviceId = deviceId,
                        platform = platform,
                        isHeadless = isHeadless,
                        screenSize = screenSize,
                        reinstallDriver = reinstallDriver,
                        executionPlan = plan,
                    ) { session ->
                        val interactor = TestSuiteInteractor(
                            maestro = session.maestro,
                            device = session.device,
                            shardIndex = workerIndex,
                            reporter = reporter,
                            captureSteps = captureSteps,
                        )
                        val summary = runBlocking {
                            interactor.runFromQueue(
                                flowQueue = flowQueue,
                                pending = pending,
                                cancelled = cancelled,
                                onDeviceCrash = { crashedFlow ->
                                    // Return the flow to the queue for another worker to pick up.
                                    flowQueue.trySend(crashedFlow)
                                    decrementAndCheckAlive(workerIndex, deviceId, aliveWorkers, cancelled, cancellationReason)
                                },
                                env = env,
                                debugOutputPath = debugOutputPath,
                                testOutputDir = testOutputDir,
                                deviceId = deviceId,
                            )
                        }
                        summaries.add(summary)
                    }
                } catch (e: Exception) {
                    // Session failed to open (device unreachable at startup).
                    logger.error("[worker-$workerIndex] Session for device $deviceId failed: ${e.message}")
                    decrementAndCheckAlive(workerIndex, deviceId, aliveWorkers, cancelled, cancellationReason)
                }
            }
        }

        jobs.awaitAll()

        cancellationReason.get()?.let { throw CliError(it) }

        summaries
    }

    private fun decrementAndCheckAlive(
        workerIndex: Int,
        deviceId: String,
        aliveWorkers: AtomicInteger,
        cancelled: AtomicBoolean,
        cancellationReason: AtomicReference<String?>,
    ) {
        val alive = aliveWorkers.decrementAndGet()
        logger.warn("[worker-$workerIndex] Device $deviceId is no longer healthy. Alive workers: $alive (minimum: $minHealthyDevices)")
        if (alive < minHealthyDevices && !cancelled.getAndSet(true)) {
            cancellationReason.set(
                "Aborting dynamic run: only $alive healthy device(s) remaining, minimum is $minHealthyDevices"
            )
        }
    }

    private fun selectPort(): Int = ServerSocket(0).use { it.localPort }
}
