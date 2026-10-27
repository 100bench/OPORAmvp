package com.screensaathi.session

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.screensaathi.circle.CircleContext
import com.screensaathi.circle.CircleIntent
import com.screensaathi.circle.CircleSession
import com.screensaathi.circle.IntentClassifier
import com.screensaathi.circle.SelectionPoint
import com.screensaathi.circle.SelectionShape
import com.screensaathi.device.DeviceContextProvider
import androidx.core.content.ContextCompat
import com.screensaathi.AppLauncher
import com.screensaathi.R
import com.screensaathi.SaathiApp
import com.screensaathi.ScreenReaderService
import com.screensaathi.overlay.DecisionButtons
import com.screensaathi.overlay.DecisionChoice
import com.screensaathi.overlay.HighlightBounds
import com.screensaathi.overlay.OverlayCommand
import com.screensaathi.overlay.PillState
import com.screensaathi.opora.OporaSettings
import com.screensaathi.sarvam.AudioPlayer
import com.screensaathi.sarvam.Language
import com.screensaathi.sarvam.Sarvam
import com.screensaathi.sarvam.Spoken
import com.screensaathi.sarvam.SarvamPlanner
import com.screensaathi.sarvam.SarvamStt
import com.screensaathi.sarvam.SarvamTts
import com.screensaathi.sarvam.WavRecorder
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.screen.TargetResolver
import com.screensaathi.task.GuidedTask
import com.screensaathi.task.RideApps
import com.screensaathi.task.StepKind
import com.screensaathi.task.TaskRepository
import com.screensaathi.task.TaskStep
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The orchestration layer. Turns UI taps into OverlayCommands, driving the
 * deterministic StepEngine plus (when the gateway is available) the
 * OpenAI voice loop: transcription -> planner -> highlight -> speech.
 *
 * Every network step has a deterministic fallback. If the gateway is down or any
 * call fails, mic-tap starts the task and Next advances in order — the overlay
 * is never left frozen.
 *
 * ## Turns
 *
 * Every user action opens a numbered *turn*. Background work captures its turn
 * number and drops itself if a newer turn has started since. Without this, a
 * mic tap during the ~1.5 s "Thinking…" window starts a fresh recording, then
 * the older in-flight plan lands and repaints the pill to "Guiding you" while
 * the user is still speaking. The pill has to tell the truth about its state,
 * so stale work is discarded rather than rendered.
 */
