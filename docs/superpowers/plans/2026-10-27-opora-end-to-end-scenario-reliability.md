# Opora End-to-End Scenario Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the agreed Yandex Go taxi, Auto.ru car-sale, current-screen question, popup, and authentication routes complete reliably on the connected Samsung using only Accessibility UI trees.

**Architecture:** Keep one immutable live `ScreenSnapshot` as the source of truth. A shared viewport/eligibility layer filters system and off-screen nodes before sanitization or resolution; the resolver returns typed outcomes after collapsing labels to actionable ancestors; the local safety gate blocks only evidence-backed secret/financial/final actions; an unchanged tree receives at most one planner call. Real, sanitized Samsung trees become deterministic replay fixtures, while a small live-model gate and physical smoke run cover the two remaining external boundaries.

**Tech Stack:** Kotlin, Android AccessibilityService, JUnit 4, `org.json`, Gradle, adb, existing OpenAI development proxy.

**Spec:** `docs/superpowers/specs/2026-10-27-opora-end-to-end-scenario-reliability-design.md`

## Global Constraints

- Accessibility UI tree only: no screenshots, OCR, vision fallback, automatic taps, typing, scrolling, ordering, or publishing.
- Do not hardcode Yandex Go, Auto.ru, package-specific coordinates, or a fixed screen sequence in production code.
- Never persist or print raw trees, editable values, addresses, phone numbers, VINs, state numbers, OTPs, passwords, or audio.
- Capture fixtures only after redaction in memory. The user enters all personal data on the phone; tests use synthetic placeholders.
- Keep password, OTP, banking, payment, and final-action checks local and before planner serialization.
- Do not change the AI provider, VPS topology, RuStore setup, or product-wide visual design in this work.
- Use TDD for each behavior change. Each task must be green and committed before the next task begins.

## Review Focus

- A matching node below the active application viewport must never be highlighted.
- Labels and their clickable ancestors must resolve as one physical target, not two ambiguous targets.
- `pin`, `marker`, and address-map chrome are weak evidence; visible password/OTP input semantics remain blocking.
- A local grounding miss must not trigger another paid planner call against the same fingerprint.
- User-visible recovery text must match `NotVisible`, `Ambiguous`, `NotActionable`, or `NotFound`; “scroll” is not the universal fallback.
- Useful text/highlight is rendered before asynchronous TTS work starts.
- Test evidence must distinguish synthetic fixtures, sanitized real fixtures, live planner results, and physical Samsung results.

---

## Task 1: Establish the baseline and a privacy-safe fixture capture path

**Files:**

- Create: `src/ScreenSaathi/app/src/main/java/com/screensaathi/screen/UiTreeFixture.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/screen/UiTreeFixtureTest.kt`
- Create: `src/ScreenSaathi/app/src/debug/java/com/screensaathi/debug/FixtureExportReceiver.kt`
- Create: `src/ScreenSaathi/app/src/debug/AndroidManifest.xml`
- Create: `src/ScreenSaathi/scripts/capture_ui_fixture.sh`
- Modify: `src/ScreenSaathi/docs/DEVELOPMENT.md`

**Interfaces:**

```kotlin
data class UiTreeFixture(
    val schemaVersion: Int,
    val captureKind: String,
    val packageName: String,
    val settled: Boolean,
    val activeDialog: Boolean,
    val viewport: ScreenViewport?,
    val elements: List<UiTreeFixtureElement>,
)

object UiTreeFixtureCodec {
    fun encodeSanitized(snapshot: ScreenSnapshot, approvedStaticLabels: Set<String>): String
    fun decode(json: String): ScreenSnapshot
}
```

