package conductor.android

import dadb.Dadb
import okio.BufferedSink
import okio.Source
import okio.buffer
import okio.sink
import okio.source
import org.slf4j.LoggerFactory
import java.io.InterruptedIOException
import java.net.ServerSocket
import kotlin.concurrent.thread

class DadbForwarder(
    private val dadb: Dadb,
    private val hostPort: Int,
    private val targetPort: Int,
) : AutoCloseable {

    private var state: State = State.STOPPED
    private var thread: Thread? = null

    fun start() {
        if (state != State.STOPPED) {
            throw IllegalStateException("Forwarder is already started at port $hostPort")
        }

        moveToState(State.STARTING)
        thread = thread {
            try {
                handleForwarding()
            } finally {
                moveToState(State.STOPPED)
            }
        }

        waitFor(10, 5000) {
            state == State.STARTED
        }
    }

    private fun handleForwarding() {
        val adbStream = dadb.open("tcp:$targetPort")

        val server = ServerSocket(hostPort)
        LOGGER.info("Forwarder started at port $hostPort targeting port $targetPort")

        moveToState(State.STARTED)

        val client = server.accept()
        LOGGER.info("Forwarder received a connection at port $hostPort")

        val readerThread = thread {
            forward(
                client.getInputStream().source(),
                adbStream.sink
            )
        }

        val writerThread = thread {
            forward(
                adbStream.source,
                client.sink().buffer()
            )
        }

        while (!Thread.interrupted()) {
            try {
                Thread.sleep(500)
            } catch (ignored: InterruptedException) {
                LOGGER.info("Forwarder at port $hostPort received an interruption")
                break
            }
        }

        readerThread.interrupt()
        writerThread.interrupt()

        LOGGER.info("Forwarder stopped at port $hostPort")
    }

    override fun close() {
        if (state == State.STOPPED || state == State.STOPPING) {
            return
        }

        moveToState(State.STOPPING)
        thread?.interrupt()
        thread = null

        waitFor(10, 5000) {
            state == State.STOPPED
        }
    }

    private fun forward(source: Source, sink: BufferedSink) {
        try {
            while (!Thread.interrupted()) {
                source.read(sink.buffer, 256)
                sink.flush()
            }
        } catch (ignored: InterruptedException) {
            // Do nothing
        } catch (ignored: InterruptedIOException) {
            // do nothing
        }
    }

    private fun moveToState(state: State) {
        this.state = state
        LOGGER.info("Forwarder at port $hostPort moved to state $state")
    }

    private enum class State {
        STARTING,
        STARTED,
        STOPPING,
        STOPPED
    }

    private fun waitFor(intervalMs: Int, timeoutMs: Int, test: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        var lastCheck = start
        while (!test()) {
            val now = System.currentTimeMillis()
            val timeSinceStart = now - start
            val timeSinceLastCheck = now - lastCheck
            if (timeoutMs in 0..timeSinceStart) {
                return false
            }
            val sleepTime = intervalMs - timeSinceLastCheck
            if (sleepTime > 0) {
                Thread.sleep(sleepTime)
            }
            lastCheck = System.currentTimeMillis()
        }
        return true
    }

    companion object {

        private val LOGGER = LoggerFactory.getLogger(DadbForwarder::class.java)
    }
}