class SessionController(
    private val context: Context,
    private val render: (OverlayCommand) -> Unit,
    private val clearHighlightInstant: () -> Unit,
    private val telemetrySink: ScenarioTelemetrySink = NoOpScenarioTelemetrySink,
) {

    init { instance = this }

    fun interface DebugSink { fun update(text: String) }
    var debugSink: DebugSink? = null

    private val worker = HandlerThread("saathi-session").apply { start() }
    private val bg = Handler(worker.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var latencyTurn: Pair<Int, ScenarioLatencyTrace>? = null

    /**
     * Recorder start/stop run here and nowhere else, so they are serialized in
     * tap order. Sharing [bg] with the network work meant a re-tap during the
     * "Thinking…" window could call start() before the previous stop() had run
     * off the back of a 1.5 s STT call — start() saw the recorder still busy,
     * returned false, and the turn silently fell back to the offline path.
     */
    private val captureWorker = HandlerThread("saathi-capture").apply { start() }
    private val capture = Handler(captureWorker.looper)

    /**
     * Speech synthesis and playback. Separate from [bg] so Bulbul's ~1.2 s —
     * the slowest layer in the loop — never sits in front of the next
     * highlight resolution. The visual path is the demo; it must never queue
     * behind audio.
     */
    private val speechWorker = HandlerThread("saathi-speech").apply { start() }
    private val speech = Handler(speechWorker.looper)

    private val tasks: TaskRepository = TaskRepository.load(context)
    private var engine: StepEngine? = null

    private val recorder = WavRecorder()
    private val stt = SarvamStt()
    private val planner = SarvamPlanner(context)
    private val tts = SarvamTts()
    private val player = AudioPlayer(context)
    private val networkMonitor: NetworkMonitor = AndroidNetworkMonitor(context)
    private val resumeCapsules: ResumeCapsuleStore =
        (context.applicationContext as? SaathiApp)?.resumeCapsuleStore
            ?: AndroidResumeCapsuleStore(context)
    private val voiceSafetyBoundary = VoiceCaptureSafetyBoundary(::currentScreenSafetyAssessment)
    private val voiceCaptureState = VoiceCaptureState(voiceSafetyBoundary)

    /** The app-agnostic reducer/planner loop used by all new text and voice goals. */
    private val universalLoop: UniversalGuidanceLoop by lazy {
        UniversalGuidanceLoop(
            planner = planner,
            snapshotProvider = { ScreenReaderService.instance?.snapshot() ?: ScreenSnapshot.EMPTY },
            clock = SystemClock::elapsedRealtime,
            emit = { emission ->
                val turn = universalTurn
                emission.dispatch(
                    postToUi = { main.post(it) },
                    isActive = { universalActive },
                    isTurnCurrent = { isCurrent(turn) },
                    consume = ::handleUniversalEmission,
                )
            },
            appLabelProvider = { packageName ->
                val packageManager = context.packageManager
                runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0),
                    ).toString().trim().takeIf(String::isNotEmpty)
                }.getOrNull()
            },
        )
    }

    @Volatile private var universalActive = false
    @Volatile private var universalTurn = -1
    @Volatile private var pendingScenarioPrimary: ScenarioChoice? = null
    @Volatile private var pendingScenarioSecondary: ScenarioChoice? = null
    @Volatile private var pendingScenarioChoices: List<ScenarioChoice> = emptyList()
    @Volatile private var pendingTargetAppPackage: String? = null
    @Volatile private var pendingResumeCapsule: ResumeCapsule? = null
    private val screenObservationId = AtomicInteger(0)

    /** User-visible language is fixed to Russian for this MVP. */
    @Volatile private var lastLanguage = Language.APP_DEFAULT

    /** Set by [onStopTapped]; cleared as soon as the user engages again. */
    @Volatile private var stopped = false

    /**
     * Monotonic turn counter. Bumped by every user action; background work that
     * finds itself outdated renders nothing.
     */
    private val turnId = AtomicInteger(0)
    private val turnLock = Any()

    private fun newTurn(): Int = synchronized(turnLock) {
        // Diagnostics belong to one turn. Without this the previous turn's note
        // ("stopped by user at step amount") sits under the next turn's
        // reading and describes something that is no longer happening.
        debug = VoiceDebug()
        turnId.incrementAndGet()
    }
    private fun isCurrent(turn: Int): Boolean = turnId.get() == turn

    /** Render only if [turn] is still the live one. */
    private fun renderIfCurrent(
        turn: Int,
        cmd: OverlayCommand,
        stillCurrent: () -> Boolean = { true },
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (isCurrent(turn) && stillCurrent()) render(cmd)
        } else {
            main.post { if (isCurrent(turn) && stillCurrent()) render(cmd) }
        }
    }

    /**
     * The highlight currently on screen. Every subsequent render must carry it
     * forward: OverlayCommand.highlight defaults to null, so a state-only
     * update (e.g. flipping to SPEAKING) would otherwise erase the ring the
     * user is being pointed at.
     */
    @Volatile private var currentHighlight: HighlightBounds? = null
    private var currentUsefulInstruction: String? = null

    /** One user goal can span several app screens; this is its visible step. */
    @Volatile private var guidanceStepNumber = 1

    /** Screen for which the current open-ended step was planned. */
    @Volatile private var activePlanScreenSignature = ""

    /**
     * Options currently on the card, carried forward for the same reason as
     * [currentHighlight]: OverlayCommand.choices defaults to empty, so the
     * SPEAKING render issued while the question is being read aloud would
     * otherwise wipe the very buttons the user is being asked to press.
     */
    @Volatile private var currentChoices: List<String> = emptyList()

    /** Only locally-created allow-listed actions can ever enter this slot. */
    @Volatile private var pendingAction: PendingAction? = null
    @Volatile private var currentDecisionButtons: DecisionButtons? = null

    /** Diagnostics for the live turn, shown by long-pressing the pill. */
    @Volatile private var debug = VoiceDebug()

    private fun publishDebug(turn: Int, update: (VoiceDebug) -> VoiceDebug) {
        if (!isCurrent(turn)) return
        val next = update(debug)
        debug = next
        Log.d(TAG, next.toPanel().replace("\n", " | "))
        debugSink?.update(next.toPanel())
    }

    // --- UI events (main thread) ----------------------------------------------

    /**
     * Live mic loudness, 0..1, for the voice waveform. Read-only pass-through
     * to the one recorder that owns the microphone — the overlay never opens
     * its own capture.
     */
    fun micLevel(): Float = if (voiceCaptureState.isListening()) recorder.level else 0f

    /** Offer a valid process-restoration capsule after the overlay is visible. */
    fun offerResumeIfAvailable() {
        val observedTurn = turnId.get()
        bg.post {
            val capsule = resumeCapsules.load() ?: return@post
            if (!isCurrent(observedTurn) || universalActive) return@post
            pendingResumeCapsule = capsule
            pendingScenarioPrimary = ScenarioChoice.CONTINUE
            pendingScenarioSecondary = ScenarioChoice.STOP
            currentDecisionButtons = DecisionButtons.CONTINUE_OR_STOP
            currentChoices = emptyList()
            renderIfCurrent(
                observedTurn,
                OverlayCommand(
                    PillState.GUIDING,
                    expanded = true,
                    instruction = "Продолжить предыдущую пошаговую помощь? Текущий экран будет проверен заново.",
                    language = lastLanguage,
                    highlight = null,
                    decisionButtons = currentDecisionButtons,
                ),
            )
        }
    }

    fun onMicTapped() {
        if (!voiceCaptureState.isListening()) startListening() else stopAndProcess()
    }

    /** Text and voice enter the same intent path; text simply skips STT. */
    fun onTextSubmitted(text: String) {
        if (text.isBlank()) return
        val turn = newTurn()
        latencyTurn = turn to ScenarioLatencyTrace(SystemClock::elapsedRealtime, telemetrySink)
        pendingResumeCapsule = null
        bg.post { resumeCapsules.delete() }
        stopUniversalForReplacement(turn)
        abandonRecording(turn)
        player.stop()
        stopped = false
        pendingAction = null
        currentDecisionButtons = null
        currentHighlight = null
        currentChoices = emptyList()
        lastUserRequest = text.trim()
        guidanceStepNumber = 1
        activePlanScreenSignature = ""
        render(OverlayCommand(PillState.THINKING, expanded = true,
            instruction = "Проверяю текущий экран…", language = Language.APP_DEFAULT))
        bg.post {
            if (!isCurrent(turn)) return@post
            if (!maybeProposeAllowedAction(text, turn)) {
                startUniversalGoal(turn, text.trim(), ambiguous = false)
            }
        }
    }

    fun onDecisionTapped(choice: DecisionChoice) {
        val scenarioChoice = if (choice == DecisionChoice.PRIMARY) {
            pendingScenarioPrimary
        } else {
            pendingScenarioSecondary
        }
        if (scenarioChoice != null) {
            val resume = pendingResumeCapsule
            if (
                resume != null &&
                scenarioChoice in setOf(ScenarioChoice.CONTINUE, ScenarioChoice.STOP)
            ) {
                val turn = newTurn()
                pendingResumeCapsule = null
                stopped = scenarioChoice == ScenarioChoice.STOP
                player.stop()
                clearScenarioDecisions()
                currentChoices = emptyList()
                currentHighlight = null
                if (scenarioChoice == ScenarioChoice.STOP) {
                    bg.post { resumeCapsules.delete() }
                    val phrase = Phrases.get(Phrases.Key.STOPPED, lastLanguage)
                    renderIfCurrent(
                        turn,
                        OverlayCommand(
                            PillState.IDLE,
                            expanded = true,
                            instruction = phrase.text,
                            language = phrase.language,
                            highlight = null,
                        ),
                    )
                } else {
                    universalActive = true
                    universalTurn = turn
                    renderIfCurrent(
                        turn,
                        OverlayCommand(
                            PillState.THINKING,
                            expanded = true,
                            instruction = "Проверяю текущий экран перед продолжением…",
                            language = lastLanguage,
                            highlight = null,
                        ),
                    )
                    bg.post {
                        val refreshed = resumeCapsules.load()
                        if (!universalActive || !isCurrent(turn)) return@post
                        if (refreshed == null || refreshed != resume) {
                            universalActive = false
                            renderIfCurrent(
                                turn,
                                OverlayCommand(
                                    PillState.ERROR,
                                    expanded = true,
                                    instruction = "Сохранённая сессия истекла. Скажите или напишите цель заново.",
                                    language = lastLanguage,
                                    highlight = null,
                                ),
                            )
                            return@post
                        }
                        universalLoop.resume(refreshed)
                    }
                }
                return
            }
            val targetPackage = pendingTargetAppPackage
            val retryPhase = universalLoop.session.phase
            if (scenarioChoice == ScenarioChoice.START && targetPackage == null) {
                latencyTurn?.takeIf { it.first == universalTurn }?.second?.guidanceConfirmed()
            }
            stopped = false
            player.stop()
            clearScenarioDecisions()
            val turn = universalTurn
            if (scenarioChoice == ScenarioChoice.RETRY) {
                val retryingNetwork = retryPhase == ScenarioPhase.PAUSED_FOR_NETWORK
                renderIfCurrent(
                    turn,
                    OverlayCommand(
                        PillState.THINKING,
                        expanded = true,
                        instruction = if (retryingNetwork) {
                            "Повторно проверяю подключение и текущий экран…"
                        } else {
                            "Повторяю попытку…"
                        },
                        language = lastLanguage,
                        highlight = if (retryingNetwork) null else currentHighlight,
                    ),
                )
            }
            bg.post {
                if (universalActive && isCurrent(turn)) {
                    handleScenarioChoice(scenarioChoice, targetPackage)
                }
            }
            return
        }

        val pending = pendingAction ?: return
        val turn = newTurn()
        pendingAction = null // consume before doing anything: decisions are one-shot
        currentDecisionButtons = null
        currentHighlight = null
        currentChoices = emptyList()

        if (choice == DecisionChoice.SECONDARY) {
            showLocalMessage(turn, "Действие отменено.", PillState.IDLE)
            return
        }

        if (SystemClock.elapsedRealtime() > pending.expiresAtMs) {
            showLocalMessage(turn, "Подтверждение истекло. Повторите просьбу.", PillState.ERROR)
            return
        }

        when (pending.type) {
            AllowedAction.CALL_CONTACT -> callConfiguredContact(pending, turn)
        }
    }

    fun onNextTapped() {
        // The universal flow advances from physical screen changes, never from
        // this legacy Next button. Crucially, do not open a new controller turn
        // here: an in-flight universal response belongs to universalTurn and
        // must not be allowed to render under a newly assigned turn number.
        if (universalActive) {
            val turn = universalTurn
            val session = universalLoop.session
            val message = when (session.phase) {
                ScenarioPhase.WAITING_FOR_MANUAL_SCROLL ->
                    "Не могу надёжно выделить цель. Проверьте подписи и действуйте вручную; я продолжу после изменения экрана."
                ScenarioPhase.WAITING_FOR_MODAL_DISMISS ->
                    session.currentGuidance?.instruction
                        ?: "Закройте появившееся окно. После закрытия я продолжу помощь."
                ScenarioPhase.GUIDING_STEP ->
                    session.lastSafeInstruction
                        ?: "Нажмите выделенный элемент на экране или остановите сценарий."
                else -> null
            }
            if (message != null) {
                renderIfCurrent(
                    turn,
                    OverlayCommand(
                        PillState.GUIDING,
                        expanded = true,
                        instruction = message,
                        language = lastLanguage,
                        highlight = currentHighlight,
                        choices = currentChoices,
                        decisionButtons = currentDecisionButtons,
                    ),
                )
            }
            return
        }

        // Legacy flows do advance from Next, so invalidate their pending work.
        val turn = newTurn()
        pendingAction = null
        currentDecisionButtons = null
        abandonRecording(turn)
        stopped = false
        val e = engine
        if (e == null) {
            showLocalMessage(turn, "Скажите или напишите, что хотите найти на текущем экране.", PillState.IDLE)
            return
        }
        if (e.task.id == "open_ended") {
            bg.post {
                processOpenEndedNext(turn, e.task.title)
            }
            return
        }
        if (e.isOnLastStep) {
            currentHighlight = null
            val done = Phrases.get(Phrases.Key.ALL_DONE, lastLanguage)
            renderIfCurrent(turn, OverlayCommand(PillState.IDLE, expanded = true,
                instruction = done.text, highlight = null))
            bg.post { speak(done, turn) }
            return
        }
        e.advance()
        presentCurrentStep(e, turn, speak = true)
    }

    /**
     * Stop cleanly: silence speech, drop any in-flight turn, clear the ring,
     * and keep the step position so the next mic tap resumes rather than
     * restarts. The user must be able to call it off mid-sentence without
     * leaving the pill stuck in "Listening…" or the ring orphaned on screen.
     */
    fun onStopTapped() {
        val turn = newTurn()
        pendingResumeCapsule = null
        bg.post { resumeCapsules.delete() }
        if (universalActive) {
            universalTurn = turn
            universalLoop.stop()
            universalActive = false
            clearScenarioDecisions()
        }
        abandonRecording(turn)
        stopped = true
        pendingAction = null
        currentDecisionButtons = null
        player.stop()
        currentHighlight = null
        lastHighlightQuery = null
        lastHighlightScreenSig = ""
        awaitingResettle = false
        currentChoices = emptyList()
        if (engine?.task?.id == "open_ended") {
            engine = null
            lastUserRequest = ""
            activePlanScreenSignature = ""
            guidanceStepNumber = 1
        }
        // Drop the app pin: after a stop the user may pick a different app, and
        // a stale pin would make guidance wait forever for the old one.
        chosenPackage = ""
        val phrase = Phrases.get(Phrases.Key.STOPPED, lastLanguage)
        render(OverlayCommand(PillState.IDLE, expanded = true,
            instruction = phrase.text, highlight = null))
        publishDebug(turn) { it.copy(note = "stopped by user at step ${engine?.currentStep?.id ?: "-"}") }
    }

    /** True once the user has stopped and before they resume. */
    val isStopped: Boolean get() = stopped

    private fun startUniversalGoal(turn: Int, goal: String, ambiguous: Boolean) {
        if (!isCurrent(turn)) return
        universalActive = true
        universalTurn = turn
        engine = null
        currentUsefulInstruction = null
        currentHighlight = null
        lastHighlightQuery = null
        lastHighlightScreenSig = ""
        awaitingResettle = false
        invalidationTurn = -1
        clearHighlightInstant()
        currentChoices = emptyList()
        clearScenarioDecisions()
        lastUserRequest = goal
        renderIfCurrent(
            turn,
            OverlayCommand(
                PillState.THINKING,
                expanded = true,
                instruction = "Проверяю текущий экран…",
                language = lastLanguage,
                highlight = null,
            ),
        )
        val targetPackage = DeviceContextProvider.snapshot(context)
            .findUniquelyMentionedLaunchableApp(goal)
            ?.packageName
        universalLoop.startGoal(goal, targetPackage = targetPackage, ambiguous = ambiguous,
            latency = latencyTurn?.takeIf { it.first == turn }?.second)
    }

    private fun handleUniversalEmission(emission: UniversalGuidanceEmission) {
        val turn = universalTurn
        if ((!universalActive && !emission.terminalPresentation) || !isCurrent(turn)) return
        when (val effect = emission.effect) {
            ScenarioEffect.BeginRecognition -> startListening(replacingUniversal = false)
            ScenarioEffect.AnalyzeCurrentScreen,
            is ScenarioEffect.RetryPlanning,
            -> Unit

            ScenarioEffect.SaveResumeCapsule -> {
                val safeSession = universalLoop.session
                bg.post { resumeCapsules.save(safeSession) }
            }

            ScenarioEffect.DeleteResumeCapsule -> bg.post { resumeCapsules.delete() }

            ScenarioEffect.ClearHighlight -> {
                currentHighlight = null
                clearHighlightInstant()
            }

            ScenarioEffect.CancelPendingWork -> {
                player.stop()
                screenObservationId.incrementAndGet()
            }

            ScenarioEffect.StartNetworkMonitoring -> {
                val observedTurn = turn
                networkMonitor.start {
                    bg.post {
                        if (
                            universalActive &&
                            universalTurn == observedTurn &&
                            isCurrent(observedTurn)
                        ) {
                            universalLoop.networkRestored()
                        }
                    }
                }
            }

            ScenarioEffect.StopNetworkMonitoring -> networkMonitor.stop()

            ScenarioEffect.StopSpeech -> player.stop()

            ScenarioEffect.ShowIdle -> renderIfCurrent(
                turn,
                OverlayCommand(
                    PillState.IDLE,
                    expanded = true,
                    instruction = Phrases.get(Phrases.Key.STOPPED, lastLanguage).text,
                    language = lastLanguage,
                    highlight = null,
                ),
            )

            ScenarioEffect.FinishScenario -> {
                clearScenarioDecisions()
                universalActive = false
            }

            ScenarioEffect.RequestManualScroll -> {
                currentHighlight = null
                renderIfCurrent(
                    turn,
                    OverlayCommand(
                        PillState.GUIDING,
                        expanded = true,
                        instruction = "Не могу надёжно выделить цель. Проверьте подписи и при необходимости прокрутите экран или заполните поле сами. Я продолжу после изменения экрана.",
                        language = lastLanguage,
                        highlight = null,
                    ),
                )
            }

            is ScenarioEffect.ShowTargetIssue -> {
                clearScenarioDecisions()
                currentHighlight = null
                if (effect.issue != ScenarioTargetIssue.NOT_VISIBLE) {
                    pendingScenarioPrimary = ScenarioChoice.REPHRASE
                    pendingScenarioSecondary = ScenarioChoice.RETRY
                    currentDecisionButtons = DecisionButtons("Уточнить", "Повторить")
                }
                val message = when (effect.issue) {
                    ScenarioTargetIssue.NOT_VISIBLE -> "Цель за пределами видимой области. Прокрутите экран вручную."
                    ScenarioTargetIssue.AMBIGUOUS -> "Нашлось несколько подходящих элементов. Уточните, какой нужен."
                    ScenarioTargetIssue.NOT_ACTIONABLE -> "Не могу выделить доступную кнопку или поле. Выполните действие вручную, затем нажмите «Повторить»."
                    ScenarioTargetIssue.NOT_FOUND -> "Не нашёл нужный элемент. Уточните запрос или повторите попытку."
                }
                renderIfCurrent(turn, OverlayCommand(
                    PillState.GUIDING, expanded = true, instruction = message,
                    language = lastLanguage, highlight = null, decisionButtons = currentDecisionButtons,
                ))
            }

            ScenarioEffect.RequestManualModalDismiss -> {
                currentHighlight = null
                renderIfCurrent(
                    turn,
                    OverlayCommand(
                        PillState.GUIDING,
                        expanded = true,
                        instruction = "Появилось окно поверх экрана. Закройте его сами; после закрытия я продолжу помощь.",
                        language = lastLanguage,
                        highlight = null,
                    ),
                )
            }

            ScenarioEffect.RecheckModalAfterSettling -> bg.postDelayed({
                if (universalActive && isCurrent(turn)) universalLoop.recheckCurrentScreen()
            }, 350L)

            is ScenarioEffect.ConfirmGoal -> {
                // The target app may have become visible because the user
                // opened it manually. From here START confirms guidance, not
                // the now-stale launch card.
                pendingTargetAppPackage = null
                pendingScenarioPrimary = ScenarioChoice.START
                pendingScenarioSecondary = ScenarioChoice.CANCEL
                currentDecisionButtons = DecisionButtons.START_OR_CANCEL
                currentChoices = emptyList()
                renderIfCurrent(
                    turn,
                    OverlayCommand(
                        PillState.GUIDING,
                        expanded = true,
                        instruction = "Цель: «${effect.goal}». Начать пошаговую помощь?",
                        language = lastLanguage,
                        highlight = null,
                        decisionButtons = currentDecisionButtons,
                    ),
                )
            }

            is ScenarioEffect.ShowAnswer -> {
                currentUsefulInstruction = effect.instruction
                currentHighlight = null
                currentChoices = emptyList()
                renderIfCurrent(
                    turn,
                    OverlayCommand(
                        PillState.IDLE,
                        expanded = true,
                        instruction = effect.instruction,
                        language = lastLanguage,
                        highlight = null,
                    ),
                )
            }

            is ScenarioEffect.ShowGuidance -> {
                val target = emission.resolvedTarget ?: return
                currentDecisionButtons = null
                currentChoices = emptyList()
                currentHighlight = HighlightBounds(
                    target.left,
                    target.top,
                    target.right,
                    target.bottom,
                    "rect",
                    true,
                )
                val instruction = effect.guidance.instruction
                val numbered = if (
                    universalLoop.session.phase == ScenarioPhase.WAITING_FOR_MODAL_DISMISS ||
                    instruction.trimStart().startsWith("Шаг ", ignoreCase = true)
                ) {
                    instruction
                } else {
                    "Шаг ${universalLoop.session.stepNumber}. $instruction"
                }
                currentUsefulInstruction = numbered
                renderIfCurrent(
                    turn,
                    OverlayCommand(
                        PillState.GUIDING,
                        expanded = true,
                        instruction = numbered,
                        language = lastLanguage,
                        highlight = currentHighlight,
                    ),
                )
            }

            is ScenarioEffect.SpeakText -> speech.post {
                // The corresponding visual effect has already run on main.
                speak(Spoken(effect.text, lastLanguage), turn, emission.latency,
                    emission::isCurrent, emission::deliverIfCurrent)
            }

            is ScenarioEffect.ShowDecision -> showScenarioDecision(turn, effect)

            is ScenarioEffect.ShowTargetAppChoice -> {
                val app = DeviceContextProvider.snapshot(context).apps
                    .singleOrNull { it.packageName == effect.packageName && it.enabled && it.launchable }
                if (app == null) {
                    showLocalMessage(turn, "Не удалось проверить выбранное приложение на этом устройстве.", PillState.ERROR)
                    return
                }
                pendingTargetAppPackage = app.packageName
                pendingScenarioPrimary = ScenarioChoice.START
                pendingScenarioSecondary = ScenarioChoice.CANCEL
                currentDecisionButtons = DecisionButtons("Открыть ${app.label}", "Отмена")
                renderIfCurrent(
                    turn,
                    OverlayCommand(
                        PillState.GUIDING,
                        expanded = true,
                        instruction = "Открыть приложение ${app.label}?",
                        language = lastLanguage,
                        highlight = null,
                        decisionButtons = currentDecisionButtons,
                    ),
                )
            }

            is ScenarioEffect.LaunchTargetApp -> {
                if (!AppLauncher.launchPackage(context, effect.packageName)) {
                    showLocalMessage(turn, "Не удалось открыть выбранное приложение.", PillState.ERROR)
                } else {
                    renderIfCurrent(
                        turn,
                        OverlayCommand(
                            PillState.THINKING,
                            expanded = true,
                            instruction = "Жду, пока приложение откроется…",
                            language = lastLanguage,
                            highlight = null,
                        ),
                    )
                }
            }

            is ScenarioEffect.ShowSafeHandoff -> {
                currentHighlight = null
                renderIfCurrent(
                    turn,
                    OverlayCommand(
                        PillState.IDLE,
                        expanded = true,
                        instruction = context.getString(R.string.safe_handoff_message),
                        language = lastLanguage,
                        highlight = null,
                    ),
                )
            }

            is ScenarioEffect.ShowRecoverableFailure -> showRecoverableFailure(turn, effect)
        }
    }

    private fun showScenarioDecision(turn: Int, effect: ScenarioEffect.ShowDecision) {
        val command = scenarioDecisionCommand(effect, lastLanguage, currentUsefulInstruction, currentHighlight)
        pendingScenarioPrimary = effect.choices.getOrNull(0)
        pendingScenarioSecondary = effect.choices.getOrNull(1)
        currentDecisionButtons = command.decisionButtons
        renderIfCurrent(turn, command)
    }

    private fun showRecoverableFailure(
        turn: Int,
        effect: ScenarioEffect.ShowRecoverableFailure,
    ) {
        pendingScenarioChoices = effect.choices
        val labels = effect.choices.map(::scenarioChoiceLabel)
        currentChoices = labels
        currentDecisionButtons = null
        val message = when (effect.reason) {
            ScenarioFailureReason.SCROLL_LIMIT ->
                "После трёх прокруток цель не найдена. Можно переформулировать просьбу, начать заново или остановиться."
            ScenarioFailureReason.INVALID_PLANNER_RESPONSE -> "AI дважды вернул неподходящий формат."
            ScenarioFailureReason.REMOTE_PLANNER_FAILURE -> "Сервис AI временно не ответил."
            ScenarioFailureReason.CONFIGURATION -> "Не настроено подключение к AI."
        }
        renderIfCurrent(
            turn,
            OverlayCommand(
                PillState.ERROR,
                expanded = true,
                instruction = message,
                language = lastLanguage,
                highlight = null,
                choices = labels,
            ),
        )
    }

    private fun handleScenarioChoice(choice: ScenarioChoice, targetPackage: String? = null) {
        when (choice) {
            ScenarioChoice.CONFIRM_RECOGNITION -> universalLoop.confirmRecognition()
            ScenarioChoice.REPEAT_RECOGNITION -> universalLoop.repeatRecognition()
            ScenarioChoice.START -> if (targetPackage != null) {
                universalLoop.selectTargetApp(targetPackage)
            } else {
                universalLoop.confirmGoal()
            }
            ScenarioChoice.CANCEL,
            ScenarioChoice.STOP,
            -> {
                universalLoop.stop(deliverIdleAfterDeactivation = true)
                universalActive = false
            }
            ScenarioChoice.CONTINUE -> universalLoop.continueAfterAuth()
            ScenarioChoice.RETRY -> universalLoop.retry()
            ScenarioChoice.REPHRASE -> universalLoop.rephrase()
            ScenarioChoice.RESTART -> universalLoop.restart()
        }
    }

    private fun clearScenarioDecisions() {
        pendingScenarioPrimary = null
        pendingScenarioSecondary = null
        pendingScenarioChoices = emptyList()
        pendingTargetAppPackage = null
        currentDecisionButtons = null
    }

    private fun scenarioChoiceLabel(choice: ScenarioChoice): String = when (choice) {
        ScenarioChoice.REPHRASE -> "Переформулировать"
        ScenarioChoice.RESTART -> "Начать заново"
        ScenarioChoice.STOP -> "Остановить"
        ScenarioChoice.RETRY -> "Повторить"
        ScenarioChoice.CONTINUE -> "Продолжить"
        ScenarioChoice.START -> "Начать"
        ScenarioChoice.CANCEL -> "Отмена"
        ScenarioChoice.CONFIRM_RECOGNITION -> "Верно"
        ScenarioChoice.REPEAT_RECOGNITION -> "Повторить"
    }

    /**
     * Run a task by id with no speech involved.
     *
     * This is the rehearsal path: it exercises the identical engine, overlay and
     * cursor code as a spoken request, minus STT. Useful on stage when the room
     * is too loud to trust a microphone, and the only way to regression-test the
     * flow without a human voice. Legacy callers may still pass [language], but
     * [Language.normalize] keeps the rehearsal surface Russian.
     */
    fun startTaskById(taskId: String, language: String = lastLanguage) {
        val turn = newTurn()
        pendingResumeCapsule = null
        bg.post { resumeCapsules.delete() }
        stopUniversalForReplacement(turn)
        abandonRecording(turn)
        stopped = false
        chosenPackage = ""
        lastLanguage = Language.normalize(language)
        val task = tasks.byId(taskId)
        if (task == null) {
            val none = Phrases.get(Phrases.Key.NO_TASKS, lastLanguage)
            render(OverlayCommand(PillState.ERROR, expanded = true,
                instruction = none.text, language = none.language))
            return
        }
        val e = StepEngine(task); engine = e
        publishDebug(turn) { it.copy(intent = task.id, language = lastLanguage, note = "rehearsal (no STT)") }
        presentCurrentStep(e, turn, speak = true)
    }

    private fun abandonRecording(turn: Int) {
        val activeCapture = voiceCaptureState.invalidateAll()
        if (activeCapture != null) {
            // The capture belongs to a turn the user has just walked away from.
            capture.post {
                recorder.stop()
                inputFileFor(activeCapture.turn).delete()
                purgeStaleCaptures(turn)
            }
        }
    }

    private fun stopUniversalForReplacement(turn: Int) {
        if (!universalActive) return
        universalTurn = turn
        universalLoop.stop()
        universalActive = false
        clearScenarioDecisions()
    }

    // --- Voice loop -----------------------------------------------------------

    private fun screenSafetyAssessment(snapshot: ScreenSnapshot): ScreenSafetyAssessment {
        val appLabel = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(snapshot.packageName, 0),
            ).toString().trim().takeIf(String::isNotEmpty)
        }.getOrNull()
        return ScreenSafetyGate.assess(snapshot, appLabel)
    }

    private fun currentScreenSafetyAssessment(): ScreenSafetyAssessment? =
        ScreenReaderService.instance?.snapshot()?.let(::screenSafetyAssessment)

    /** Local-only handoff: no recording, planner call, synthesis or speech. */
    private fun enterVoiceSafetyHandoff(turn: Int, assessment: ScreenSafetyAssessment) {
        if (!isCurrent(turn)) return
        val activeCapture = voiceCaptureState.invalidateAll()
        if (activeCapture != null) {
            capture.post {
                recorder.stop()
                inputFileFor(activeCapture.turn).delete()
            }
        }
        if (universalActive) {
            // stop() emits cleanup synchronously. Point emissions at the new
            // current turn before dispatch so they cannot be discarded.
            universalTurn = turn
            universalLoop.stop()
            universalActive = false
        }
        networkMonitor.stop()
        player.stop()
        bg.post { resumeCapsules.delete() }
        clearScenarioDecisions()
        pendingAction = null
        currentChoices = emptyList()
        currentHighlight = null
        lastHighlightQuery = null
        lastHighlightScreenSig = ""
        awaitingResettle = false
        circle.clear()
        clearHighlightInstant()
        renderIfCurrent(
            turn,
            OverlayCommand(
                PillState.IDLE,
                expanded = true,
                instruction = assessment.explanation,
                language = Language.APP_DEFAULT,
                highlight = null,
            ),
        )
    }

    /** Stop every tree/speech path and delete a clip as soon as a protected screen appears. */
    private fun enforceCurrentScreenSafetyBoundary(): Boolean {
        val assessment = voiceSafetyBoundary.unsafeAssessment() ?: return false
        enterVoiceSafetyHandoff(newTurn(), assessment)
        return true
    }

    private fun startListening(replacingUniversal: Boolean = true) {
        val turn = newTurn()
        if (replacingUniversal) {
            pendingResumeCapsule = null
            bg.post { resumeCapsules.delete() }
            stopUniversalForReplacement(turn)
        } else {
            universalTurn = turn
        }
        stopped = false
        pendingAction = null
        currentDecisionButtons = null

        voiceSafetyBoundary.unsafeAssessment()?.let { assessment ->
            enterVoiceSafetyHandoff(turn, assessment)
            return
        }

        // A denied microphone used to fail silently: recorder.start() returned
        // false and the app quietly ran the deterministic task, so the user was
        // never told why the voice loop does nothing. Say it, then keep guiding.
        if (!hasMicPermission()) {
            publishDebug(turn) { it.copy(note = "RECORD_AUDIO denied") }
            val message = "Разрешите микрофон или напишите просьбу текстом."
            if (!restoreRepeatRecognitionDecision(turn, message)) {
                showLocalMessage(turn, message, PillState.ERROR)
            }
            return
        }
        if (!Sarvam.isConfigured()) {
            publishDebug(turn) { it.copy(note = "no AI gateway — voice unavailable") }
            val message = "Голос сейчас недоступен. Напишите просьбу текстом."
            if (!restoreRepeatRecognitionDecision(turn, message)) {
                showLocalMessage(turn, message, PillState.ERROR)
            }
            return
        }
        val activeCapture = when (val start = voiceCaptureState.begin(turn)) {
            is VoiceCaptureStart.Blocked -> {
                enterVoiceSafetyHandoff(turn, start.assessment)
                return
            }
            is VoiceCaptureStart.Started -> start.capture
        }
        val captureToken = activeCapture.token

        // Flip the pill first: the user must see "Listening…" the instant they
        // tap, not after the capture thread has drained a pending stop().
        currentHighlight = null
        renderIfCurrent(turn, OverlayCommand(PillState.LISTENING, expanded = true,
            instruction = Phrases.get(Phrases.Key.LISTENING, lastLanguage).text,
            language = lastLanguage))

        capture.post {
            purgeStaleCaptures(turn)
            if (!voiceCaptureState.isCurrent(captureToken, VoiceCapturePhase.STARTING)) {
                inputFileFor(turn).delete()
                return@post
            }
            var started = false
            val assessment = voiceSafetyBoundary.runIfAllowed {
                if (voiceCaptureState.isCurrent(captureToken, VoiceCapturePhase.STARTING)) {
                    started = recorder.start(inputFileFor(turn))
                }
            }
            if (assessment != null) {
                // The safety observation belongs to this start attempt. If a
                // newer attempt replaced it while the capture queue was busy,
                // the stale callback must leave the newer microphone alone.
                if (!voiceCaptureState.isCurrent(captureToken, VoiceCapturePhase.STARTING)) {
                    inputFileFor(turn).delete()
                    return@post
                }
                if (voiceCaptureState.invalidateIfOwned(captureToken) == null) return@post
                enterVoiceSafetyHandoff(turn, assessment)
                return@post
            }
            if (started && voiceCaptureState.markRecording(captureToken)) return@post
            if (started) {
                recorder.stop()
                inputFileFor(turn).delete()
                return@post
            }
            // A quick second tap may have moved STARTING → STOPPING before
            // AudioRecord was created. The queued stop owns that normal path;
            // do not turn it into a microphone error or invalidate its token.
            if (!voiceCaptureState.isCurrent(captureToken, VoiceCapturePhase.STARTING)) {
                return@post
            }
            if (voiceCaptureState.invalidateIfOwned(captureToken) == null) return@post
            val message = "Не удалось включить микрофон. Попробуйте текстовый ввод."
            if (!restoreRepeatRecognitionDecision(turn, message)) {
                showLocalMessage(turn, message, PillState.ERROR)
            }
        }

        // Close the turn on our own clock as well as on a second tap.
        //
        // A pure toggle mic has two failure modes and both read to the user as
        // "it isn't listening": tap once and speak, and the capture never ends
        // so nothing is ever transcribed; tap twice quickly and the clip lands
        // under MIN_SPEECH_MS, which skips the STT call entirely and answers
        // with the spoken fallback. Measured on device: a 265 ms hold, no STT
        // request, assistant talks anyway.
        //
        // A ceiling makes one tap sufficient. Tapping again still stops early,
        // so this only ever adds an ending where there wasn't one.
        bg.postDelayed({
            if (voiceCaptureState.isListening(turn)) stopAndProcess()
        }, MAX_UTTERANCE_MS)
    }

    private fun stopAndProcess() {
        // The capture's own turn, not whatever is current — they diverge if
        // anything opened a turn while the user was still speaking.
        val activeCapture = voiceCaptureState.beginStopping() ?: return
        val turn = activeCapture.turn
        val captureToken = activeCapture.token
        latencyTurn = turn to ScenarioLatencyTrace(SystemClock::elapsedRealtime, telemetrySink)

        voiceSafetyBoundary.unsafeAssessment()?.let { assessment ->
            if (voiceCaptureState.invalidateIfOwned(captureToken) == null) return
            capture.post {
                recorder.stop()
                inputFileFor(turn).delete()
            }
            enterVoiceSafetyHandoff(turn, assessment)
            return
        }

        render(OverlayCommand(PillState.THINKING, expanded = true,
            instruction = Phrases.get(Phrases.Key.THINKING, lastLanguage).text,
            language = lastLanguage))

        capture.post {
            recorder.stop()
            val wav = inputFileFor(turn)
            val heldMs = if (wav.exists()) recorder.recordedMs else 0L
            if (!voiceCaptureState.markProcessing(captureToken)) {
                wav.delete()
                return@post
            }
            bg.post { process(turn, heldMs, captureToken) }
        }
    }

    /** Runs on [bg]. The whole network half of a voice turn. */
    private fun process(turn: Int, heldMs: Long, captureToken: VoiceCaptureToken) {
        val wav = inputFileFor(turn)

        if (
            !voiceCaptureState.isCurrent(captureToken, VoiceCapturePhase.PROCESSING) ||
            !isCurrent(turn)
        ) {
            wav.delete()
            return
        }

        voiceSafetyBoundary.unsafeAssessment()?.let { assessment ->
            if (!voiceCaptureState.isCurrent(captureToken, VoiceCapturePhase.PROCESSING)) {
                wav.delete()
                return
            }
            if (voiceCaptureState.invalidateIfOwned(captureToken) == null) {
                wav.delete()
                return
            }
            wav.delete()
            enterVoiceSafetyHandoff(turn, assessment)
            return
        }

        // A double-tapped mic leaves a header-only WAV. Sending it costs a full
        // round trip to be told nothing was said, which reads on stage as the
        // app hanging.
        if (heldMs < MIN_SPEECH_MS) {
            voiceCaptureState.finishIfOwned(captureToken)
            publishDebug(turn) { it.copy(note = "too short (${heldMs}ms) — no STT call") }
            wav.delete()
            if (!restoreRepeatRecognitionDecision(
                    turn,
                    Phrases.get(Phrases.Key.HOLD_LONGER, lastLanguage).text,
                )
            ) {
                fallbackAfterFailedSpeech(turn, Phrases.Key.HOLD_LONGER)
            }
            return
        }

        // Timed around the network call alone. Measuring from before
        // recorder.stop() folded in its writer-thread join (up to 1.5 s) and
        // made STT look far over budget when it was not.
        // Last possible local check before bytes leave the device. A protected
        // screen event invalidates the generation even while stop() is queued.
        if (!voiceCaptureState.isCurrent(captureToken, VoiceCapturePhase.PROCESSING) ||
            !isCurrent(turn)
        ) {
            wav.delete()
            return
        }
        voiceSafetyBoundary.unsafeAssessment()?.let { assessment ->
            if (!voiceCaptureState.isCurrent(captureToken, VoiceCapturePhase.PROCESSING) ||
                !isCurrent(turn)
            ) {
                wav.delete()
                return
            }
            if (voiceCaptureState.invalidateIfOwned(captureToken) == null) {
                wav.delete()
                return
            }
            wav.delete()
            enterVoiceSafetyHandoff(turn, assessment)
            return
        }

        val t0 = SystemClock.uptimeMillis()
        val trace = latencyTurn?.takeIf { it.first == turn }?.second
        val sttResult = if (trace == null) stt.transcribe(wav) else trace.measure(
            ScenarioLatencyStage.STT,
            outcome = { if (it == null) ScenarioLatencyOutcome.FAILURE else ScenarioLatencyOutcome.SUCCESS },
        ) { stt.transcribe(wav) }
        val sttMs = SystemClock.uptimeMillis() - t0
        wav.delete()
        voiceCaptureState.finishIfOwned(captureToken)

        if (!isCurrent(turn)) return
        currentScreenSafetyAssessment()
            ?.takeUnless { it.disposition == ScreenDisposition.ALLOW }
            ?.let { assessment ->
                enterVoiceSafetyHandoff(turn, assessment)
                return
            }

        if (sttResult == null) {
            publishDebug(turn) { it.copy(sttMs = sttMs, note = "STT returned nothing") }
            if (!restoreRepeatRecognitionDecision(
                    turn,
                    Phrases.get(Phrases.Key.DIDNT_CATCH, lastLanguage).text,
                )
            ) {
                fallbackAfterFailedSpeech(turn, Phrases.Key.DIDNT_CATCH)
            }
            return
        }
        // Everything the assistant says from here on is chosen for this.
        lastLanguage = sttResult.language
        // The authorisation layer must see what the user ACTUALLY said, not a
        // task title from the DSL. Without this, a guided task authorises
        // launches against a string like "Book a taxi", which names no app, so
        // every launch is refused for the wrong reason.
        lastUserRequest = sttResult.transcript
        guidanceStepNumber = 1
        activePlanScreenSignature = ""
        // A fresh spoken request supersedes any earlier list selection.
        userSelectedPackage = ""
        publishDebug(turn) {
            it.copy(heard = sttResult.transcript, sttMs = sttMs, language = lastLanguage)
        }

        // RepeatRecognition deliberately keeps the universal session alive
        // while the microphone records. Once a replacement transcript exists,
        // retire that recognizing generation silently before routing the new
        // request; special local actions must not leave a hidden universal
        // session intercepting Next/Stop behind their confirmation UI.
        if (
            universalActive &&
            universalTurn == turn &&
            universalLoop.session.phase == ScenarioPhase.RECOGNIZING_GOAL
        ) {
            universalActive = false
            universalLoop.stop()
            clearScenarioDecisions()
        }

        // Low confidence is useful only when the provider actually supplies
        // it. Missing confidence is not treated as low. Empty/very short text
        // is deterministically ambiguous and cannot authorize any local action.
        if (SarvamStt.isAmbiguous(sttResult)) {
            startUniversalGoal(turn, sttResult.transcript.trim(), ambiguous = true)
            return
        }

        if (maybeProposeAllowedAction(sttResult.transcript, turn)) return

        // Persistent memory: if we're waiting for an app choice, see if they spoke it.
        val activeApps = offeredApps
        if (activeApps.isNotEmpty()) {
            val transcriptLower = sttResult.transcript.lowercase()
            val matchIdx = activeApps.indexOfFirst { app ->
                transcriptLower.contains(app.label.lowercase())
            }
            if (matchIdx >= 0) {
                publishDebug(turn) { it.copy(note = "matched spoken choice: ${activeApps[matchIdx].label}") }
                offeredApps = emptyList() // Clear so we don't trap future turns
                // onChoiceTapped starts a new turn internally, so we don't need to post it to bg
                // since we are already on bg, but it's safe to call directly.
                onChoiceTapped(matchIdx)
                return
            }
        }

        // A live circle selection claims the request first: "help me pay this"
        // only means anything relative to what "this" was. onCircleRequest
        // returns false for an informational question it cannot answer from
        // the accessibility tree alone, which falls through to the normal
        // planner path below with the selection added to its prompt context.
        if (circle.context != null && onCircleRequest(sttResult.transcript)) {
            return
        }

        // Clear offeredApps so a previous app choice cannot trap a new goal.
        offeredApps = emptyList()
        startUniversalGoal(turn, sttResult.transcript.trim(), ambiguous = false)
    }

    /**
     * Speech failed. Keep the user moving in their own language rather than
     * dead-ending, and resume where they were if a task is already running.
     */
    private fun fallbackAfterFailedSpeech(turn: Int, key: Phrases.Key) {
        val phrase = Phrases.get(key, lastLanguage)
        currentHighlight = null
        engine = null
        renderIfCurrent(turn, OverlayCommand(PillState.ERROR, expanded = true,
            instruction = phrase.text, language = phrase.language, highlight = null))
        speech.post { speak(phrase, turn) }
    }

    /** Keep RepeatRecognition failures actionable instead of hiding the session behind an error. */
    private fun restoreRepeatRecognitionDecision(turn: Int, message: String): Boolean {
        if (
            !universalActive ||
            universalTurn != turn ||
            !isCurrent(turn) ||
            universalLoop.session.phase != ScenarioPhase.RECOGNIZING_GOAL
        ) {
            return false
        }
        universalLoop.recognitionFailed()
        renderIfCurrent(
            turn,
            OverlayCommand(
                PillState.ERROR,
                expanded = true,
                instruction = message,
                language = lastLanguage,
                highlight = null,
                decisionButtons = DecisionButtons.RETRY_OR_STOP,
            ),
        )
        return true
    }

    private fun processOpenEndedNext(
        turn: Int,
        intent: String,
        completedStep: String? = null,
    ) {
        // Never plan from a snapshot captured by a previous queued callback.
        // First prove this turn still owns the request, then read exactly one
        // fresh tree and use that same tree for the safety gate, planner and
        // target resolution below.
        if (!isCurrent(turn)) return
        val snap = ScreenReaderService.instance?.snapshot() ?: ScreenSnapshot.EMPTY
        if (!isCurrent(turn)) return
        screenSafetyAssessment(snap).takeUnless { it.disposition == ScreenDisposition.ALLOW }
            ?.let { assessment ->
                enterVoiceSafetyHandoff(turn, assessment)
                return
            }
        activePlanScreenSignature = snap.signature()
        
        renderIfCurrent(turn, OverlayCommand(PillState.THINKING, expanded = true,
            instruction = Phrases.get(Phrases.Key.THINKING, lastLanguage).text,
            language = lastLanguage))

        // A protected-screen event opens a new turn. Check ownership at the
        // final local boundary before any screen data leaves the device.
        if (!isCurrent(turn)) return

        val tp0 = SystemClock.uptimeMillis()
        val plan = planner.planOpenEnded(
            intent,
            snap,
            lastLanguage,
            completedStep,
            guidanceStepNumber,
        )
        val planMs = SystemClock.uptimeMillis() - tp0
        
        if (!isCurrent(turn)) return
        currentScreenSafetyAssessment()
            ?.takeUnless { it.disposition == ScreenDisposition.ALLOW }
            ?.let { assessment ->
                enterVoiceSafetyHandoff(turn, assessment)
                return
            }
        
        if (plan != null) {
            if (plan.isDone || plan.actionType == "answer") {
                currentHighlight = null
                engine = null
                activePlanScreenSignature = ""
                val textToSpeak = if (plan.instruction.isNotBlank()) {
                    Spoken(plan.instruction, plan.language)
                } else {
                    Phrases.get(Phrases.Key.ALL_DONE, lastLanguage)
                }
                renderIfCurrent(turn, OverlayCommand(PillState.IDLE, expanded = true,
                    instruction = textToSpeak.text, language = textToSpeak.language,
                    highlight = null))
                speech.post { speak(textToSpeak, turn) }
                return
            }
            
            // The open-ended path can tap, type and launch apps. Until now the
            // only gate was `plan != null` above, so a confident plan acted
            // unconditionally. Validate against the screen BEFORE building the
            // step, and degrade to `guide` when the plan cannot be justified —
            // the assistant still points and speaks, it just does not act.
            val targetResolves = snap.boundsForResourceId(plan.targetResourceId) != null ||
                (plan.targetText.isNotEmpty() && snap.boundsForText(listOf(plan.targetText)) != null)
            val verdict = SafetyGuard.validateOpenEndedAction(
                userRequest = intent,
                actionType = plan.actionType,
                targetResourceId = plan.targetResourceId,
                targetText = plan.targetText,
                actionPayload = plan.actionPayload,
                elementCount = snap.elements.size,
                settled = snap.settled,
                targetResolves = targetResolves,
            )
            // A launch needs authorisation as well as validity. The evaluator
            // caught "find an app for booking a cab" being answered with a
            // confident launch of Uber on a phone that also had Ola: a valid
            // target, but not one the user chose. Naming no app authorises no
            // launch, which also covers content requests ("find my downloaded
            // PDF") — opening Files is not finding the file.
            val launchVerdict = if (plan.actionType == "launch_app") {
                SafetyGuard.validateLaunchAuthorization(
                    userRequest = intent,
                    resolution = DeviceContextProvider.snapshot(context)
                        .resolveApp(plan.actionPayload),
                    userSelectedPackage = userSelectedPackage.ifBlank { null },
                )
            } else {
                SafetyGuard.Verdict.Allow
            }

            val blockedReason = (verdict as? SafetyGuard.Verdict.Block)?.reason
                ?: (launchVerdict as? SafetyGuard.Verdict.Block)?.reason
            // The model may explain, answer, or nominate a highlight. It can
            // never manufacture an executable action; those are created only
            // by maybeProposeAllowedAction from the two local configured IDs.
            val modelTriedAction = plan.actionType !in setOf("guide", "answer")
            val safeActionType = if (blockedReason != null || modelTriedAction) "guide"
                else plan.actionType

            // Create a single step guided task so we can reuse the presentation logic
            val numberedInstruction = if (plan.instruction.trimStart()
                    .startsWith("Шаг ", ignoreCase = true)) {
                plan.instruction
            } else {
                "Шаг $guidanceStepNumber. ${plan.instruction}"
            }
            val openEndedStep = TaskStep(
                id = "open_ended_step",
                resourceId = plan.targetResourceId,
                instruction = numberedInstruction,
                instructions = mapOf(plan.language to numberedInstruction),
                textAny = if (plan.targetText.isNotEmpty()) listOf(plan.targetText) else emptyList(),
                actionType = safeActionType,
                actionPayload = plan.actionPayload,
            )
            val newTask = GuidedTask(1, "open_ended", intent, emptyList(), listOf(openEndedStep))
            val e = StepEngine(newTask)
            engine = e
            
            publishDebug(turn) {
                it.copy(
                    intent = intent, step = "open_ended_step",
                    wantResourceId = plan.targetResourceId.ifEmpty { "text:${plan.targetText}" },
                    planMs = planMs, confidence = plan.confidence,
                    language = plan.language,
                    note = when {
                        blockedReason != null -> "action blocked -> guide: $blockedReason"
                        modelTriedAction -> "model action downgraded -> guide"
                        else -> null
                    },
                )
            }
            presentCurrentStep(e, turn, speak = true)
        } else {
            publishDebug(turn) {
                it.copy(note = "planner unavailable or failed open-ended")
            }
            engine = null
            showLocalMessage(
                turn,
                "Не удалось получить подсказку. Проверьте подключение к AI и попробуйте ещё раз.",
                PillState.ERROR,
            )
        }
    }

    private fun pickTask(transcript: String): GuidedTask {
        return GuidedTask(1, "open_ended", transcript, emptyList(), emptyList())
    }

    private fun presentCurrentStep(e: StepEngine, turn: Int, speak: Boolean) {
        presentStep(e, e.currentStep.spokenFor(lastLanguage), turn, speak)
    }

    /**
     * Show instruction immediately, resolve the highlight off-thread, speak the
     * instruction. Rendering is always pushed back through the callback, and
     * always gated on [turn] still being live.
     */
    /**
     * Find the element this step is talking about, without relying on the
     * planner to have named it.
     *
     * The ring used to be drawn only from step.resourceId / step.textAny, both
     * of which come straight out of the model. When the model returned neither
     * — which it frequently does — bounds were null and nothing was
     * highlighted, so the same request highlighted on one run and not the next.
     * That is the "randomness": the visual half of the product was conditional
     * on a field the LLM could silently omit.
     *
     * So the target is derived in falling order of evidence, and the last two
     * steps need no model cooperation at all:
     *   1. an explicit resource id
     *   2. the planner's candidate texts, ranked rather than substring-matched
     *   3. the words of the instruction the user is actually being told to act
     *      on — "Tap \"Where to?\"" names its own target
     *
     * Ranked matching via TargetResolver throughout, so a near-tie is reported
     * rather than resolved by tree order. Entirely app-agnostic: no package
     * checks, no per-app tables.
     */
    private fun resolveStepBounds(target: TaskStep, instruction: String, snap: ScreenSnapshot): Rect? {
        if (target.resourceId.isNotEmpty()) {
            snap.boundsForResourceId(target.resourceId)?.let { return it }
        }
        for (t in target.textAny) {
            if (t.isBlank()) continue
            val r = TargetResolver.resolve(t, snap)
            if (r is TargetResolver.Result.Found) return r.candidate.bounds
        }
        for (q in quotedPhrases(instruction)) {
            val r = TargetResolver.resolve(q, snap)
            if (r is TargetResolver.Result.Found) return r.candidate.bounds
        }
        return null
    }

    /**
     * Phrases the instruction puts in quotes. Guidance copy names its target
     * that way ('Tap "Rentals"'), which makes the spoken sentence and the ring
     * agree by construction instead of by the model filling in two fields
     * consistently.
     */
    private fun quotedPhrases(instruction: String): List<String> =
        QUOTED.findAll(instruction)
            .map { it.groupValues[1].trim().trimEnd('?', '.', '!', ',') }
            .filter { it.length in 2..40 }
            .distinct()
            .toList()

    private fun presentStep(e: StepEngine, instruction: Spoken, turn: Int, speak: Boolean) {
        currentScreenSafetyAssessment()
            ?.takeUnless { it.disposition == ScreenDisposition.ALLOW }
            ?.let { assessment ->
                enterVoiceSafetyHandoff(turn, assessment)
                return
            }
        val step = e.currentStep
        // Drop the previous step's ring before resolving this one, so a stale
        // box never lingers over an element that is no longer the target.
        currentHighlight = null

        // A choose-app step has no on-screen element to point at — it asks a
        // question. Render the options and wait for a tap.
        if (step.kind == StepKind.CHOOSE_APP) {
            presentAppChoice(instruction, turn, speak)
            return
        }

        // A guiding step has no options; clear any left over from the question.
        currentChoices = emptyList()
        renderIfCurrent(turn, OverlayCommand(PillState.GUIDING, expanded = true,
            instruction = instruction.text, language = instruction.language, highlight = null))

        // Speech is kicked off in parallel with bounds resolution, on its own
        // thread. Bulbul's ~1.2 s is the slowest layer we have; running it here
        // meant the *next* interaction's highlight queued behind it, so an
        // impatient second tap looked like a frozen pill.
        if (speak) speech.post { speak(instruction, turn) }

        bg.post {
            if (!isCurrent(turn)) return@post

            // Resolve continuously rather than once.
            //
            // A single resolve fires whenever it happens to fire — often before
            // the target screen has finished drawing — and then draws a ring
            // around where the element was half a second ago, or nothing at all.
            // Polling re-reads the live tree until the target actually appears,
            // and only re-renders when the resolved bounds CHANGE, so a stable
            // target does not restart its pulse animation every tick.
            //
            // Reconciled from Aswin's highlight work on
            // feature/screensaathi-integration (6f35e36, 6ef64a9, 61c96b6,
            // 20ad685). Those commits predate the SafetyGuard/DeviceContext
            // layer and call AppLauncher unguarded; the polling/persistence
            // behaviour is taken, the unguarded launch is NOT — the gating below
            // is the version from this branch.
            val startedAt = SystemClock.uptimeMillis()

            // What the screen looked like when this step was planned. If the
            // user walks somewhere else, the plan behind this step describes a
            // screen that is no longer in front of them, and continuing to
            // recite it is worse than saying nothing.
            var plannedFor = ScreenReaderService.instance?.snapshot()?.signature().orEmpty()
            var replannedFor = ""

            while (isCurrent(turn) && !stopped) {
                // The package is only known once the user has picked an app, so
                // it is pinned here rather than in the task JSON.
                val target = if (chosenPackage.isNotEmpty() && step.expectPackage.isEmpty()) {
                    step.copy(expectPackage = chosenPackage)
                } else {
                    step
                }

                val snap = ScreenReaderService.instance?.snapshot()
                if (snap != null) {
                    val assessment = screenSafetyAssessment(snap)
                    if (assessment.disposition != ScreenDisposition.ALLOW) {
                        enterVoiceSafetyHandoff(turn, assessment)
                        return@post
                    }
                }
                val currentPkg = snap?.packageName.orEmpty()
                val expectedPkg = target.expectPackage.ifEmpty { chosenPackage }

                // Don't hunt for a target while the launcher or system UI is in
                // front — the element cannot be there, and matching against it
                // produces a ring on an unrelated home-screen icon.
                // The launcher is a screen too.
                //
                // This used to hard-block any match while the launcher was in
                // front, so a user still on the app drawer got spoken guidance
                // and no ring at all — the assistant naming an app it would not
                // point at. The block exists to stop a stray ring landing on an
                // unrelated home-screen icon, and that risk only exists when we
                // do not yet know which app we want.
                //
                // Once a package IS known, its icon is a legitimate target: the
                // step's own candidate texts carry the app's label, so the icon
                // resolves by label through the same ranked matcher as anything
                // else. Language-independent by construction — it matches the
                // launcher's actual label rather than inventing a package.
                val packageMatches = when {
                    expectedPkg.isNotEmpty() ->
                        currentPkg == expectedPkg || isLauncherPackage(currentPkg)
                    target.actionType != "launch_app" -> !isLauncherPackage(currentPkg)
                    else -> true
                }

                val bounds = if (packageMatches && snap != null) {
                    resolveStepBounds(target, instruction.text, snap)
                } else {
                    null
                }

                val hl = bounds?.let {
                    HighlightBounds(it.left, it.top, it.right, it.bottom,
                        target.highlight.shape, target.highlight.pulse)
                }

                // Re-plan when the screen moves out from under the step.
                //
                // The step engine walks a plan that was written for the screen
                // that was in front of the user when it was made. Someone else
                // driving the phone — a judge opening a different page — leaves
                // the assistant guiding a screen that is gone, confidently and
                // wrongly. The snapshot is already being polled here, so the
                // change is detectable; this reacts to it by asking the planner
                // again with the CURRENT screen rather than continuing.
                //
                // Guarded so it fires once per screen: replanning on every tick
                // would put the planner in a loop and never settle.
                val sig = snap?.signature().orEmpty()
                if (sig.isNotEmpty() && sig != plannedFor && sig != replannedFor &&
                    bounds == null && lastUserRequest.isNotBlank() &&
                    SystemClock.uptimeMillis() - startedAt > REPLAN_SETTLE_MS
                ) {
                    replannedFor = sig
                    plannedFor = sig
                    Log.d(TAG, "SCREEN_CHANGED replanning pkg=$currentPkg intent='$lastUserRequest'")
                    currentHighlight = null
                    guidanceStepNumber++
                    bg.post {
                        processOpenEndedNext(
                            turn,
                            lastUserRequest,
                            completedStep = instruction.text,
                        )
                    }
                    return@post
                }

                if (hl != currentHighlight) {
                    currentHighlight = hl
                    renderIfCurrent(turn, OverlayCommand(PillState.GUIDING, expanded = true,
                        instruction = instruction.text, language = instruction.language, highlight = hl))
                }

                publishDebug(turn) {
                    it.copy(
                        wantResourceId = target.resourceId.ifEmpty {
                            "text:" + target.textAny.firstOrNull().orEmpty()
                        },
                        readerBound = ScreenReaderService.instance != null,
                        screenPackage = snap?.packageName ?: "-",
                        settled = snap?.settled,
                        elementCount = snap?.elements?.size ?: 0,
                        visibleIds = snap?.elements
                            ?.filter { el -> el.resourceId.isNotEmpty() }
                            ?.joinToString(",") { el -> el.resourceId } ?: "-",
                        bounds = bounds?.toShortString() ?: "NOT FOUND",
                    )
                }

                // click/type_text are deliberately NOT auto-executed here. The
                // ring persists and the user taps the real element themselves;
                // onUserClicked() advances the step. That keeps a model-proposed
                // tap from becoming a device action the user never asked for —
                // the open gap flagged in the safety audit.
                if (SystemClock.uptimeMillis() - startedAt > TARGET_POLL_TIMEOUT_MS) break
                try { Thread.sleep(TARGET_POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
            }
        }
    }

    /**
     * A launcher/system surface, where a task's target element cannot be.
     *
     * Our own package is explicitly NOT one: the guided demo screen lives in
     * com.screensaathi, so treating it as launcher chrome would make the one
     * screen we ship impossible to point at.
     */
    private fun isLauncherPackage(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val lower = packageName.lowercase()
        if (lower == context.packageName.lowercase()) return false
        return lower.contains("launcher") || lower == "com.android.systemui" || lower == "android"
    }

    /**
     * Point at whatever the user just named, on whatever screen is in front of
     * them right now. No task, no DSL, no demo — the live accessibility tree
     * and the user's words are the only inputs.
     *
     * Re-resolves on a poll so the ring follows a target that scrolls or moves,
     * and drops it the moment the target stops existing.
     */
    /** Drop any ring and end the current turn. */
    // --- Circle selection mode ------------------------------------------------

    /**
     * Circle mode state. Held here rather than in the overlay because the
     * selection has to outlive the drawing surface — the whole point is that
     * "okay, help me use it" still knows what "it" was, several turns later.
     */
    private val circle: CircleSession by lazy {
        CircleSession(
            snapshotProvider = {
                ScreenReaderService.instance?.snapshot()?.takeIf { snapshot ->
                    screenSafetyAssessment(snapshot).disposition == ScreenDisposition.ALLOW
                }
            },
        )
    }

    /**
     * Set the session language without going through STT.
     *
     * Used by the rehearsal entry points, which skip Saaras entirely. The
     * voice path sets this from the detected language instead.
     */
    fun setLanguage(code: String) {
        lastLanguage = Language.normalize(code)
    }

    /** Localised prompt for the empty selection surface. */
    fun selectionHint(): String =
        Phrases.get(Phrases.Key.CIRCLE_HINT, lastLanguage).text

    /** Collapse out of the way so the assistant does not cover the target. */
    fun onSelectionModeEntered() {
        val turn = newTurn()
        currentScreenSafetyAssessment()
            ?.takeUnless { it.disposition == ScreenDisposition.ALLOW }
            ?.let { assessment ->
                enterVoiceSafetyHandoff(turn, assessment)
                return
            }
        renderIfCurrent(turn, OverlayCommand(PillState.IDLE, expanded = false, highlight = null))
    }

    fun onSelectionModeExited() {
        // Nothing to undo: the selection window is the overlay's to remove,
        // and any resolved context deliberately survives leaving the surface.
    }

    /**
     * A selection was drawn. Resolve it, then invite a question.
     *
     * This does not answer anything yet — the user has selected a thing but
     * not said what they want with it. The response to that request is routed
     * in [onCircleRequest], which is where the question-vs-action fork lives.
     */
    fun onSelectionDrawn(path: List<SelectionPoint>, shape: SelectionShape) {
        val turn = newTurn()
        stopped = false

        currentScreenSafetyAssessment()
            ?.takeUnless { it.disposition == ScreenDisposition.ALLOW }
            ?.let { assessment ->
                circle.clear()
                enterVoiceSafetyHandoff(turn, assessment)
                return
            }

        circle.onSelectionDrawn(path, shape, lastLanguage) { ctx ->
            // Tree-only resolution is synchronous and produces exactly one
            // description. No screenshot callback can arrive later.
            describeSelection(turn, ctx)
        }
    }

    /**
     * Say what was selected, honestly.
     *
     * Three outcomes, and the third is the one that matters most: a purely
     * visual region gets an explicit "I'd need visual understanding" rather
     * than a guess. For a tool whose users often cannot check the answer
     * themselves, a confident wrong description is worse than no description.
     */
    private fun describeSelection(turn: Int, ctx: CircleContext) {
        if (!isCurrent(turn)) return
        currentScreenSafetyAssessment()
            ?.takeUnless { it.disposition == ScreenDisposition.ALLOW }
            ?.let { assessment ->
                circle.clear()
                enterVoiceSafetyHandoff(turn, assessment)
                return
            }

        val element = ctx.target.element
        val lead = when {
            element != null && element.text.isNotBlank() ->
                Phrases.get(Phrases.Key.CIRCLE_FOUND, lastLanguage)
                    .let { it.copy(text = String.format(it.text, element.text.trim())) }

            ctx.target.selectedText.isNotBlank() ->
                Phrases.get(Phrases.Key.CIRCLE_SELECTED_TEXT, lastLanguage)
                    .let { it.copy(text = String.format(it.text, ctx.target.selectedText.take(80))) }

            ctx.needsVision -> Phrases.get(Phrases.Key.CIRCLE_NEEDS_VISION, lastLanguage)

            else -> Phrases.get(Phrases.Key.CIRCLE_NOTHING_FOUND, lastLanguage)
        }

        val ask = Phrases.get(Phrases.Key.CIRCLE_ASK, lastLanguage)
        val resolved = element != null || ctx.target.selectedText.isNotBlank()

        renderIfCurrent(
            turn,
            OverlayCommand(
                if (resolved) PillState.IDLE else PillState.ERROR,
                expanded = true,
                instruction = if (resolved) "${lead.text} ${ask.text}" else lead.text,
                language = lead.language,
                highlight = null,
            ),
        )
        speak(lead, turn)

        publishDebug(turn) {
            it.copy(
                wantResourceId = element?.resourceId.orEmpty().ifEmpty { element?.text ?: "(none)" },
                confidence = ctx.target.confidence / 100.0,
                note = "CIRCLE_RESOLVED ${ctx.target.reason}",
            )
        }
    }

    /**
     * A request made about the current selection.
     *
     * The fork that separates this from ordinary circle-to-search: an
     * informational question is answered in place, an actionable one is handed
     * to the existing agent loop via [highlightTarget] — the same resolver,
     * overlay and screen-change invalidation the voice path already uses. No
     * second agent framework.
     *
     * Returns false when there is no live selection, so the caller can fall
     * back to the ordinary voice path.
     */
    fun onCircleRequest(request: String): Boolean {
        currentScreenSafetyAssessment()
            ?.takeUnless { it.disposition == ScreenDisposition.ALLOW }
            ?.let { assessment ->
                circle.clear()
                enterVoiceSafetyHandoff(newTurn(), assessment)
                return true
            }
        val ctx = circle.context ?: return false
        val intent = IntentClassifier.classify(request)

        circle.addTurn(request, intent, "")

        when {
            // Actionable, and the selection named something we can point at.
            intent.isAgentic && ctx.target.element?.text?.isNotBlank() == true -> {
                val label = ctx.target.element!!.text.trim()

                // Policy runs after the intent is known and before anything is
                // rendered. The classifier proposes; this decides.
                val ruling = ActionPolicy.evaluate(label)
                Log.d(TAG, "CIRCLE_AGENTIC intent=$intent target=\"$label\" policy=${ruling.level}")

                when {
                    !ruling.isPermitted -> {
                        val turn = newTurn()
                        renderIfCurrent(turn, OverlayCommand(PillState.ERROR, expanded = true,
                            instruction = ruling.userMessage, language = lastLanguage))
                    }

                    ruling.requiresConfirmation -> {
                        // Point at it, but say plainly what it is before the
                        // user commits. ScreenSaathi does not tap, so the
                        // highlight plus this sentence is the confirmation
                        // step — the user's own finger is the final gate.
                        val turn = newTurn()
                        renderIfCurrent(turn, OverlayCommand(PillState.GUIDING, expanded = true,
                            instruction = ruling.userMessage, language = lastLanguage))
                        highlightTarget(label)
                    }

                    else -> highlightTarget(label)
                }
            }

            // Actionable, but nothing nameable was selected — say so instead
            // of highlighting something arbitrary.
            intent.isAgentic -> {
                val turn = newTurn()
                val phrase = if (ctx.needsVision) {
                    Phrases.get(Phrases.Key.CIRCLE_NEEDS_VISION, lastLanguage)
                } else {
                    Phrases.get(Phrases.Key.CIRCLE_NOTHING_FOUND, lastLanguage)
                }
                renderIfCurrent(turn, OverlayCommand(PillState.ERROR, expanded = true,
                    instruction = phrase.text, language = phrase.language))
                speak(phrase, turn)
            }

            // Informational about a region we cannot see into.
            ctx.needsVision -> {
                val turn = newTurn()
                val phrase = Phrases.get(Phrases.Key.CIRCLE_NEEDS_VISION, lastLanguage)
                renderIfCurrent(turn, OverlayCommand(PillState.IDLE, expanded = true,
                    instruction = phrase.text, language = phrase.language))
                speak(phrase, turn)
            }

            // Unclear, but something is selected. Ask which way to go rather
            // than falling through to a screen search for the sentence itself
            // — "okay help me use it" is not a label on any screen, so
            // searching for it can only ever fail and look broken.
            intent == CircleIntent.UNKNOWN -> {
                val turn = newTurn()
                val label = ctx.target.element?.text?.trim()
                    ?: ctx.target.selectedText.take(40)
                val phrase = Phrases.get(Phrases.Key.CIRCLE_CLARIFY, lastLanguage)
                    .let { it.copy(text = String.format(it.text, label)) }
                Log.d(TAG, "CIRCLE_CLARIFY request=\"$request\"")
                renderIfCurrent(turn, OverlayCommand(PillState.IDLE, expanded = true,
                    instruction = phrase.text, language = phrase.language))
                speak(phrase, turn)
            }

            // Informational, and the tree can answer. The planner does the
            // real work here; it already receives the screen, and the
            // selection is added to its context by toPromptText().
            else -> {
                Log.d(TAG, "CIRCLE_INFORMATIONAL intent=$intent")
                return false
            }
        }
        return true
    }

    /** Drop the selection and its cached pixels. */
    fun clearCircleSelection() = circle.clear()

    /** The live selection rendered for the planner prompt, or empty. */
    fun circlePromptContext(): String {
        val allowed = currentScreenSafetyAssessment()?.disposition == ScreenDisposition.ALLOW
        return if (allowed) circle.context?.toPromptText().orEmpty() else ""
    }

    fun clearHighlight() {
        val turn = newTurn()
        currentHighlight = null
        lastHighlightQuery = null
        lastHighlightScreenSig = ""
        awaitingResettle = false
        renderIfCurrent(turn, OverlayCommand(PillState.IDLE, expanded = false, highlight = null))
    }

    fun highlightTarget(query: String) {
        val turn = newTurn()
        currentScreenSafetyAssessment()
            ?.takeUnless { it.disposition == ScreenDisposition.ALLOW }
            ?.let { assessment ->
                enterVoiceSafetyHandoff(turn, assessment)
                return
            }
        stopped = false
        lastUserRequest = query
        lastHighlightQuery = query
        currentHighlight = null
        lastHighlightScreenSig = ""

        renderIfCurrent(turn, OverlayCommand(PillState.THINKING, expanded = true,
            instruction = "Ищу «$query»…", language = lastLanguage))

        bg.post { runHighlightPollLoop(turn, query) }
    }

    /** [highlightTarget]'s resolve/render loop, factored out so a screen
     * transition (see [onWindowStateChanged]) can restart it on a fresh
     * generation without duplicating the loop body. */
    private fun runHighlightPollLoop(turn: Int, query: String) {
        val startedAt = SystemClock.uptimeMillis()
        var announced = false

        while (isCurrent(turn) && !stopped) {
            val snap = ScreenReaderService.instance?.snapshot()
            if (snap == null) {
                publishDebug(turn) { it.copy(note = "OVERLAY_CLEAR reason=no_accessibility_service") }
                break
            }
            val assessment = screenSafetyAssessment(snap)
            if (assessment.disposition != ScreenDisposition.ALLOW) {
                enterVoiceSafetyHandoff(newTurn(), assessment)
                return
            }
            val result = TargetResolver.resolve(query, snap)
            val hl = when (result) {
                is TargetResolver.Result.Found -> {
                    val b = result.candidate.bounds
                    HighlightBounds(b.left, b.top, b.right, b.bottom, "rect", true)
                }
                else -> null
            }

            if (hl != currentHighlight) {
                val reason = when (result) {
                    is TargetResolver.Result.Found ->
                        "OVERLAY_RENDER x=${hl?.left} y=${hl?.top} w=${(hl?.right ?: 0) - (hl?.left ?: 0)} " +
                            "h=${(hl?.bottom ?: 0) - (hl?.top ?: 0)} why=${result.candidate.why}"
                    is TargetResolver.Result.Ambiguous ->
                        "OVERLAY_CLEAR reason=ambiguous(${result.candidates.size})"
                    is TargetResolver.Result.NotFound ->
                        "OVERLAY_CLEAR reason=not_found"
                    is TargetResolver.Result.NotVisible -> "OVERLAY_CLEAR reason=not_visible"
                    is TargetResolver.Result.NotActionable -> "OVERLAY_CLEAR reason=not_actionable"
                }
                Log.d(TAG, reason)
                currentHighlight = hl
                // Keep the signature of the screen that owns the visible ring.
                // Updating it on every poll can make a destination screen look
                // current before the transition callback clears the old target.
                if (hl != null) lastHighlightScreenSig = snap.signature()
                val say = when (result) {
                    is TargetResolver.Result.Found -> "Вот нужный элемент."
                    is TargetResolver.Result.Ambiguous ->
                        "Нашлось несколько похожих элементов (${result.candidates.size}). Уточните, какой нужен."
                    is TargetResolver.Result.NotFound -> "На этом экране не видно элемента «$query»."
                    is TargetResolver.Result.NotVisible -> "Цель за пределами видимой области. Прокрутите экран вручную."
                    is TargetResolver.Result.NotActionable -> "Не могу выделить доступную кнопку или поле. Выполните действие вручную."
                }
                renderIfCurrent(turn, OverlayCommand(
                    if (hl != null) PillState.GUIDING else PillState.ERROR,
                    expanded = true, instruction = say,
                    language = lastLanguage, highlight = hl))
                if (!announced) { announced = true; speech.post { speak(Spoken(say, lastLanguage), turn) } }
            }

            publishDebug(turn) {
                it.copy(
                    wantResourceId = "q:" + query,
                    readerBound = true,
                    screenPackage = snap.packageName,
                    settled = snap.settled,
                    elementCount = snap.elements.size,
                    bounds = currentHighlight?.let { h -> "${h.left},${h.top},${h.right},${h.bottom}" }
                        ?: "NOT FOUND",
                )
            }

            if (SystemClock.uptimeMillis() - startedAt > TARGET_POLL_TIMEOUT_MS) break
            try { Thread.sleep(TARGET_POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
        }
    }

    /** Last query passed to [highlightTarget], so a mid-flow screen
     * transition knows what to keep looking for. Null once the highlight is
     * dismissed for any other reason — a transition must never resurrect a
     * highlight the user (or [onStopTapped]) already cancelled. */
    @Volatile private var lastHighlightQuery: String? = null

    /** The screen signature the current highlight was last confirmed against. */
    @Volatile private var lastHighlightScreenSig: String = ""

    /** Set the instant a transition is detected; consumed once the new
     * screen settles and a fresh resolve actually runs. Lets clearing and
     * resolving happen on two different events instead of one. */
    @Volatile private var awaitingResettle = false
    @Volatile private var invalidationTurn = 0

    /**
     * Fast invalidation signal for a screen transition, called from
     * [ScreenReaderService] on every TYPE_WINDOW_STATE_CHANGED.
     *
     * TYPE_VIEW_CLICKED cannot be this signal — measured on device, Uber
     * never emits it, on a plain button as much as a bare text row — so
     * nothing upstream of an actual screen change can be trusted to fire
     * reliably. TYPE_WINDOW_STATE_CHANGED is what the platform sends when the
     * window itself changes, which is the one thing the demo's "tap advances
     * the screen" case is guaranteed to produce.
     *
     * Two separate decisions, deliberately not one:
     *
     *  - CLEAR must not wait for the new screen to settle. Gating it on
     *    `settled` was the first cut of this — measured on device, it held
     *    the stale ring on screen for ~1.5s after the tap, because a
     *    mid-transition tree reports unsettled for several ticks. But an
     *    unsettled read is still real evidence of change (elems 73→64 shows
     *    up on the very first unsettled read, well before `settled` flips) —
     *    so the clear compares signatures on whatever snapshot is available,
     *    settled or not, and fires the moment they diverge.
     *  - RESOLVE still waits for `settled`, so the next target is read off a
     *    finished layout rather than a half-drawn one.
     *
     * Universal guidance clears synchronously because [bg] may be waiting on
     * the provider. The older polling path below still runs on [bg], the same
     * thread as the poll loop it invalidates.
     */
    fun onWindowStateChanged() {
        if (enforceCurrentScreenSafetyBoundary()) return
        scheduleUniversalScreenObservation()

        // Universal planning can be blocked on a network call on [bg]. Clear a
        // ring synchronously from the accessibility callback as soon as a
        // different readable tree is visible; the later debounced observation
        // still owns replanning.
        if (universalActive && currentHighlight != null) {
            val observed = ScreenReaderService.instance?.snapshot()?.signature().orEmpty()
            val expected = universalLoop.session.screenFingerprint.orEmpty()
            if (shouldClearGuidanceHighlight(expected, observed)) {
                currentHighlight = null
                clearHighlightInstant()
            }
        }

        bg.post {
            if (stopped) return@post
            val query = lastHighlightQuery ?: return@post
            if (currentHighlight == null && !awaitingResettle) return@post

            val snap = ScreenReaderService.instance?.snapshot() ?: return@post
            val sig = snap.signature()
            // Nothing readable yet on this event — not evidence either way,
            // wait for a later one rather than clear on noise.
            if (sig.isEmpty()) return@post

            if (currentHighlight != null && sig != lastHighlightScreenSig) {
                // Real transition. Cut immediately — no fly-home, no tether,
                // no trail — on THIS event, not the one where the new screen
                // finally settles.
                currentHighlight = null
                lastHighlightScreenSig = ""
                clearHighlightInstant()
                invalidationTurn = newTurn()
                awaitingResettle = true
            }

            if (awaitingResettle && snap.settled) {
                awaitingResettle = false
                runHighlightPollLoop(invalidationTurn, query)
            }
        }
    }

    /** Debounced bridge from accessibility tree changes into the pure reducer loop. */
    fun onScreenContentChanged() {
        if (enforceCurrentScreenSafetyBoundary()) return
        scheduleUniversalScreenObservation()
    }

    private fun scheduleUniversalScreenObservation() {
        if (!universalActive) return
        val observation = screenObservationId.incrementAndGet()
        bg.postDelayed({
            if (!universalActive || observation != screenObservationId.get()) return@postDelayed
            val snapshot = ScreenReaderService.instance?.snapshot() ?: return@postDelayed
            if (snapshot.settled) universalLoop.observeScreen(snapshot)
        }, UNIVERSAL_SCREEN_DEBOUNCE_MS)
    }

    /**
     * The user physically tapped something. That is the signal to advance —
     * not a timer, and not an action we performed on their behalf.
     */
    @Synchronized
    fun onUserClicked(clickedBounds: Rect? = null) {
        val now = SystemClock.uptimeMillis()
        // A single tap can produce several accessibility events.
        if (now - lastUserClickAt < USER_CLICK_DEBOUNCE_MS) return
        lastUserClickAt = now

        val highlight = currentHighlight
        if (
            universalActive &&
            universalLoop.session.phase == ScenarioPhase.GUIDING_STEP &&
            highlight != null
        ) {
            if (clickedBounds != null && !Rect.intersects(
                    clickedBounds,
                    Rect(highlight.left, highlight.top, highlight.right, highlight.bottom),
                )
            ) {
                Log.d(TAG, "UNIVERSAL_CLICK ignored: outside highlighted target $clickedBounds")
                return
            }
            val previousFingerprint = universalLoop.session.screenFingerprint.orEmpty()
            val turn = newTurn()
            universalTurn = turn
            currentHighlight = null
            player.stop()
            clearHighlightInstant()
            universalLoop.targetTapped()
            renderIfCurrent(
                turn,
                OverlayCommand(
                    PillState.THINKING,
                    expanded = false,
                    instruction = "Смотрю следующий экран…",
                    language = lastLanguage,
                    highlight = null,
                ),
            )
            bg.post { continueUniversalAfterTap(turn, previousFingerprint) }
            return
        }

        if (universalActive && universalLoop.session.phase == ScenarioPhase.WAITING_FOR_MODAL_DISMISS) {
            scheduleUniversalScreenObservation()
            return
        }

        val liveEngine = engine
        if (liveEngine != null && highlight != null) {
            if (clickedBounds != null && !Rect.intersects(
                    clickedBounds,
                    Rect(highlight.left, highlight.top, highlight.right, highlight.bottom),
                )
            ) {
                Log.d(TAG, "USER_CLICK ignored: outside highlighted target $clickedBounds")
                return
            }

            // Invalidate the long-running target poll immediately. Posting the
            // click behind that poll used to delay it by up to 30 seconds.
            if (liveEngine.task.id == "open_ended") {
                val goal = liveEngine.task.title
                val completed = liveEngine.currentStep.instruction
                val previousScreen = activePlanScreenSignature
                val turn = newTurn()
                currentHighlight = null
                guidanceStepNumber++
                player.stop()
                clearHighlightInstant()
                renderIfCurrent(
                    turn,
                    OverlayCommand(
                        PillState.THINKING,
                        expanded = false,
                        instruction = "Смотрю следующий экран…",
                        language = lastLanguage,
                    ),
                )
                bg.post {
                    continueAfterTargetTap(turn, goal, previousScreen, completed)
                }
                return
            }

            onNextTapped()
            return
        }

        bg.post {
            val turn = turnId.get()
            if (stopped || !isCurrent(turn)) return@post
            // The ad-hoc highlight loop (highlightTarget) has no step engine
            // and so was invisible to this click handler entirely — a tap
            // never told it anything. It kept re-matching the same query
            // text against whatever screen came next, and if that label
            // still existed there (e.g. "Where to?" surviving as a
            // placeholder on the next screen too), the cursor flew to the
            // new position and the in-flight tether read as a leftover line
            // from the screen the user had already left.
            //
            // Tied to the real TYPE_VIEW_CLICKED event, not a delay: the
            // instant a tap is accepted, drop the old geometry and stop
            // chasing that query. bumping the turn is what actually halts
            // the poll loop — its own isCurrent(turn) check exits on the
            // next iteration.
            if (currentHighlight != null) {
                currentHighlight = null
                val t = newTurn()
                // instruction = null: OverlayCommand leaves existing pill text
                // alone (render() only replaces it when non-null) — this call
                // exists solely to drop the highlight, not to say anything new.
                render(OverlayCommand(PillState.GUIDING, expanded = true,
                    instruction = null, language = lastLanguage, highlight = null))
                publishDebug(t) { it.copy(note = "OVERLAY_CLEAR reason=user_tapped") }
            }
        }
    }

    /** Wait for the destination UI to settle, then continue the same goal. */
    private fun continueAfterTargetTap(
        turn: Int,
        goal: String,
        previousScreenSignature: String,
        completedStep: String,
    ) {
        val startedAt = SystemClock.uptimeMillis()
        val deadline = startedAt + NEXT_SCREEN_WAIT_MS
        var snapshot: ScreenSnapshot? = null

        while (isCurrent(turn) && !stopped && SystemClock.uptimeMillis() < deadline) {
            snapshot = ScreenReaderService.instance?.snapshot()
            val signature = snapshot?.signature().orEmpty()
            val changed = signature.isNotEmpty() && signature != previousScreenSignature
            val sameScreenGraceElapsed =
                SystemClock.uptimeMillis() - startedAt >= SAME_SCREEN_REPLAN_DELAY_MS
            if (snapshot?.settled == true && (changed || sameScreenGraceElapsed)) break
            try {
                Thread.sleep(NEXT_SCREEN_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }

        if (!isCurrent(turn) || stopped) return
        processOpenEndedNext(turn, goal, completedStep)
    }

    /** Waits for a settled post-tap tree; ordinary duplicate events stay reducer-idempotent. */
    private fun continueUniversalAfterTap(turn: Int, previousFingerprint: String) {
        val startedAt = SystemClock.uptimeMillis()
        val deadline = startedAt + NEXT_SCREEN_WAIT_MS
        var snapshot: ScreenSnapshot? = null
        while (isCurrent(turn) && universalActive && SystemClock.uptimeMillis() < deadline) {
            snapshot = ScreenReaderService.instance?.snapshot()
            val fingerprint = snapshot?.signature().orEmpty()
            val changed = fingerprint.isNotBlank() && fingerprint != previousFingerprint
            val sameScreenGraceElapsed =
                SystemClock.uptimeMillis() - startedAt >= SAME_SCREEN_REPLAN_DELAY_MS
            if (snapshot?.settled == true && (changed || sameScreenGraceElapsed)) break
            try {
                Thread.sleep(NEXT_SCREEN_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
        if (!isCurrent(turn) || !universalActive) return
        val observed = snapshot ?: ScreenSnapshot.EMPTY
        if (!observed.settled) {
            universalLoop.physicalTapTimedOut()
            return
        }
        if (observed.signature().isNotBlank() && observed.signature() != previousFingerprint) {
            universalLoop.observeScreen(observed)
        } else {
            universalLoop.physicalTapSettled(observed)
        }
    }

    @Volatile private var lastUserClickAt: Long = 0L

    /** Runs on [speech]. Never on the thread that resolves the highlight. */
    private fun speak(
        spoken: Spoken,
        turn: Int,
        latency: ScenarioLatencyTrace? = null,
        stillCurrent: () -> Boolean = { true },
        withCurrent: (() -> Unit) -> Unit = { it() },
    ) {
        if (!isCurrent(turn) || stopped || !stillCurrent()) return
        currentScreenSafetyAssessment()
            ?.takeUnless { it.disposition == ScreenDisposition.ALLOW }
            ?.let { assessment ->
                enterVoiceSafetyHandoff(newTurn(), assessment)
                return
            }
        if (!Sarvam.isConfigured()) return
        if (!isCurrent(turn) || stopped || !stillCurrent()) return
        latency?.ttsStarted()
        val bytes = tts.synthesize(spoken)
        if (bytes == null) {
            if (universalActive && isCurrent(turn) && stillCurrent()) universalLoop.ttsFailed()
            return
        }
        if (!isCurrent(turn) || stopped || !stillCurrent()) return
        // Carry currentHighlight through both renders — the ring must survive
        // the speaking state, not blink out while the instruction is read.
        player.play(
            bytes,
            onStart = {
                if (!stillCurrent()) return@play
                renderIfCurrent(turn, OverlayCommand(PillState.SPEAKING, expanded = true,
                    instruction = spoken.text, language = spoken.language,
                    highlight = currentHighlight, choices = currentChoices,
                    decisionButtons = currentDecisionButtons), stillCurrent)
            },
            onDone = {
                if (!stillCurrent()) return@play
                renderIfCurrent(turn, OverlayCommand(PillState.GUIDING, expanded = true,
                    instruction = spoken.text, language = spoken.language,
                    highlight = currentHighlight, choices = currentChoices,
                    decisionButtons = currentDecisionButtons), stillCurrent)
            },
            withCurrent = { action ->
                withCurrent {
                    synchronized(turnLock) {
                        if (isCurrent(turn) && !stopped) action()
                    }
                }
            },
        )
    }

    /**
     * Poll the live tree until the step's target shows up.
     *
     * Matches a resource id when the step has one (our own demo screen) and
     * visible text when it does not (Uber, Ola, Rapido, whose view ids are
     * obfuscated). When the step names a package, wait for that package to be
     * in front first — a ride app takes a second or two to draw, and without
     * this the ring lands on whatever was still on screen.
     */
    private fun resolveBounds(step: TaskStep): Rect? {
        val attempts = if (step.expectPackage.isNotEmpty()) CROSS_APP_ATTEMPTS else RESOLVE_ATTEMPTS
        repeat(attempts) {
            val snap = ScreenReaderService.instance?.snapshot()
            if (snap != null && screenSafetyAssessment(snap).disposition != ScreenDisposition.ALLOW) {
                return null
            }
            if (snap != null && step.expectPackage.let { it.isEmpty() || snap.packageName == it }) {
                val bounds = when {
                    step.resourceId.isNotEmpty() -> snap.boundsForResourceId(step.resourceId)
                    else -> null
                } ?: snap.boundsForText(step.textAny)
                if (bounds != null) return bounds
            }
            try { Thread.sleep(RESOLVE_INTERVAL_MS) } catch (_: InterruptedException) { return null }
        }
        return null
    }

    // --- App choice -----------------------------------------------------------

    /** The apps currently offered, parallel to OverlayCommand.choices. */
    @Volatile private var offeredApps: List<RideApps.Installed> = emptyList()

    private fun presentAppChoice(instruction: Spoken, turn: Int, speak: Boolean) {
        val apps = RideApps.installed(context)
        offeredApps = apps
        currentHighlight = null
        currentChoices = apps.map { it.label }

        if (apps.isEmpty()) {
            val none = Phrases.get(Phrases.Key.NO_RIDE_APPS, lastLanguage)
            renderIfCurrent(turn, OverlayCommand(PillState.ERROR, expanded = true,
                instruction = none.text, language = none.language))
            if (speak) speech.post { speak(none, turn) }
            return
        }

        publishDebug(turn) { it.copy(note = "offering ${apps.joinToString(",") { a -> a.label }}") }
        renderIfCurrent(turn, OverlayCommand(PillState.GUIDING, expanded = true,
            instruction = instruction.text, language = instruction.language,
            highlight = null, choices = apps.map { it.label }))
        if (speak) speech.post { speak(instruction, turn) }
    }

    /**
     * The user picked a ride app. Launch it, then guide inside it — the next
     * step is pinned to that package so the ring waits for the app to draw.
     */
    fun onChoiceTapped(index: Int) {
        val scenarioChoice = pendingScenarioChoices.getOrNull(index)
        if (scenarioChoice != null) {
            stopped = false
            player.stop()
            clearScenarioDecisions()
            currentChoices = emptyList()
            val turn = universalTurn
            bg.post {
                if (universalActive && isCurrent(turn)) {
                    handleScenarioChoice(scenarioChoice)
                }
            }
            return
        }

        val apps = offeredApps
        val app = apps.getOrNull(index) ?: return
        val turn = newTurn()
        stopped = false

        // Tapping an app in a list the assistant presented IS authorisation.
        // Recorded before the launch so the guard can see it.
        userSelectedPackage = app.packageName

        val opening = Phrases.get(Phrases.Key.OPENING_APP, lastLanguage)
        val spoken = Spoken(String.format(opening.text, app.label), opening.language)
        render(OverlayCommand(PillState.THINKING, expanded = true,
            instruction = spoken.text, language = spoken.language))

        // Route the explicit choice through the guard too, rather than around
        // it. The selection authorises the launch (userSelectedPackage above),
        // but device evidence — installed, enabled, launchable — is still
        // checked, and a failure is still reported honestly.
        val choiceVerdict = SafetyGuard.validateLaunchAuthorization(
            userRequest = lastUserRequest,
            resolution = DeviceContextProvider.snapshot(context).resolveApp(app.label),
            userSelectedPackage = app.packageName,
        )
        if (choiceVerdict is SafetyGuard.Verdict.Block) {
            publishDebug(turn) { it.copy(note = "choice launch blocked: ${choiceVerdict.reason}") }
        }

        if (choiceVerdict is SafetyGuard.Verdict.Block || !AppLauncher.launchPackage(context, app.packageName)) {
            val failed = Phrases.get(Phrases.Key.APP_WONT_OPEN, lastLanguage)
            render(OverlayCommand(PillState.ERROR, expanded = true,
                instruction = failed.text, language = failed.language,
                choices = apps.map { it.label }))
            speech.post { speak(failed, turn) }
            return
        }

        speech.post { speak(spoken, turn) }

        val e = engine ?: return
        if (!e.advance()) return
        // Pin the remaining steps to the app we just opened, so guidance waits
        // for it instead of pointing at our own screen mid-launch.
        chosenPackage = app.packageName
        bg.post {
            if (!isCurrent(turn)) return@post
            presentCurrentStep(e, turn, speak = true)
        }
    }

    @Volatile private var chosenPackage: String = ""

    /** The user's most recent actual utterance — the authorisation evidence. */
    @Volatile private var lastUserRequest: String = ""

    /** A package the user explicitly picked from a presented list. */
    @Volatile private var userSelectedPackage: String = ""

    private enum class AllowedAction { CALL_CONTACT }

    private data class PendingAction(
        val type: AllowedAction,
        val targetId: String,
        val label: String,
        val phone: String = "",
        val expiresAtMs: Long,
    )

    /**
     * Recognises only an explicit current request for the optional local call
     * contact. No model output is consulted and the phone number is never
     * added to a prompt.
     */
    private fun maybeProposeAllowedAction(request: String, turn: Int): Boolean {
        val lower = request.trim().lowercase()
        if (lower.startsWith("как ") || lower.startsWith("где ") ||
            lower.startsWith("почему ") || lower.startsWith("можно ли ")
        ) return false

        val settings = OporaSettings.load(context)
        val wantsCall = listOf("позвони", "позвонить", "набери", "call ")
            .any(lower::contains)
        if (wantsCall && mentionsConfiguredTarget(lower, settings.contactName, "контакт")) {
            val phone = OporaSettings.normalizedPhone(settings.contactPhone)
            if (!settings.hasContact || phone == null) {
                showLocalMessage(turn, "Сначала сохраните допустимый контакт в настройках «Опоры».", PillState.ERROR)
                return true
            }
            val suffix = phone.takeLast(4)
            proposeAction(
                PendingAction(
                    type = AllowedAction.CALL_CONTACT,
                    targetId = OporaSettings.CONTACT_TARGET_ID,
                    label = settings.contactName,
                    phone = phone,
                    expiresAtMs = SystemClock.elapsedRealtime() + CONFIRM_TTL_MS,
                ),
                "Позвонить контакту ${settings.contactName} (номер ••••$suffix)?",
                turn,
            )
            return true
        }
        return false
    }

    private fun mentionsConfiguredTarget(request: String, configuredName: String, generic: String): Boolean {
        if (request.contains(generic) || request.contains("избранн")) return true
        val words = configuredName.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }
        return words.any { name ->
            val stem = name.take(3)
            request.split(Regex("[^а-яa-z0-9]+"))
                .any { spoken -> spoken.startsWith(stem) }
        }
    }

    private fun proposeAction(action: PendingAction, message: String, turn: Int) {
        pendingAction = action
        currentDecisionButtons = DecisionButtons("Позвонить", "Отмена")
        currentHighlight = null
        currentChoices = emptyList()
        renderIfCurrent(turn, OverlayCommand(PillState.GUIDING, expanded = true,
            instruction = "$message Подтверждение действует 30 секунд.",
            language = Language.APP_DEFAULT, decisionButtons = currentDecisionButtons))
        speech.post { speak(Spoken(message, Language.APP_DEFAULT), turn) }
        bg.postDelayed({
            if (pendingAction === action) {
                pendingAction = null
                currentDecisionButtons = null
                showLocalMessage(turn, "Подтверждение истекло. Повторите просьбу.", PillState.IDLE)
            }
        }, CONFIRM_TTL_MS)
    }

    private fun callConfiguredContact(action: PendingAction, turn: Int) {
        val settings = OporaSettings.load(context)
        val currentPhone = OporaSettings.normalizedPhone(settings.contactPhone)
        if (action.targetId != OporaSettings.CONTACT_TARGET_ID || currentPhone != action.phone) {
            showLocalMessage(turn, "Настройки контакта изменились. Повторите просьбу.", PillState.ERROR)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showLocalMessage(turn, "Разрешите звонки на экране настройки «Опоры».", PillState.ERROR)
            return
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(action.phone)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onSuccess { showLocalMessage(turn, "Начинаю звонок: ${action.label}.", PillState.IDLE) }
            .onFailure { showLocalMessage(turn, "Не удалось начать звонок.", PillState.ERROR) }
    }

    private fun showLocalMessage(turn: Int, message: String, state: PillState) {
        renderIfCurrent(turn, OverlayCommand(state, expanded = true,
            instruction = message, language = Language.APP_DEFAULT))
        speech.post { speak(Spoken(message, Language.APP_DEFAULT), turn) }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One capture file per turn. A shared filename let a new recording truncate
     * the very file the previous turn's STT upload was still streaming.
     */
    private fun inputFileFor(turn: Int) = File(context.cacheDir, "$CAPTURE_PREFIX$turn.wav")

    /** Abandoned turns leave their capture behind; don't grow the cache all demo. */
    private fun purgeStaleCaptures(keep: Int) {
        val keepName = inputFileFor(keep).name
        context.cacheDir.listFiles { f ->
            f.name.startsWith(CAPTURE_PREFIX) && f.name != keepName
        }?.forEach { it.delete() }
    }

    fun dispose() {
        turnId.incrementAndGet() // invalidate anything still in flight
        networkMonitor.stop()
        if (instance === this) instance = null
        if (universalActive) {
            universalActive = false
            universalLoop.stop()
        }
        player.stop()
        recorder.stop()
        worker.quitSafely()
        captureWorker.quitSafely()
        speechWorker.quitSafely()
    }

    companion object {
        @Volatile var instance: SessionController? = null
        /** Re-resolve cadence for the live target. */
        private const val TARGET_POLL_INTERVAL_MS = 300L
        /** Stop hunting for a target that never appears. */
        private const val TARGET_POLL_TIMEOUT_MS = 30_000L
        /** One physical tap can emit several accessibility events. */
        private const val USER_CLICK_DEBOUNCE_MS = 800L
        private const val TAG = "SessionController"
        private const val RESOLVE_ATTEMPTS = 12
        /** A cold ride app can take a couple of seconds to draw its first screen. */
        private const val CROSS_APP_ATTEMPTS = 40
        private const val RESOLVE_INTERVAL_MS = 120L
        private const val CONFIDENCE_FLOOR = 0.5
        private const val CAPTURE_PREFIX = "saathi_input_"

        /** Below this the clip is a mis-tap, not speech. */
        private val QUOTED = Regex("[\"'“”‘’]([^\"'“”‘’]+)[\"'“”‘’]")

        /** Let a screen finish drawing before treating it as a real change. */
        private const val REPLAN_SETTLE_MS = 1_200L

        /** Give a tapped app time to draw its destination before re-planning. */
        private const val NEXT_SCREEN_WAIT_MS = 2_500L
        /** A focus/keyboard change may keep the same signature; still continue. */
        private const val SAME_SCREEN_REPLAN_DELAY_MS = 1_000L
        private const val NEXT_SCREEN_POLL_INTERVAL_MS = 120L
        private const val UNIVERSAL_SCREEN_DEBOUNCE_MS = 350L

        private const val MIN_SPEECH_MS = 400L

        /** Longest single-tap capture before it is closed automatically. */
        private const val MAX_UTTERANCE_MS = 7_000L
        private const val CONFIRM_TTL_MS = 30_000L
    }
}