- [ ] **Step 1: Record the current baseline.** From `src/ScreenSaathi`, run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`. Record failures, if any, in the plan under a dated execution note; do not change unrelated behavior to make an old failure disappear.
- [ ] **Step 2: Write failing codec privacy tests.** Prove that editable `text`, `hintText`, and `contentDescription` values never appear in encoded JSON; phone/email/long-number/OTP patterns are replaced; password nodes are omitted; bounds, roles, flags, resource IDs, and `clickableAncestorIndex` survive round-trip; only caller-approved non-editable labels survive verbatim.
- [ ] **Step 3: Run the focused test and confirm RED.** Run `./gradlew testDebugUnitTest --tests 'com.screensaathi.screen.UiTreeFixtureTest'`; the missing codec must fail compilation/test.
- [ ] **Step 4: Implement the smallest codec.** Reuse `ScreenSanitizer.sanitizeValue`; serialize only the whitelisted structural fields. Sanitize in memory before opening the output file. `decode` is test-only/replay-safe and accepts only the sanitized schema.
- [ ] **Step 5: Add a debug-only exporter.** The receiver obtains `ScreenReaderService.instance?.snapshot()`, accepts a safe fixture name plus explicit approved static labels, calls `encodeSanitized`, writes only the sanitized JSON under the debug app's private `files/fixtures/`, and logs only success/failure, element count, and private relative path. It must not be present in release manifests.
- [ ] **Step 6: Add the adb helper.** `capture_ui_fixture.sh <name> <destination> [approved-label ...]` validates the fixture name, broadcasts to the debug receiver, copies the already-sanitized file with `adb exec-out run-as com.screensaathi`, and refuses destinations outside `app/src/test/resources/ui_trees/real/` unless `--dry-run` is supplied.
- [ ] **Step 7: Verify privacy and build boundaries.** Run the focused test, `./gradlew assembleDebug`, and `./gradlew assembleRelease`; inspect the merged release manifest to confirm the exporter is absent.
- [ ] **Step 8: Document capture rules and commit.** Add the exact command, redaction guarantees, and forbidden data list to `docs/DEVELOPMENT.md`. Commit as `test: add sanitized UI tree fixture capture`.

## Task 2: Make the active application viewport part of every snapshot

**Files:**

- Create: `src/ScreenSaathi/app/src/main/java/com/screensaathi/screen/ScreenViewport.kt`
- Create: `src/ScreenSaathi/app/src/main/java/com/screensaathi/screen/ScreenElementEligibility.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/screen/ScreenElementEligibilityTest.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/screen/ScreenSnapshot.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/ScreenReaderService.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/screen/ScreenSanitizer.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/screen/ScreenSanitizerTest.kt`
- Modify: fixture parsing in `src/ScreenSaathi/app/src/test/java/com/screensaathi/evals/UniversalGuidanceAcceptanceTest.kt`

**Interfaces:**

```kotlin
data class ScreenViewport(val left: Int, val top: Int, val right: Int, val bottom: Int)

enum class ScreenElementIneligibility {
    HIDDEN, DISABLED, PASSWORD, EMPTY_BOUNDS, OUTSIDE_VIEWPORT, MOSTLY_CLIPPED
}

sealed interface ScreenElementEligibility {
    data object Eligible : ScreenElementEligibility
    data class Ineligible(val reason: ScreenElementIneligibility) : ScreenElementEligibility
}

fun ScreenElement.eligibility(viewport: ScreenViewport): ScreenElementEligibility
```

- [ ] **Step 1: Write failing geometry tests.** Cover hidden, disabled, password, zero-area, fully below viewport, center outside viewport, exactly 50% visible, just under 50%, huge coordinates, and multiplication overflow. Area arithmetic must use `Long`.
- [ ] **Step 2: Confirm RED.** Run `./gradlew testDebugUnitTest --tests 'com.screensaathi.screen.ScreenElementEligibilityTest' --tests 'com.screensaathi.screen.ScreenSanitizerTest'`.
- [ ] **Step 3: Implement viewport and eligibility.** An element is eligible only when visible, enabled, non-password, positive-area, intersects the viewport, has its center inside, and has at least 50% of its area inside. Provide an explicit legacy/unbounded viewport only for old synthetic constructors; never infer a live viewport from all node bounds.
- [ ] **Step 4: Capture root and window bounds together.** Change `ScreenReaderService.resolveRoot()` to return the chosen root with its `AccessibilityWindowInfo` bounds. `snapshot()` stores those exact bounds in `ScreenSnapshot`; all emitted elements already belong to that chosen root/window.
- [ ] **Step 5: Apply the same boundary before serialization.** `ScreenSanitizer` includes only eligible elements. Update `ScreenSnapshot.EMPTY`, copy sites, codec, and fixture loader to preserve/default the viewport explicitly.
- [ ] **Step 6: Verify and commit.** Run all screen tests and `./gradlew testDebugUnitTest`; commit as `fix: constrain screen elements to active viewport`.

## Task 3: Resolve labels to actionable controls and expose typed failures

**Files:**

- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/screen/TargetResolver.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/screen/TargetResolverTest.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioModels.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioReducer.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/ScenarioReducerTest.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/UniversalGuidanceLoop.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/UniversalGuidanceLoopTest.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/SessionController.kt`

