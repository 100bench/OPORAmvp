package com.screensaathi.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.screensaathi.OverlayService

/** adb-only debug entry: same text submit path as the card; never logs or stores the query. */
class TextProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        resultCode = Activity.RESULT_CANCELED
        val query = TextProbeRequest.acceptedQuery(intent.action, intent.getStringExtra("query")) ?: return
        val service = OverlayService.live ?: return
        Handler(Looper.getMainLooper()).post { service.submitTextCommand(query) }
        resultCode = Activity.RESULT_OK
    }
}

internal object TextProbeRequest {
    const val ACTION = "com.screensaathi.SUBMIT_TEXT_PROBE"
    fun acceptedQuery(action: String?, query: String?): String? =
        query?.takeIf { action == ACTION && it.isNotBlank() && it.length <= 2000 }
}
