package com.screensaathi.circle

import android.util.Log
import com.screensaathi.screen.ScreenSnapshot

/**
 * Holds one circle interaction and resolves it exclusively from the live
 * accessibility UI tree.
 *
 * [capture] and [vision] remain constructor-only compatibility seams so tests
 * can prove even an available pixel provider is unreachable in the MVP. They
 * are deliberately not retained: after construction there is no runtime path
 * from this session to screenshot capture or a vision request.
 */
@Suppress("UNUSED_PARAMETER")
class CircleSession(
    capture: ScreenCaptureProvider = UnavailableScreenCaptureProvider,
    vision: VisionProvider = NoOpVisionProvider,
    private val snapshotProvider: () -> ScreenSnapshot?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    var context: CircleContext? = null
        private set

    /**
     * Resolve a completed selection synchronously against the current UI tree.
     * A missing tree is a normal unavailable result; pixels are never used as
     * a fallback.
     */
    fun onSelectionDrawn(
        path: List<SelectionPoint>,
        shape: SelectionShape,
        languageCode: String,
        onResolved: (CircleContext) -> Unit,
    ) {
        val snapshot = snapshotProvider()
        if (snapshot == null) {
            Log.w(TAG, "no accessibility snapshot; selection cannot be resolved")
        }

        val selection = ScreenSelection.fromPath(
            shape = shape,
            path = path,
            packageName = snapshot?.packageName.orEmpty(),
            screenSignature = snapshot?.signature().orEmpty(),
            capturedAtMs = clock(),
        )
        val target = if (snapshot != null) {
            SelectionResolver.resolve(selection, snapshot)
        } else {
            EMPTY_TARGET
        }

        val resolved = CircleContext(
            selection = selection,
            target = target,
            frame = null,
            languageCode = languageCode,
        )
        context = resolved
        onResolved(resolved)
    }

    fun addTurn(request: String, intent: CircleIntent, response: String) {
        context = context?.withTurn(request, intent, response, clock())
    }

    fun setActiveTask(taskId: String?) {
        context = context?.withTask(taskId)
    }

    /**
     * Visual understanding is outside the tree-only MVP. Keep the old method
     * as an honest local result for callers compiled against the earlier API;
     * it cannot reach [vision].
     */
    fun analyzeVisually(userPrompt: String, onResult: (VisionResult) -> Unit) {
        onResult(VisionResult.Unavailable(VisionResult.Unavailable.Reason.NO_PIXELS))
    }

    fun clear() {
        context = null
    }

    companion object {
        private const val TAG = "CircleSession"

        private val EMPTY_TARGET = SelectionResolver.SelectedTarget(
            element = null,
            confidence = 0,
            alternatives = emptyList(),
            selectedText = "",
            possibleActions = emptySet(),
            surroundingContext = "",
            ambiguous = false,
            reason = "the accessibility service was not available",
        )
    }
}