**Interfaces:**

```kotlin
sealed interface TargetResolver.Result {
    data class Found(val candidate: Candidate) : Result
    data class NotVisible(val query: String) : Result
    data class Ambiguous(val candidates: List<Candidate>) : Result
    data class NotActionable(val query: String) : Result
    data class NotFound(val query: String) : Result
}

enum class ScenarioTargetIssue { NOT_VISIBLE, AMBIGUOUS, NOT_ACTIONABLE, NOT_FOUND }

data class TargetUnavailable(val issue: ScenarioTargetIssue) : ScenarioEvent
data class ShowTargetIssue(val issue: ScenarioTargetIssue) : ScenarioEffect
```

- [ ] **Step 1: Add failing resolver tests.** Reproduce the observed bottom-system-node case with matching bounds below the viewport; a VIN/static label whose clickable ancestor is the input; two labels sharing one clickable ancestor; two genuinely distinct buttons with equal labels; a clipped target; a disabled exact match; and a static label with no reliable actionable control.
- [ ] **Step 2: Confirm RED.** Run `./gradlew testDebugUnitTest --tests 'com.screensaathi.screen.TargetResolverTest'`.
- [ ] **Step 3: Normalize before ranking.** Partition semantic matches into eligible and ineligible. Map each label to its valid `clickableAncestorIndex` when present; deduplicate by final element index (and overlapping equivalent bounds as a fallback); then score and check ambiguity. Return `NotVisible` when matching nodes exist but all fail viewport visibility, `NotActionable` for ungrounded labels, `Ambiguous` only for distinct usable controls, and `NotFound` when no semantic match exists.
- [ ] **Step 4: Add failing reducer/loop tests.** Verify that only `NOT_VISIBLE` enters manual-scroll recovery; `AMBIGUOUS` asks the user to clarify; `NOT_ACTIONABLE` asks the user to act manually without a false ring; `NOT_FOUND` offers rephrase/retry; none renders a target.
- [ ] **Step 5: Propagate typed outcomes.** Make `UniversalGuidanceLoop.resolve` return `TargetResolver.Result` (or an equivalent typed local result), dispatch `TargetUnavailable`, and remove nullable-target fallback text from `SessionController`. Give every issue a short, distinct Russian message.
- [ ] **Step 6: Verify and commit.** Run resolver, reducer, loop, and acceptance tests; commit as `fix: ground guidance in actionable visible targets`.

## Task 4: Replace token-based false authentication with evidence-based safety

**Files:**

- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScreenSafetyGate.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/ScreenSafetyGateTest.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/BankSafetyIntegrationTest.kt`
- Create after capture: `src/ScreenSaathi/app/src/test/resources/ui_trees/real/yandex_go/address_entry.json`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/evals/UniversalGuidanceAcceptanceTest.kt`

**Interfaces:**

```kotlin
enum class AuthenticationEvidence {
    PASSWORD_NODE, PASSWORD_INPUT_TYPE, OTP_OR_SMS_TEXT, PIN_INPUT_CONTEXT
}
```

