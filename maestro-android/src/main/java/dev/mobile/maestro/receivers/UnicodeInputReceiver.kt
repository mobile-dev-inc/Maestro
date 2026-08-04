package dev.mobile.maestro.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import dev.mobile.maestro.input.MaestroInputMethodService

class UnicodeInputReceiver : BroadcastReceiver(), HasAction {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STATUS) {
            val status = MaestroInputMethodService.status()
            resultCode = if (status.ready) RESULT_OK else RESULT_ERROR
            resultData = status.message
            Log.i(TAG, "IME status active=${status.active} ready=${status.ready} message=${status.message}")
            return
        }

        val encodedText = intent.getStringExtra(EXTRA_TEXT_BASE64)
        if (encodedText.isNullOrEmpty()) {
            resultCode = RESULT_ERROR
            resultData = "Missing text payload"
            return
        }

        val decodedText = try {
            String(
                Base64.decode(encodedText, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8
            )
        } catch (error: IllegalArgumentException) {
            resultCode = RESULT_ERROR
            resultData = "Invalid Base64 payload"
            return
        }

        val result = MaestroInputMethodService.commitText(decodedText)
        resultCode = if (result.success) RESULT_OK else RESULT_ERROR
        resultData = result.message
        Log.i(TAG, "Unicode commit result=${result.success} message=${result.message}")
    }

    override fun action(): String {
        return ACTION_COMMIT
    }

    companion object {
        const val ACTION_COMMIT = "dev.mobile.maestro.ime.commitText"
        const val ACTION_STATUS = "dev.mobile.maestro.ime.status"
        const val EXTRA_TEXT_BASE64 = "textBase64"
        private const val TAG = "MaestroIME"

        private const val RESULT_OK = 0
        private const val RESULT_ERROR = 1
    }
}
