package com.screensaathi.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.ScreenReaderService

/** DUMP-protected, debug-only, in-memory result. Never writes files or logs raw data. */
class SemanticProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        resultCode = Activity.RESULT_CANCELED
        if (intent.action != ACTION) return
        try {
            val reader = requireNotNull(ScreenReaderService.instance)
            val snapshot = reader.snapshot()
            require(snapshot.elements.isNotEmpty())
            // Only our known static confirmation control is admitted from overlay windows.
            val controls = mutableListOf<ScreenElement>()
            var visited = 0
            fun walkOwn(node: AccessibilityNodeInfo?) {
                if (node == null || visited++ >= 600 || node.packageName?.toString() != "com.screensaathi") return
                if (node.viewIdResourceName == "com.screensaathi:id/decision_primary_button" && node.text?.toString() == "Начать") {
                    controls.add(ScreenElement(controls.size, "decision_primary_button", "Начать", "Button",
                        Rect().also(node::getBoundsInScreen), false, node.isClickable,
                        enabled = node.isEnabled, visibleToUser = node.isVisibleToUser, password = node.isPassword))
                }
                for (i in 0 until node.childCount) walkOwn(node.getChild(i))
            }
            reader.windows.forEach { walkOwn(it.root) }
            resultData = SemanticProbe.encode(snapshot, ScreenSnapshot("com.screensaathi", true, controls, viewport = snapshot.viewport))
            resultCode = Activity.RESULT_OK
        } catch (_: Exception) {
            // Accessibility and JSON exception details can contain source labels.
            resultData = null
        }
    }

    companion object {
        const val ACTION = "com.screensaathi.PROBE_UI_SEMANTICS"
    }
}