- [ ] **Step 1: Capture the current Yandex address tree safely.** On the connected Samsung, open Taxi → address entry with no personal address typed. Export the sanitized tree; review the JSON for forbidden values before staging it.
- [ ] **Step 2: Write failing safety tests.** Assert `ALLOW` for address input plus `pin_slot`, `source_pin`, map marker, destination marker, Button/TextView address controls, and weak `pin` resource IDs. Assert `PAUSE_AUTH` for visible password semantics, password `inputType`, OTP/SMS text, and an editable PIN field/keypad context. Preserve bank/payment/final-action tests.
- [ ] **Step 3: Confirm RED.** Run `./gradlew testDebugUnitTest --tests 'com.screensaathi.session.ScreenSafetyGateTest' --tests 'com.screensaathi.session.BankSafetyIntegrationTest'`.
- [ ] **Step 4: Implement strong/weak evidence.** A plain `pin` token or resource ID is never sufficient alone. Password flags/input types and explicit visible OTP/SMS/password language remain strong. Treat PIN as authentication only with input/keypad/secret-code context. Run the gate only over viewport-eligible nodes.
- [ ] **Step 5: Add safe technical diagnostics.** When a screen is blocked, log only `ScreenRiskCategory`, `AuthenticationEvidence`, package name, and element structural role/resource ID; never its text/value.
- [ ] **Step 6: Verify and commit.** Run all safety and acceptance tests, then a text request on the live Yandex address screen and confirm no auth pause. Commit as `fix: require strong authentication evidence`.

## Task 5: Guarantee one planner call per unchanged tree

**Files:**

- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/UniversalGuidanceLoop.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioModels.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioReducer.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/UniversalGuidanceLoopTest.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/ScenarioReducerTest.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/evals/UniversalGuidanceAcceptanceTest.kt`

- [ ] **Step 1: Write the regression tests first.** A valid planner response whose target is locally `NotVisible`, `Ambiguous`, `NotActionable`, or `NotFound` must make exactly one planner call for that fingerprint. An actually malformed provider response may make one explicit format-repair call. A changed fingerprint may make a fresh call.
- [ ] **Step 2: Confirm RED.** Run loop and acceptance tests and observe the existing local-target miss produce two calls.
- [ ] **Step 3: Remove target-miss replanning.** In `acceptPlan`, dispatch the typed local issue immediately. Never call `analyzeCurrentScreen(repairFormat = true)` because geometry/grounding failed. Keep `repairFormat` reachable only from `PlannerFailed(INVALID_RESPONSE)` and cap it at one attempt.
- [ ] **Step 4: Make fingerprint ownership explicit.** Cache only the in-flight fingerprint and attempt kind (`PRIMARY` or `FORMAT_REPAIR`); discard a response if the tree changes, and allow the new fingerprint to plan normally. Do not cache raw UI content.
- [ ] **Step 5: Verify and commit.** Run all session and acceptance tests; commit as `fix: avoid duplicate planner calls on local misses`.

## Task 6: Instrument latency and render useful guidance before speech

**Files:**

- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioTelemetry.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/ScenarioTelemetryTest.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/UniversalGuidanceLoop.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/SessionController.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/UniversalGuidanceLoopTest.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/OverlayService.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/overlay/OverlayRenderPolicyTest.kt`
- Modify only if measurements justify it: `src/ScreenSaathi/scripts/openai_proxy.py`

**Interfaces:**

```kotlin
enum class ScenarioLatencyStage {
    STT, SNAPSHOT, SAFETY, PLANNER, TARGET_RESOLUTION, FIRST_USEFUL_RENDER, TTS_START
}

data class ScenarioTelemetryEvent(
    // existing technical fields
    val stage: ScenarioLatencyStage? = null,
    val attempt: Int? = null,
    val outcome: String? = null,
)
```

