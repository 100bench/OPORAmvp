package com.screensaathi.debug

import com.screensaathi.session.ScenarioPhase
import org.json.JSONObject

/** Own-app presentation input only; source text is compared but never serialized. */
internal object OwnHandoffEvidence {
    fun encode(phase: ScenarioPhase?, instruction: CharSequence?, visible: Boolean): String =
        JSONObject().put("phase", phase?.name ?: "UNAVAILABLE")
            .put("card", when {
                !visible -> "HIDDEN"
                instruction?.toString() in SemanticProbe.HANDOFF_TEXTS -> "SAFE_HANDOFF"
                instruction?.toString() == "Проверяю текущий экран…" -> "CHECKING_SCREEN"
                else -> "OTHER"
            }).toString()
}
