package dev.mobile.maestro.input

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import java.util.concurrent.atomic.AtomicReference

class MaestroInputMethodService : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        activeService.set(this)
        hasActiveInputConnection.set(false)
        Log.i(TAG, "Maestro IME created")
    }

    override fun onCreateInputView(): View {
        Log.i(TAG, "Creating Maestro IME input view")
        return View(this)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        hasActiveInputConnection.set(currentInputConnection != null)
        Log.i(
            TAG,
            "onStartInput package=${attribute?.packageName} restarting=$restarting connected=${hasActiveInputConnection.get()}"
        )
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        hasActiveInputConnection.set(currentInputConnection != null)
        Log.i(
            TAG,
            "onStartInputView package=${info?.packageName} restarting=$restarting connected=${hasActiveInputConnection.get()}"
        )
    }

    override fun onFinishInput() {
        hasActiveInputConnection.set(false)
        Log.i(TAG, "onFinishInput")
        super.onFinishInput()
    }

    override fun onDestroy() {
        activeService.compareAndSet(this, null)
        hasActiveInputConnection.set(false)
        Log.i(TAG, "Maestro IME destroyed")
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    private fun commitTextFromBroadcast(text: String): CommitResult {
        return try {
            val inputConnection = currentInputConnection
            if (inputConnection == null) {
                CommitResult(success = false, message = "No active input connection")
            } else {
                inputConnection.beginBatchEdit()
                val committed = inputConnection.commitText(text, 1)
                val finished = inputConnection.finishComposingText()
                inputConnection.endBatchEdit()

                if (committed) {
                    Log.i(
                        TAG,
                        "Committed ${text.length} chars via Maestro IME finishComposing=$finished"
                    )
                    CommitResult(success = true, message = "Committed ${text.length} chars")
                } else {
                    CommitResult(success = false, message = "commitText returned false")
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to commit text", error)
            CommitResult(success = false, message = error.message ?: error.javaClass.simpleName)
        }
    }

    companion object {
        const val IME_ID = "dev.mobile.maestro/.input.MaestroInputMethodService"
        private const val TAG = "MaestroIME"

        private val activeService = AtomicReference<MaestroInputMethodService?>()
        private val hasActiveInputConnection = AtomicReference(false)

        fun status(): ImeStatus {
            val service = activeService.get()
            val hasConnection = hasActiveInputConnection.get() && service?.currentInputConnection != null
            val message = when {
                service == null -> "Maestro IME is not active"
                !hasConnection -> "No active input connection"
                else -> "Ready"
            }

            return ImeStatus(
                active = service != null,
                ready = hasConnection,
                message = message,
            )
        }

        fun commitText(text: String): CommitResult {
            val imeStatus = status()
            if (!imeStatus.active) {
                return CommitResult(success = false, message = imeStatus.message)
            }
            if (!imeStatus.ready) {
                return CommitResult(success = false, message = imeStatus.message)
            }

            val service = activeService.get()
                ?: return CommitResult(success = false, message = "Maestro IME is not active")

            return service.commitTextFromBroadcast(text)
        }
    }
}

data class CommitResult(
    val success: Boolean,
    val message: String,
)

data class ImeStatus(
    val active: Boolean,
    val ready: Boolean,
    val message: String,
)