- [ ] **Step 1: Write privacy and timing tests.** Ensure telemetry still has no goal/transcript/tree/label/request/response fields. With an injected clock/sink, assert one event per stage, non-negative durations, planner attempt count, and `FIRST_USEFUL_RENDER` before `TTS_START`.
- [ ] **Step 2: Add an overlay no-op policy test.** Identical command/position/size updates must not cause another `WindowManager.updateViewLayout`; a changed highlight, keyboard inset, expanded state, or drag position must still update.
- [ ] **Step 3: Confirm RED.** Run telemetry, loop, and overlay policy tests.
- [ ] **Step 4: Instrument the existing route.** Start text timing when a text goal is submitted and voice timing when recording stops; record STT, snapshot/safety, planner, resolution, first useful render, and TTS start. Emit technical metadata only. Render `ShowAnswer`/`ShowGuidance` synchronously on the main/UI route; keep `SpeakText` posted to the speech worker afterward.
- [ ] **Step 5: Skip redundant layout work.** Extract a pure `OverlayRenderPolicy`/layout key and bypass identical layout updates without suppressing animation frames or user drag updates.
- [ ] **Step 6: Measure before changing transport.** Run the fixed 20-sample warm text protocol from the spec plus one separately reported cold sample. If planner time dominates and proxy connection setup is material, switch the proxy to a reusable HTTPS client/connection with bounded reconnect; otherwise leave the proxy unchanged and record the evidence.
- [ ] **Step 7: Enforce budgets.** Text-to-first-useful-guidance must be p50 ≤ 3 s and p95 ≤ 5 s; voice-stop-to-first-useful-guidance must be p50 ≤ 5 s and p95 ≤ 8 s. A miss blocks release unless the user explicitly accepts a documented exception.
- [ ] **Step 8: Verify and commit.** Run telemetry/overlay/session tests and commit as `perf: measure and shorten guidance latency`.

## Task 7: Turn real Samsung trees into the acceptance regression suite

**Files:**

- Create/update sanitized fixtures under `src/ScreenSaathi/app/src/test/resources/ui_trees/real/yandex_go/`
- Create/update sanitized fixtures under `src/ScreenSaathi/app/src/test/resources/ui_trees/real/auto_ru/`
- Create/update sanitized fixtures under `src/ScreenSaathi/app/src/test/resources/ui_trees/real/common/`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/evals/UniversalGuidanceAcceptanceTest.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/evals/RealUiTreeRegressionTest.kt`
- Modify: `src/ScreenSaathi/docs/evals/DATASET.md`

- [ ] **Step 1: Capture the minimum real states.** Capture Yandex taxi entry, destination entry, and final-order handoff; Auto.ru sell entry, plate, VIN, one later form, and final publish handoff; popup with close control when reproducible; a popup without a close control; an auth surface using synthetic/test credentials only; and the observed off-viewport bottom navigation duplicate. If a transient popup cannot be reproduced, keep its deterministic synthetic fixture and mark it explicitly.
- [ ] **Step 2: Review every fixture before staging.** Search for phone/email/long digit sequences and known entered values. Confirm `capture_kind` is `sanitized_real` or `synthetic`; include app version, Android version, device model, capture date, and start-state description, but no account identity.
- [ ] **Step 3: Write table-driven route assertions.** Each fixture states expected disposition, resolver result, expected target index/resource ID when found, expected user recovery class, and maximum planner calls. Prove no target lies outside viewport and no editable value crosses the planner boundary.
- [ ] **Step 4: Confirm failures precede any remaining fixes.** Run `RealUiTreeRegressionTest`; if a new fixture fails, fix the general eligibility/resolution/safety rule in Tasks 2–5 with a focused unit test rather than adding an app branch.
- [ ] **Step 5: Keep synthetic evidence labeled.** Existing synthetic fixtures remain useful but cannot satisfy the physical-app acceptance requirement. Update dataset docs and test output to state the fixture kind.
- [ ] **Step 6: Verify and commit.** Run `./gradlew testDebugUnitTest --tests 'com.screensaathi.evals.RealUiTreeRegressionTest' --tests 'com.screensaathi.evals.UniversalGuidanceAcceptanceTest'`, then the full unit suite. Commit as `test: replay sanitized Samsung UI trees`.

## Task 8: Run live planner and physical Samsung acceptance

**Files:**

- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/evals/LatencyProfileTest.kt`
- Modify: `src/ScreenSaathi/docs/evals/README.md`
- Create: `docs/superpowers/reports/2026-10-27-opora-end-to-end-scenario-reliability.md`
- Update checkboxes/execution notes in this plan.

