package com.screensaathi.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.screensaathi.OverlayService

/**
 * adb-only debug entry: adds no query logging/storage; uses the normal card submit path.
 * Guidance may persist the query as the goal in an encrypted ResumeCapsule. Normal cleanup
 * applies: stop/completion/safe handoff deletes it; expired capsules are deleted on load.
 */
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
