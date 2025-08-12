package maestro.test

import maestro.js.JsEngine
import maestro.orchestra.FlowController
import kotlinx.coroutines.channels.Channel

class FlowControllerTest : FlowController {
    private var _isPaused = false
    private val pauseChannel = Channel<Unit>()
    private val resumeChannel = Channel<Unit>()

    override suspend fun waitIfPaused() {
        if (_isPaused) {
            // Wait for resume signal
            resumeChannel.receive()
        }
    }

    override fun pause() {
        _isPaused = true
        pauseChannel.trySend(Unit)
    }

    override fun resume() {
        _isPaused = false
        resumeChannel.trySend(Unit)
    }

    override val isPaused: Boolean get() = _isPaused

    // Test helper methods
    suspend fun waitForPause() {
        pauseChannel.receive()
    }

    suspend fun waitForResume() {
        resumeChannel.receive()
    }
} 