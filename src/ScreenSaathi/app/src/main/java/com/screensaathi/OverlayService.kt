package com.screensaathi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.screensaathi.overlay.AssistantUiState
import com.screensaathi.overlay.AssistantPresentation
import com.screensaathi.overlay.DecisionButtons
import com.screensaathi.overlay.DecisionChoice
import com.screensaathi.overlay.HighlightView
import com.screensaathi.overlay.OverlayCommand
import com.screensaathi.overlay.PillLabels
import com.screensaathi.overlay.PillState
import com.screensaathi.sarvam.Language
import com.screensaathi.session.SessionController

/**
 * The overlay renderer. Owns two WindowManager windows:
 *   1. a full-screen, touch-through highlight layer
 *   2. the interactive pill / assistant card
 *
 * It renders an OverlayCommand (contracts/overlay.schema.json) and nothing more
 * — all reasoning lives in SessionController. UI taps are forwarded to the
 * controller; the controller pushes commands back.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())

    private lateinit var highlightView: HighlightView
    private lateinit var pillRoot: View
    private lateinit var cardBody: ScrollView
    private lateinit var cardContent: LinearLayout
    private lateinit var pillLabel: TextView
    private lateinit var instructionText: TextView
    private lateinit var micButton: View
    private lateinit var transportRow: View
    private lateinit var waveform: com.screensaathi.overlay.VoiceWaveformView
    private lateinit var languageChip: TextView
    private lateinit var debugPanel: TextView
    private lateinit var choiceRow: LinearLayout
    private lateinit var choiceButtons: List<TextView>
    private lateinit var decisionRow: LinearLayout
    private lateinit var decisionPrimaryButton: TextView
    private lateinit var decisionSecondaryButton: TextView
    private lateinit var textCommand: EditText
    private lateinit var textCommandRow: View
    private lateinit var voiceSlot: View
    private lateinit var removalPrompt: View

    private var expanded = false
    private var expansionRequestedDuringDrag: Boolean? = null
    private var presentation = AssistantPresentation()
    private var debugVisible = false
    private var voiceActive = false
    private var textInputActive = false
    private var textInputSawKeyboard = false
    private var levelPump: Runnable? = null

    /** Single source of truth for presentation — see AssistantUiState. */
    private var uiState: AssistantUiState = AssistantUiState.COLLAPSED
    private var stateBeforeDrag: AssistantUiState = AssistantUiState.COLLAPSED

    /** The window params we own, so drag/placement writes one object. */
    private var pillParams: WindowManager.LayoutParams? = null

    /** Present only while the user is drawing a selection. */
    private var selectionView: com.screensaathi.circle.CircleSelectionView? = null

    /** Where the *user* put it. The keyboard may move the live window off
     *  this temporarily; this is what it returns to. */
    private var userX = 0
    private var userY = 0
    /** True while the assistant card is temporarily moved away from a target. */
    private var avoidingHighlight = false
    private var dockAnimator: android.animation.ValueAnimator? = null
    private lateinit var minimizedDot: View

    private lateinit var controller: SessionController

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Idempotent by construction, the way Tappr's OverlayModule guards
        // showBubble() with `if (notchRoot != null) return`. A per-instance
        // flag would not catch the actual risk here — a fresh Service object
        // has fresh fields regardless — so this lives on the companion
        // object, shared across any Service instance in this process. Two
        // startForegroundService() calls racing on some OEM scheduler before
        // the first onCreate() finishes would otherwise register two pill
        // windows and two highlight windows, silently, with no crash to
        // notice by.
        if (windowsAdded) return
        windowsAdded = true

        live = this
        addHighlightWindow()
        addPillWindow()
        controller = SessionController(
            applicationContext,
            render = { cmd -> main.post { render(cmd) } },
            // A screen-transition invalidation must never play the normal
            // fly-home clear — that would animate toward a target that is no
            // longer on screen. This bypasses render()/OverlayCommand and
            // drops the ring directly.
            clearHighlightInstant = { main.post { highlightView.clearInstant() } },
        )
        controller.debugSink = SessionController.DebugSink { text -> main.post { updateDebug(text) } }
        render(OverlayCommand(PillState.IDLE, expanded = false))
        controller.offerResumeIfAvailable()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Rehearsal entry point: run a task without speaking. Same engine,
        // overlay and cursor as a spoken request — only STT is skipped.
        when (intent?.action) {
            ACTION_RUN_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_STICKY
                val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: Language.APP_DEFAULT
                main.post { controller.startTaskById(taskId, language) }
            }
            // Pick an option without touching the screen. Same code path as the
            // button, so a rehearsal exercises the real thing.
            // Point at a named element on the CURRENT screen. Same resolver and
            // same overlay the voice path uses; only STT is skipped, so this is
            // a real exercise of the pipeline rather than a test double.
            ACTION_HIGHLIGHT -> {
                val q = intent.getStringExtra(EXTRA_QUERY).orEmpty()
                if (q.isNotBlank()) main.post { controller.highlightTarget(q) }
            }
            ACTION_CLEAR_HIGHLIGHT -> main.post { controller.clearHighlight() }
            ACTION_CHOOSE -> {
                val index = intent.getIntExtra(EXTRA_CHOICE, -1)
                if (index >= 0) main.post { controller.onChoiceTapped(index) }
            }
            ACTION_START_SELECTION -> main.post { enterSelectionMode() }
            ACTION_CANCEL_SELECTION -> main.post { exitSelectionMode() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Window setup ---------------------------------------------------------

    private fun addHighlightWindow() {
        highlightView = HighlightView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        // Android 12+ blocks pass-through touches beneath a full-screen
        // TYPE_APPLICATION_OVERLAY whose obscuring opacity is above 0.8, even
        // with FLAG_NOT_TOUCHABLE. Keep this visual-only layer below that
        // threshold so the highlighted app control remains tappable.
        lp.alpha = 0.75f
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(highlightView, lp)
    }

    private fun addPillWindow() {
        pillRoot = LayoutInflater.from(this).inflate(R.layout.overlay_pill, null)
        cardBody = pillRoot.findViewById(R.id.card_body)
        cardContent = pillRoot.findViewById(R.id.card_content)
        pillLabel = pillRoot.findViewById(R.id.pill_label)
        instructionText = pillRoot.findViewById(R.id.instruction_text)
        micButton = pillRoot.findViewById(R.id.mic_button)
        transportRow = pillRoot.findViewById(R.id.transport_row)
        waveform = pillRoot.findViewById(R.id.waveform)
        minimizedDot = pillRoot.findViewById(R.id.minimized_dot)
        minimizedDot.setOnClickListener {
            setMinimized(false)
            toggleCard()
        }
        languageChip = pillRoot.findViewById(R.id.language_chip)
        debugPanel = pillRoot.findViewById(R.id.debug_panel)
        choiceRow = pillRoot.findViewById(R.id.choice_row)
        choiceButtons = listOf(R.id.choice_0, R.id.choice_1, R.id.choice_2)
            .map { pillRoot.findViewById<TextView>(it) }
        choiceButtons.forEachIndexed { i, b ->
            b.setOnClickListener { controller.onChoiceTapped(i) }
        }
        decisionRow = pillRoot.findViewById(R.id.decision_row)
        decisionPrimaryButton = pillRoot.findViewById(R.id.decision_primary_button)
        decisionSecondaryButton = pillRoot.findViewById(R.id.decision_secondary_button)
        decisionPrimaryButton.setOnClickListener {
            controller.onDecisionTapped(DecisionChoice.PRIMARY)
        }
        decisionSecondaryButton.setOnClickListener {
            controller.onDecisionTapped(DecisionChoice.SECONDARY)
        }
        textCommand = pillRoot.findViewById(R.id.text_command)
        textCommandRow = pillRoot.findViewById(R.id.text_command_row)
        voiceSlot = pillRoot.findViewById(R.id.voice_slot)
        removalPrompt = pillRoot.findViewById(R.id.removal_prompt)
        pillRoot.findViewById<View>(R.id.removal_cancel_button).setOnClickListener {
            cancelRemovalPrompt()
        }
        pillRoot.findViewById<View>(R.id.removal_confirm_button).setOnClickListener {
            confirmRemoval()
        }
        textCommand.setOnClickListener { focusTextInput() }
        textCommand.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP &&
                ((pillParams?.flags ?: 0) and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
            ) {
                // EditText consumes its first tap while this overlay cannot
                // take focus. Handle that tap before its normal click path.
                focusTextInput()
                true
            } else false
        }
        textCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTextCommand()
                true
            } else false
        }
        pillRoot.findViewById<View>(R.id.send_text_button).setOnClickListener {
            submitTextCommand()
        }

        // The mascot opens the compact card. The mic remains visible inside;
        // tapping the mascot never starts an unintended recording.
        pillRoot.findViewById<View>(R.id.pill_row).setOnClickListener { toggleCard() }
        pillRoot.findViewById<View>(R.id.pill_row).setOnLongClickListener {
            requestRemovalPrompt()
            true
        }
        pillLabel.setOnLongClickListener {
            debugVisible = !debugVisible
            debugPanel.visibility = if (debugVisible) View.VISIBLE else View.GONE
            true
        }
        micButton.setOnClickListener { controller.onMicTapped() }
        // Circle-selection mode stays on the expanded mic. The mascot's long
        // press is reserved for confirmed removal; diagnostics use the header.
        micButton.setOnLongClickListener {
            enterSelectionMode()
            true
        }
        // While listening the mic is gone; the wave is what's under the
        // finger, so it has to end the turn too.
        waveform.setOnClickListener { controller.onMicTapped() }
        pillRoot.findViewById<View>(R.id.next_button).setOnClickListener { controller.onNextTapped() }
        pillRoot.findViewById<View>(R.id.stop_button).setOnClickListener { controller.onStopTapped() }
        pillRoot.findViewById<View>(R.id.close_button).setOnClickListener { toggleCard() }

        val lp = WindowManager.LayoutParams(
            // Explicit width follows the presentation, so text cannot resize
            // the frame and the collapsed mascot intercepts only 96dp.
            dp(presentation.windowWidthDp),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // Not focusable, so the keyboard on the screen underneath still works,
            // but still touchable so the mic/next buttons receive taps.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        // TOP|START with explicit x/y, not BOTTOM|CENTER. Gravity-relative
        // coordinates make dragging ambiguous — the same p.x means a different
        // screen position depending on which gravity bits are set, which is
        // how a dragged bubble ends up somewhere other than where the finger
        // released it. One absolute anchor keeps the window position, the
        // rendered pixels and the touch region describing the same thing.
        lp.gravity = Gravity.TOP or Gravity.START
        pillParams = lp
        wm.addView(pillRoot, lp)

        // Position once the view has been measured — the safe area depends on
        // the window's real height, which is not known until layout.
        pillRoot.post { restToDefaultPosition() }
        installDragHandling()
        installInsetHandling()
        pillRoot.addOnLayoutChangeListener { _, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                pillRoot.post { repositionForCurrentScreen() }
            }
        }
        cardContent.addOnLayoutChangeListener { _, left, top, right, bottom,
                                                oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                pillRoot.post { repositionForCurrentScreen() }
            }
        }
    }

    /** Usable area: the display minus system bars, cutout and (when up) the IME. */
    private fun safeArea(): Rect {
        val metrics = resources.displayMetrics
        val full = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        val insets = pillRoot.rootWindowInsets ?: return full
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val t = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout(),
            )
            Rect(full.left + t.left, full.top + t.top, full.right - t.right, full.bottom - t.bottom)
        } else {
            @Suppress("DEPRECATION")
            Rect(
                full.left + insets.systemWindowInsetLeft,
                full.top + insets.systemWindowInsetTop,
                full.right - insets.systemWindowInsetRight,
                full.bottom - insets.systemWindowInsetBottom,
            )
        }
    }

    /**
     * Top edge of the keyboard in screen px, or 0 when it is closed.
     *
     * Comes from the accessibility service, not this window's insets — see
     * ScreenReaderService.imeTopPx(). A FLAG_NOT_FOCUSABLE overlay is never
     * the IME's target, so its own rootWindowInsets report no keyboard even
     * while one is plainly covering half the screen.
     */
    private fun imeTop(): Int = ScreenReaderService.instance?.imeTopPx() ?: 0

    private fun windowSize(): Pair<Int, Int> {
        val w = pillParams?.width ?: desiredWindowWidth(expanded)
        val h = if (pillRoot.height > 0) pillRoot.height else dp(96)
        return w to h
    }

    /** Bottom-right, matching the reference bubble before the user moves it. */
    private fun restToDefaultPosition() {
        val (w, h) = windowSize()
        val safe = safeArea()
        userX = safe.right - w - dp(20)
        userY = safe.bottom - h - dp(28)
        applyPosition(userX, userY, respectKeyboard = true)
    }

    /**
     * Write a position to the window, clamped into the safe area and lifted
     * clear of the keyboard if it would otherwise sit under it.
     *
     * [userX]/[userY] hold what the *user* chose; the keyboard may push the
     * live window off that temporarily, and it returns when the IME closes.
     */
    private fun applyPosition(x: Int, y: Int, respectKeyboard: Boolean) {
        val lp = pillParams ?: return
        val (w, h) = windowSize()
        val safe = if (respectKeyboard) currentSafeArea() else safeArea()
        val (cx, cy) = com.screensaathi.overlay.AssistantPlacement.clamp(x, y, w, h, safe)
        lp.x = cx
        lp.y = cy
        runCatching { wm.updateViewLayout(pillRoot, lp) }
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                TAG_UI,
                "ASSISTANT_WINDOW x=${lp.x} y=${lp.y} width=$w height=$h " +
                    "safe=${safe.toShortString()} imeTop=${imeTop()} state=$uiState",
            )
        }
    }

    /**
     * Re-run placement when the insets change — keyboard opening or closing,
     * rotation, a cutout coming into play. The user's chosen position is the
     * input every time, so the assistant returns to it once the IME is gone
     * rather than drifting upward with each successive keyboard.
     */
    private fun installInsetHandling() {
        pillRoot.setOnApplyWindowInsetsListener { _, insets ->
            pillRoot.post { repositionForCurrentScreen() }
            insets
        }
    }

    private fun currentSafeArea(): Rect = safeArea().apply {
        val keyboardTop = imeTop()
        if (keyboardTop > 0) bottom = minOf(bottom, keyboardTop - dp(12)).coerceAtLeast(top)
    }

    private fun desiredWindowWidth(open: Boolean): Int = minOf(
        dp(if (open) AssistantPresentation.CARD_WIDTH_DP else AssistantPresentation.CIRCLE_WIDTH_DP),
        safeArea().width().coerceAtLeast(1),
    )

    /** The card scrolls when large text or the keyboard leaves less room. */
    private fun limitCardHeight() {
        if (!expanded || cardBody.visibility != View.VISIBLE) return
        val natural = cardContent.measuredHeight + cardBody.paddingTop + cardBody.paddingBottom
        if (natural <= 0) return
        val available = currentSafeArea().height().coerceAtLeast(1)
        val desired = minOf(natural, available)
        val lp = cardBody.layoutParams
        if (lp.height != desired) {
            lp.height = desired
            cardBody.layoutParams = lp
        }
    }

    private fun repositionForCurrentScreen() {
        if (uiState == AssistantUiState.DRAGGING) return
        if (textInputActive) {
            if (imeTop() > 0) textInputSawKeyboard = true
            else if (textInputSawKeyboard) releaseTextInputFocus()
        }
        limitCardHeight()
        val params = pillParams
        applyPosition(
            if (avoidingHighlight) params?.x ?: userX else userX,
            if (avoidingHighlight) params?.y ?: userY else userY,
            respectKeyboard = true,
        )
        if (!presentation.removalPending) presentation.command.highlight?.let(::moveCardAwayFrom)
    }

    /**
     * Drag the whole window, and tell a drag apart from a tap.
     *
     * Below slop a tap toggles the card; above it the window follows the finger
     * and the tap is suppressed entirely.
     */
    private fun installDragHandling() {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val listener = object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                val lp = pillParams ?: return false
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downRawX = e.rawX; downRawY = e.rawY
                        startX = lp.x; startY = lp.y
                        moved = false
                        if (BuildConfig.DEBUG) {
                            val loc = IntArray(2)
                            v.getLocationOnScreen(loc)
                            android.util.Log.d(
                                TAG_UI,
                                "ASSISTANT_TOUCH rawX=${e.rawX.toInt()} rawY=${e.rawY.toInt()} " +
                                    "insideWindow=true insideControl=[${loc[0]},${loc[1]}]" +
                                    "[${loc[0] + v.width},${loc[1] + v.height}]",
                            )
                        }
                        return false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downRawX).toInt()
                        val dy = (e.rawY - downRawY).toInt()
                        if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                            moved = true
                            stateBeforeDrag = uiState
                            uiState = AssistantUiState.DRAGGING
                        }
                        if (moved) {
                            // Keyboard avoidance is suspended mid-drag: the
                            // user is explicitly placing it, and fighting them
                            // for the position reads as the window sticking.
                            applyPosition(startX + dx, startY + dy, respectKeyboard = false)
                        }
                        return moved
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        if (!moved) return false // let the click listener run
                        val (w, _) = windowSize()
                        val safe = safeArea()
                        val snapped = com.screensaathi.overlay.AssistantPlacement
                            .snapX(lp.x, w, safe, resources.displayMetrics.density)
                        userX = snapped
                        userY = lp.y
                        uiState = stateBeforeDrag
                        animateToX(snapped)
                        expansionRequestedDuringDrag?.let { requested ->
                            expansionRequestedDuringDrag = null
                            pillRoot.post { setExpanded(requested) }
                        }
                        return true
                    }
                }
                return false
            }
        }
        // Attached to the window root as well as the pill row. The row alone
        // was not enough: it does not span the whole window (padding, and the
        // card above it), so a grab that landed a few pixels outside it was
        // never delivered to the drag listener at all and the assistant simply
        // refused to move. The root always covers the window.
        pillRoot.setOnTouchListener(listener)
        pillRoot.findViewById<View>(R.id.pill_row).setOnTouchListener(listener)
        // The card's content scrolls at large font sizes; drag it by the mascot.
    }

    /** Slide to the docked x rather than teleporting there on release. */
    private fun animateToX(targetX: Int) {
        val lp = pillParams ?: return
        dockAnimator?.cancel()
        dockAnimator = android.animation.ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = DOCK_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                applyPosition(a.animatedValue as Int, userY, respectKeyboard = true)
            }
            start()
        }
    }

    /** Park the assistant as the mascot so the app underneath is usable. */
    fun setMinimized(minimized: Boolean) {
        if (minimized && uiState == AssistantUiState.MINIMIZED) return
        uiState = if (minimized) AssistantUiState.MINIMIZED else AssistantUiState.COLLAPSED
        presentation = presentation.hide()
        setExpanded(false)
        pillRoot.findViewById<View>(R.id.pill_row).visibility =
            if (minimized) View.GONE else View.VISIBLE
        minimizedDot.visibility = if (minimized) View.VISIBLE else View.GONE
        pillRoot.post { applyPosition(userX, userY, respectKeyboard = true) }
    }

    /**
     * Swap between the transport row and the waveform as one object changing
     * state: the outgoing surface fades and shrinks slightly, the incoming one
     * fades up. Both live in the same fixed-height slot, so nothing around
     * them moves and a tap in flight cannot land on a control that has just
     * shifted.
     */
    private fun setVoiceActive(active: Boolean) {
        if (voiceActive == active) return
        voiceActive = active
        val appearing: View = if (active) waveform else transportRow
        val leaving: View = if (active) transportRow else waveform

        leaving.animate().cancel()
        appearing.animate().cancel()
        leaving.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
            .setDuration(TRANSITION_MS)
            .withEndAction { leaving.visibility = View.GONE }
            .start()
        appearing.alpha = 0f
        appearing.scaleX = 0.94f
        appearing.scaleY = 0.94f
        appearing.visibility = View.VISIBLE
        appearing.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(TRANSITION_MS)
            .start()

        if (active) startLevelPump() else stopLevelPump()
    }

    /**
     * Feeds the waveform real microphone loudness while a voice state is on
     * screen. Read-only: the recorder owns the mic, this only samples the
     * level it already computes, so there is still exactly one capture.
     */
    private fun startLevelPump() {
        stopLevelPump()
        levelPump = object : Runnable {
            override fun run() {
                waveform.setLevel(controller.micLevel())
                main.postDelayed(this, LEVEL_POLL_MS)
            }
        }.also { main.post(it) }
    }

    private fun stopLevelPump() {
        levelPump?.let { main.removeCallbacks(it) }
        levelPump = null
        waveform.setLevel(0f)
    }

    // --- Circle selection mode ------------------------------------------------

    /**
     * Show the draw-around-something surface.
     *
     * A separate window, added only for the duration of the gesture. It has to
     * be touchable (to receive the stroke) and focusable (to receive BACK),
     * which is exactly why it cannot be folded into the existing highlight
     * window — that one is deliberately FLAG_NOT_TOUCHABLE so taps reach the
     * app underneath.
     */
    private fun enterSelectionMode() {
        if (selectionView != null) return

        val view = com.screensaathi.circle.CircleSelectionView(
            context = this,
            onSelectionComplete = { path, shape -> onSelectionDrawn(path, shape) },
            onCancel = { main.post { exitSelectionMode() } },
        )
        view.setHint(controller.selectionHint())

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // Focusable on purpose — BACK must cancel selection. FLAG_WATCH_
            // OUTSIDE_TOUCH is not needed since this covers the whole screen.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        )

        runCatching { wm.addView(view, lp) }
            .onFailure {
                android.util.Log.w(TAG_UI, "could not add selection window", it)
                return
            }

        selectionView = view
        view.requestFocus()

        // Get the assistant out of the way of the thing being circled.
        controller.onSelectionModeEntered()
    }

    private fun exitSelectionMode() {
        val view = selectionView ?: return
        selectionView = null
        runCatching { wm.removeView(view) }
        controller.onSelectionModeExited()
    }

    private fun onSelectionDrawn(
        path: List<com.screensaathi.circle.SelectionPoint>,
        shape: com.screensaathi.circle.SelectionShape,
    ) {
        main.post {
            // Take the surface down before resolving: the accessibility
            // snapshot must not include our own dimmed selection window, and
            // the user should see their screen again immediately.
            exitSelectionMode()
            controller.onSelectionDrawn(path, shape)
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    // --- Rendering (the whole job of this class) ------------------------------

    private fun render(cmd: OverlayCommand) {
        presentation = presentation.accept(cmd)

        // The voice surface. Exactly one of {transport row, waveform} is ever
        // visible, so the mic and the wave can never read as two competing
        // "audio is happening" indicators.
        val waveMode = when (cmd.pillState) {
            PillState.LISTENING -> com.screensaathi.overlay.VoiceWaveformView.Mode.LISTENING
            PillState.THINKING -> com.screensaathi.overlay.VoiceWaveformView.Mode.THINKING
            PillState.SPEAKING -> com.screensaathi.overlay.VoiceWaveformView.Mode.SPEAKING
            else -> com.screensaathi.overlay.VoiceWaveformView.Mode.IDLE
        }
        val voiceActive = waveMode != com.screensaathi.overlay.VoiceWaveformView.Mode.IDLE
        waveform.setMode(waveMode)
        setVoiceActive(voiceActive)

        // One assignment, derived from the command — not a set of booleans
        // that can disagree. DRAGGING/MINIMIZED are user gestures and are not
        // overwritten by a render, or the pill would jump back mid-drag.
        if (uiState != AssistantUiState.DRAGGING && uiState != AssistantUiState.MINIMIZED) {
            uiState = when (cmd.pillState) {
                PillState.LISTENING -> AssistantUiState.LISTENING
                PillState.THINKING -> AssistantUiState.THINKING
                PillState.SPEAKING -> AssistantUiState.SPEAKING
                PillState.GUIDING -> AssistantUiState.GUIDING
                PillState.ERROR -> AssistantUiState.EXPANDED
                PillState.IDLE -> if (cmd.expanded) AssistantUiState.EXPANDED
                    else AssistantUiState.COLLAPSED
            }
        }
        // The pill's own label speaks the user's language too — an English
        // "Listening…" above a Hindi instruction breaks the illusion instantly.
        pillLabel.text = PillLabels.forState(cmd.pillState, cmd.language)
        languageChip.text = Language.nativeName(cmd.language)

        cmd.instruction?.let { instructionText.text = it }
        renderChoices(cmd.choices)
        renderDecision(cmd.decisionButtons)
        renderRemovalPrompt()

        setExpanded(presentation.cardVisible && uiState != AssistantUiState.MINIMIZED)
        pillRoot.post { repositionForCurrentScreen() }

        // Keep the cursor's launch point under the pill, so it always flies out
        // of the assistant rather than appearing from nowhere.
        publishHomePosition()

        val h = cmd.highlight
        if (h == null) {
            highlightView.clear()
            if (avoidingHighlight) {
                avoidingHighlight = false
                pillRoot.post { applyPosition(userX, userY, respectKeyboard = true) }
            }
        } else {
            highlightView.show(h.left, h.top, h.right, h.bottom, h.shape, h.pulse)
            // The card is a real touchable window and must never cover the
            // control it asks the user to press.
            pillRoot.post { moveCardAwayFrom(h) }
        }
    }

    private fun moveCardAwayFrom(target: com.screensaathi.overlay.HighlightBounds) {
        if (presentation.removalPending) {
            highlightView.clear()
            return
        }
        val lp = pillParams ?: return
        val (width, height) = windowSize()
        if (width <= 0 || height <= 0) return

        val margin = dp(12)
        val screenSafe = safeArea()
        val keyboardTop = imeTop()
        val safe = if (keyboardTop > 0) {
            Rect(screenSafe.left, screenSafe.top, screenSafe.right,
                minOf(screenSafe.bottom, keyboardTop - margin).coerceAtLeast(screenSafe.top))
        } else screenSafe
        val bounds = Rect(target.left, target.top, target.right, target.bottom)
        when (val placement = com.screensaathi.overlay.AssistantPlacement.avoidTarget(
            lp.x, lp.y, width, height, safe, bounds, margin,
        )) {
            is com.screensaathi.overlay.AssistantPlacement.Avoidance.Move -> {
                if (lp.x != placement.x || lp.y != placement.y) {
                    lp.x = placement.x
                    lp.y = placement.y
                    wm.updateViewLayout(pillRoot, lp)
                    avoidingHighlight = true
                }
            }
            com.screensaathi.overlay.AssistantPlacement.Avoidance.Collapse -> {
                if (expanded) {
                    presentation = presentation.hide()
                    setExpanded(false)
                    pillRoot.post { moveCardAwayFrom(target) }
                }
            }
        }
    }

    /**
     * One button per option, up to the three the row holds. More installed ride
     * apps than that is not a case worth a scrolling list on a demo overlay —
     * the first three in a curated order are the ones anyone will pick.
     */
    private fun renderChoices(choices: List<String>) {
        choiceRow.visibility = if (choices.isEmpty()) View.GONE else View.VISIBLE
        choiceButtons.forEachIndexed { i, button ->
            val label = choices.getOrNull(i)
            button.visibility = if (label == null) View.GONE else View.VISIBLE
            if (label != null) button.text = label
        }
    }

    private fun renderDecision(buttons: DecisionButtons?) {
        decisionRow.visibility = if (buttons == null) View.GONE else View.VISIBLE
        if (buttons == null) return
        decisionPrimaryButton.text = buttons.primaryLabel
        decisionSecondaryButton.text = buttons.secondaryLabel
    }

    private fun focusTextInput() {
        val lp = pillParams ?: return
        if (!textInputActive) {
            textInputActive = true
            textInputSawKeyboard = false
        }
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        wm.updateViewLayout(pillRoot, lp)
        textCommand.requestFocus()
        // Window focus changes asynchronously after FLAG_NOT_FOCUSABLE is
        // cleared. An immediate showSoftInput is ignored on Samsung and the
        // user otherwise has to tap the field twice.
        textCommand.postDelayed({
            if (textInputActive && presentation.cardVisible && textCommand.isAttachedToWindow) {
                textCommand.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(textCommand, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 180L)
    }

    private fun releaseTextInputFocus() {
        textInputActive = false
        textInputSawKeyboard = false
        textCommand.clearFocus()
        pillParams?.let { lp ->
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            wm.updateViewLayout(pillRoot, lp)
        }
    }

    private fun submitTextCommand() {
        val text = textCommand.text.toString().trim()
        if (text.isBlank()) return
        textCommand.setText("")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(textCommand.windowToken, 0)
        releaseTextInputFocus()
        controller.onTextSubmitted(text)
    }

    /** Screen position of the pill, handed to the cursor layer as its home. */
    private fun publishHomePosition() {
        val loc = IntArray(2)
        pillRoot.findViewById<View>(R.id.pill_row).getLocationOnScreen(loc)
        val row = pillRoot.findViewById<View>(R.id.pill_row)
        highlightView.setHome(
            loc[0] + row.width / 2f,
            loc[1] + row.height / 2f,
        )
    }

    /** Debug panel content. Visibility stays user-controlled (long-press the pill). */
    fun updateDebug(text: String) {
        debugPanel.text = text
    }

    private fun toggleCard() {
        if (presentation.removalPending) {
            cancelRemovalPrompt()
            return
        }
        presentation = presentation.toggle()
        if (!presentation.cardVisible && textInputActive) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(textCommand.windowToken, 0)
            releaseTextInputFocus()
        }
        uiState = if (presentation.cardVisible) AssistantUiState.EXPANDED
            else AssistantUiState.COLLAPSED
        setExpanded(presentation.cardVisible)
        presentation.command.highlight?.let { target ->
            pillRoot.post { moveCardAwayFrom(target) }
        }
    }

    private fun requestRemovalPrompt() {
        // A drag can last longer than Android's long-press timeout. The
        // moving mascot must not turn into a confirmation card on release.
        if (uiState == AssistantUiState.DRAGGING) return
        if (textInputActive) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(textCommand.windowToken, 0)
            releaseTextInputFocus()
        }
        presentation = presentation.requestRemoval()
        highlightView.clear()
        setExpanded(true)
        renderRemovalPrompt()
    }

    private fun cancelRemovalPrompt() {
        presentation = presentation.cancelRemoval()
        render(presentation.command)
    }

    private fun confirmRemoval() {
        if (!presentation.canConfirmRemoval) return
        controller.onStopTapped()
        main.post { stopSelf() }
    }

    private fun renderRemovalPrompt() {
        val pending = presentation.removalPending
        removalPrompt.visibility = if (pending) View.VISIBLE else View.GONE
        instructionText.visibility = if (pending) View.GONE else View.VISIBLE
        textCommandRow.visibility = if (pending) View.GONE else View.VISIBLE
        voiceSlot.visibility = if (pending) View.GONE else View.VISIBLE
        debugPanel.visibility = if (!pending && debugVisible) View.VISIBLE else View.GONE
        if (pending) {
            choiceRow.visibility = View.GONE
            decisionRow.visibility = View.GONE
        }
    }

    private fun setExpanded(value: Boolean) {
        if (uiState == AssistantUiState.DRAGGING) {
            expansionRequestedDuringDrag = value
            return
        }
        val width = desiredWindowWidth(value)
        if (expanded == value && cardBody.visibility == (if (value) View.VISIBLE else View.GONE) &&
            pillParams?.width == width
        ) return
        expanded = value
        cardBody.visibility = if (value) View.VISIBLE else View.GONE
        pillRoot.findViewById<View>(R.id.pill_row).contentDescription =
            if (value) "Свернуть Опору" else "Открыть Опору"
        pillParams?.let { params ->
            if (params.width != width) {
                val delta = params.width - width
                val nextX = params.x + delta
                params.width = width
                params.x = nextX
                userX += delta
                wm.updateViewLayout(pillRoot, params)
            }
        }
        pillRoot.post {
            repositionForCurrentScreen()
            publishHomePosition()
        }
    }

    // --- Foreground plumbing --------------------------------------------------

    private fun startAsForeground() {
        val channelId = "saathi_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId, "Опора", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notif: Notification =
            androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setContentTitle("Опора готова")
                .setContentText("Нажмите на плавающую кнопку, чтобы начать.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()

        val hasMic = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // Only claim the microphone FGS type when the permission is actually
        // held — otherwise Android 14+ throws at startForeground.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (hasMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (live === this) live = null
        stopLevelPump()
        controller.dispose()
        // Selection mode may still be up if the service is torn down mid-gesture;
        // a leaked full-screen touchable window would swallow every tap.
        selectionView?.let { runCatching { wm.removeView(it) } }
        selectionView = null
        runCatching { wm.removeView(highlightView) }
        runCatching { wm.removeView(pillRoot) }
        // Only a real teardown clears this — a genuinely new overlay lifetime
        // (service killed and restarted) is allowed to add its windows back.
        windowsAdded = false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val NOTIF_ID = 42

        /** Shared across any Service instance in this process — see onCreate(). */
        @Volatile private var windowsAdded = false

        @Volatile private var live: OverlayService? = null

        /**
         * The system's window set changed — most importantly the keyboard
         * appearing or disappearing. Re-runs placement so the assistant lifts
         * clear of the IME and settles back afterwards.
         */
        fun onSystemWindowsChanged() {
            val svc = live ?: return
            svc.main.post { svc.repositionForCurrentScreen() }
        }

        private const val TRANSITION_MS = 220L
        private const val DOCK_MS = 180L
        private const val TAG_UI = "AssistantUI"
        /** ~30fps is plenty: the view smooths between samples itself. */
        private const val LEVEL_POLL_MS = 33L

        const val ACTION_RUN_TASK = "com.screensaathi.RUN_TASK"
        const val ACTION_CHOOSE = "com.screensaathi.CHOOSE"
        const val ACTION_HIGHLIGHT = "com.screensaathi.HIGHLIGHT"
        const val ACTION_CLEAR_HIGHLIGHT = "com.screensaathi.CLEAR_HIGHLIGHT"
        const val ACTION_START_SELECTION = "com.screensaathi.START_SELECTION"
        const val ACTION_CANCEL_SELECTION = "com.screensaathi.CANCEL_SELECTION"
        const val EXTRA_QUERY = "query"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_CHOICE = "choice"

        fun start(context: Context) {
            val i = Intent(context, OverlayService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        /** Pick option [index] from the card, as if the button were tapped. */
        fun highlight(context: Context, query: String) {
            context.startService(
                Intent(context, OverlayService::class.java)
                    .setAction(ACTION_HIGHLIGHT)
                    .putExtra(EXTRA_QUERY, query)
            )
        }

        fun clearHighlight(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_CLEAR_HIGHLIGHT)
            )
        }

        /** Open the draw-a-selection surface. */
        fun startSelection(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_START_SELECTION)
            )
        }

        /**
         * Resolve a rectangular region as though the user had drawn it.
         *
         * Goes through the identical selection path as the gesture — same
         * resolver, same context, same phrasing — so a scripted run is a real
         * exercise of the feature and not a shortcut around it.
         */
        fun selectRegion(context: Context, left: Int, top: Int, right: Int, bottom: Int) {
            val svc = live ?: return
            svc.main.post {
                svc.controller.onSelectionDrawn(
                    listOf(
                        com.screensaathi.circle.SelectionPoint(left, top),
                        com.screensaathi.circle.SelectionPoint(right, top),
                        com.screensaathi.circle.SelectionPoint(right, bottom),
                        com.screensaathi.circle.SelectionPoint(left, bottom),
                    ),
                    com.screensaathi.circle.SelectionShape.FREEFORM,
                )
            }
        }

        /** Ask about the live selection without going through STT. */
        fun askAboutSelection(context: Context, query: String, language: String? = null) {
            val svc = live ?: return
            svc.main.post {
                language?.let { svc.controller.setLanguage(it) }
                if (!svc.controller.onCircleRequest(query)) {
                    // No selection, or an informational question the tree
                    // cannot answer — fall back to the ordinary highlight path
                    // so the request still does something visible.
                    svc.controller.highlightTarget(query)
                }
            }
        }

        fun choose(context: Context, index: Int) {
            val i = Intent(context, OverlayService::class.java)
                .setAction(ACTION_CHOOSE)
                .putExtra(EXTRA_CHOICE, index)
            ContextCompat.startForegroundService(context, i)
        }

        /** Start the overlay if needed, then run [taskId] without speech. */
        fun runTask(context: Context, taskId: String, language: String) {
            val i = Intent(context, OverlayService::class.java)
                .setAction(ACTION_RUN_TASK)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_LANGUAGE, language)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
