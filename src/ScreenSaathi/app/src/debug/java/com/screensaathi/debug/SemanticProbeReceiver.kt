package com.screensaathi.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import com.screensaathi.overlay.AssistantPresentation
import com.screensaathi.OverlayService
import com.screensaathi.R
import com.screensaathi.session.SessionController
import com.screensaathi.session.UniversalGuidanceLoop
import org.json.JSONObject
import android.view.accessibility.AccessibilityNodeInfo
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.screen.ScreenViewport
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
                if (node.viewIdResourceName == "com.screensaathi:id/instruction_text" &&
                    node.text?.toString() in SemanticProbe.HANDOFF_TEXTS) {
                    controls.add(ScreenElement(controls.size, "instruction_text", SemanticProbe.HANDOFF_TEXTS.first { it == node.text?.toString() }, "TextView",
                        Rect().also(node::getBoundsInScreen), false, false,
                        enabled = node.isEnabled, visibleToUser = node.isVisibleToUser, password = node.isPassword))
                }
                for (i in 0 until node.childCount) walkOwn(node.getChild(i))
            }
            var assistantViewport: ScreenViewport? = null
            reader.windows.forEach { window ->
                val previousSize = controls.size
                walkOwn(window.root)
                if (controls.size > previousSize) {
                    val bounds = Rect().also(window::getBoundsInScreen)
                    assistantViewport = ScreenViewport(bounds.left, bounds.top, bounds.right, bounds.bottom)
                }
            }
            val presentation = try {
                // Observe only known own objects. No production hook or third-party value is read.
                val controller = requireNotNull(SessionController.instance)
                val getter = SessionController::class.java.getDeclaredMethod("getUniversalLoop").apply { isAccessible = true }
                val phase = (getter.invoke(controller) as UniversalGuidanceLoop).session.phase
                val overlay = requireNotNull(OverlayService.live)
                val field = OverlayService::class.java.getDeclaredField("pillRoot").apply { isAccessible = true }
                val instruction = (field.get(overlay) as View).findViewById<TextView>(R.id.instruction_text)
                val modelField = OverlayService::class.java.getDeclaredField("presentation").apply { isAccessible = true }
                val model = modelField.get(overlay) as AssistantPresentation
                JSONObject(OwnHandoffEvidence.encode(phase, instruction.text,
                    instruction.isShown && instruction.getGlobalVisibleRect(Rect())))
                    .put("modelCard", JSONObject(OwnHandoffEvidence.encode(phase,
                        model.command.instruction, model.cardVisible)).getString("card"))
                    .put("viewMatchesHandoffResource", instruction.text.toString() == context.getString(R.string.safe_handoff_message))
                    .put("modelMatchesHandoffResource", model.command.instruction == context.getString(R.string.safe_handoff_message))
                    .put("viewMatchesFinalActionHandoff", instruction.text.toString() == SemanticProbe.FINAL_ACTION_HANDOFF_TEXT)
                    .put("modelMatchesFinalActionHandoff", model.command.instruction == SemanticProbe.FINAL_ACTION_HANDOFF_TEXT)
                    .put("resourceMatchesKnownHandoff", context.getString(R.string.safe_handoff_message) == SemanticProbe.HANDOFF_TEXT)
                    .toString()
            } catch (_: Exception) {
                OwnHandoffEvidence.encode(null, null, false)
            }
            resultData = JSONObject(SemanticProbe.encode(snapshot,
                ScreenSnapshot("com.screensaathi", true, controls, viewport = assistantViewport)))
                .put("ownPresentation", JSONObject(presentation)).toString()
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
