package com.screensaathi.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.screensaathi.ScreenReaderService
import com.screensaathi.screen.UiTreeFixtureCodec
import com.screensaathi.screen.ScreenElementEligibility
import com.screensaathi.screen.ScreenSanitizer
import com.screensaathi.screen.eligibility
import org.json.JSONArray
import java.io.File

/** Debug source set only; DUMP permission restricts invocation to adb/system. */
class FixtureExportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        resultCode = Activity.RESULT_CANCELED
        if (intent.action != ACTION) return
        try {
            val name = intent.getStringExtra("name").orEmpty()
            require(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(name))
            val labelsJson = JSONArray(intent.getStringExtra("approved_labels") ?: "[]")
            require(labelsJson.length() <= 100)
            val approved = (0 until labelsJson.length()).map { i ->
                require(labelsJson.get(i) is String)
                labelsJson.getString(i).also { require(it.length <= 200) }
            }.toSet()
            val snapshot = requireNotNull(ScreenReaderService.instance).snapshot()
            require(snapshot.packageName.isNotBlank() && snapshot.elements.isNotEmpty())
            // Complete the privacy boundary before creating directories or opening any file.
            val sanitized = UiTreeFixtureCodec.encodeSanitized(snapshot, approved)
            val directory = File(context.filesDir, "fixtures")
            check(directory.isDirectory || directory.mkdirs())
            val temporary = File(directory, "$name.tmp")
            try {
                temporary.writeText(sanitized, Charsets.UTF_8)
                check(temporary.renameTo(File(directory, "$name.json")))
            } finally {
                temporary.delete()
            }
            resultCode = Activity.RESULT_OK
            val eligibility = snapshot.elements.groupingBy {
                when (val result = it.eligibility(snapshot.viewportOrLegacy)) {
                    ScreenElementEligibility.Eligible -> "ELIGIBLE"
                    is ScreenElementEligibility.Ineligible -> result.reason.name
                }
            }.eachCount()
            // Geometry/counts only: never log labels, IDs, package names or raw nodes.
            Log.i(TAG, "success elements=${snapshot.elements.count { !it.password }} " +
                "viewport=${snapshot.viewport} eligibility=$eligibility " +
                "providerElements=${ScreenSanitizer.sanitize(snapshot).elements.size} path=files/fixtures/$name.json")
        } catch (_: Exception) {
            // No exception/cause logging: JSON and accessibility errors can contain user data.
            Log.w(TAG, "failure")
        }
    }

    companion object {
        const val ACTION = "com.screensaathi.EXPORT_UI_FIXTURE"
        private const val TAG = "FixtureExport"
    }
}
