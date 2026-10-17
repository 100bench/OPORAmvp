# Architecture

```
Voice / Text input + raw Accessibility snapshot
      │
      ▼
Local Screen Safety  (bank/auth/payment/final-action boundary)
      │
      ▼
Sanitization  (semantic planner projection, no geometry/current field values)
      │
      ▼
Intent Understanding  (gpt-transcribe for allowed voice → gpt-6-luna planner)
      │
      ▼
Session Controller  (turns, capture generations, screen-change invalidation)
      │
      ▼
Target Resolution + Action Policy  (same raw tree, ranked, app-agnostic)
      │
      ▼
Android Interaction  (user taps; we never tap for them)
      │
      ▼
Visual Guidance  (HighlightView, VoiceWaveformView, OverlayService)
      │
      ▼
Screen Re-perception  (loop back to Local Screen Safety)
```

## Subsystems

### Perception — `ScreenReaderService.kt`, `screen/ScreenSnapshot.kt`
An `AccessibilityService` that walks the live accessibility tree of the
foreground app into a flat, indexed `ScreenSnapshot`: resource ID, text,
content description, hint text, class, bounds, clickable/editable flags. It is
strictly read-only — no gestures, no synthetic taps. `resolveRoot()` falls
back through the window list when `rootInActiveWindow` is null (common
mid-transition). See `docs/DECISIONS.md` for why several of these choices
exist.

### Target resolution — `screen/TargetResolver.kt`
Turns a natural-language phrase into one element, or an honest refusal.
Scoring is ranked (exact text → normalized → word-boundary → id-contains),
never substring `contains`, and a near-tie is reported as genuinely ambiguous
rather than guessed. Zero app-specific branches — the same code resolves
`Where to?` in Uber and `Electricity Bill` in PhonePe.

### Orchestration — `session/SessionController.kt`
Owns the voice loop and the guided-task state machine. Every user action opens
a numbered *turn*; background work checks its turn is still current before
rendering, so a slow network response can never paint over what the user is
now looking at. `onWindowStateChanged()` invalidates a highlight the instant
the screen's content signature diverges — not gated on the new screen
settling, because gating both was measured to hold a stale ring on screen for
~1.5s after a tap.

### Safety — `session/ScreenSafetyGate.kt`, `session/SafetyGuard.kt`
The raw UI tree is classified locally before sanitization or any planner call.
Authentication, banking, payment-data and final-confirmation surfaces clear
the highlight and pause or hand control back to the user. Plans are also
rejected unless grounded in the exact readable tree used for that request.

### Visual guidance — `overlay/HighlightView.kt`, `overlay/VoiceWaveformView.kt`, `OverlayService.kt`
`HighlightView` is a `NOT_TOUCHABLE` full-screen layer; the cursor flies to a
target and the ring blooms once it arrives, or clears instantly (no
animation) when the screen has genuinely changed underneath it —
`clearInstant()` exists specifically so a transition never animates toward a
target that no longer exists. `VoiceWaveformView` is the single voice-state
surface (listening/thinking/speaking) driven by real microphone RMS computed
inside `WavRecorder`'s existing capture loop — no second `AudioRecord`.
`OverlayService` owns a fixed-width (`320dp`) `TOP|START`-positioned window;
`overlay/AssistantPlacement.kt` holds the (Android-free, unit-testable) clamp/
snap/keyboard-avoidance math, and `overlay/AssistantUiState.kt` is the single
state-machine enum that replaced a set of independent booleans which used to
be able to contradict each other.

### Voice and planning
The voice path uses OpenAI `gpt-transcribe`, the guidance path uses a strict
structured `gpt-6-luna` response, and spoken output uses
`gpt-4o-mini-tts`. A localhost development gateway owns the API key; no secret
is present in the APK. The user-facing contract is Russian-only. If a network
call fails, the session pauses with bounded local recovery rather than issuing
an ungrounded instruction.

### Device inventory — `device/DeviceContext.kt`, `device/DeviceContextProvider.kt`
Real `PackageManager` discovery (`QUERY_ALL_PACKAGES`), not a curated app
list. Three-state per app: known-present, known-absent, or
unknown-due-to-package-visibility — the assistant is honest about which one
it's in rather than assuming.

### Launcher — `launcher/LauncherActivity.kt`, `launcher/AppGridAdapter.kt`
Optional `CATEGORY_HOME` launcher, opt-in only if the user sets ScreenSaathi
as their default home app. Lists real installed apps from
`DeviceContextProvider`, not a fixture list.

## What is explicitly NOT here

- No MediaProjection/screenshot fallback — perception is accessibility-only.
  Where an app exposes too little to accessibility, the resolver says so
  rather than guessing from a screenshot.
- No full transcript log or UI-tree persistence. Resume stores only a minimal
  encrypted capsule (active goal, package, safe step text and timestamps). A
  voice goal is necessarily the recognized text of that current request. On
  resume, live safety and target resolution run again before anything is shown.