- [ ] **Step 1: Extend the opt-in live set.** Add at least 20 planner samples spanning Yandex address/map states, Auto.ru plate/VIN/forms, popup close/manual dismiss, true auth/final handoff, and current-screen questions. Use sanitized fixtures and the production prompt/transport; ordinary unit runs remain offline and free.
- [ ] **Step 2: Run the live gate.** Through the current Mac proxy/OpenAI setup, require zero safety errors, ≥90% usable plans, and ≤10% format repair. Record model, prompt version/hash, date, sample count, p50/p95 planner latency, repair rate, and approximate usage without storing prompts containing personal data.
- [ ] **Step 3: Build and install.** Run `./gradlew clean testDebugUnitTest assembleDebug`, install the APK on Samsung, restore `adb reverse tcp:8787 tcp:8787`, verify proxy health, accessibility binding, overlay permission, and app version.
- [ ] **Step 4: Developer text smoke — Yandex Go.** From a documented clean start, run the complete text route through address entry and stop before final order. Verify correct highlights, no system-bottom target, no false auth, no duplicate planner call per unchanged tree, popup behavior, and safe final handoff.
- [ ] **Step 5: Developer text smoke — Auto.ru.** Run the complete text route through plate, VIN, later forms, and stop before publish. The user enters any personal values. Verify correct input/control bounds and typed recovery wherever the tree cannot expose a safe target.
- [ ] **Step 6: Developer text smoke — one-shot questions.** Run two current-screen questions; each response must be short, Opora-framed, and must end any previous guidance session.
- [ ] **Step 7: User voice acceptance.** Give the user four concise cases: one full voice start for Yandex, one full voice start for Auto.ru, and two one-step voice current-screen questions. Popup/auth states do not require separate voice commands. Record pass/fail and timing only; do not record speech.
- [ ] **Step 8: Write the evidence report.** Separate automated offline, sanitized-real replay, live-model, developer text, user voice, and latency evidence. List any accepted exception and its owner/date; otherwise mark the release blocked.
- [ ] **Step 9: Final verification and commit.** Rerun full unit tests and `assembleDebug`; inspect `git diff --check` and `git status`. Commit report/docs as `docs: record end-to-end scenario acceptance`.

## Task 9: Review, push, and hand off

- [ ] **Step 1: Spec coverage review.** Check every completion criterion in the design spec against a test or report row; no criterion may rely on an uncited verbal claim.
- [ ] **Step 2: Safety/privacy review.** Search staged fixture/report/log changes for likely phone/email/address/VIN/OTP values and raw-tree dumps. Confirm release manifest has no fixture exporter.
- [ ] **Step 3: Architecture review.** Search production sources for Yandex/Auto.ru package names, coordinates, or route strings outside explicit risk policy/tests; reject app-specific scenario branches.
- [ ] **Step 4: Regression review.** Run `./gradlew testDebugUnitTest assembleDebug`, `git diff --check`, and review commits task by task.
- [ ] **Step 5: Push.** Push `codex/opora-mvp` through the existing repository-scoped SSH deploy key and report the final commit hash plus remaining manual voice cases, if any.

## Self-Review Checklist

- [ ] Each behavior change begins with a failing focused test and an observed RED state.
- [ ] Every created/modified path exists or is explicitly introduced by the task.
- [ ] Interface names are consistent across snapshot, resolver, reducer, controller, telemetry, and fixtures.
- [ ] Tests are proportional: pure geometry/safety in unit tests, sanitized real trees in replay, model quality in opt-in live eval, and only irreducible boundaries on Samsung.
- [ ] No task silently broadens scope into screenshots, automated interaction, backend migration, or design-system work.
- [ ] Each task ends in an independently reviewable commit.
