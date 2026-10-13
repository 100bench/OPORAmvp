# Opora Android MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Стабилизировать Android MVP «Опоры» как универсальный русскоязычный помощник, который по живому Accessibility-дереву показывает один безопасный следующий шаг, никогда не действует вместо пользователя и корректно останавливается на чувствительных и финальных действиях.

**Architecture:** Существующие Accessibility, overlay, audio и transport компоненты сохраняются. Вокруг текущего `SessionController` вводится маленькое чистое ядро `ScenarioReducer`; Android-слой только превращает события в переходы и исполняет эффекты. Сырое дерево сначала проходит локальный `ScreenSafetyGate`, затем `ScreenSanitizer`; только очищенный DTO может попасть в текущий OpenAI-контур через Mac/VPN. Маршруты Яндекс Go, Settings, CDEK и Госуслуг являются acceptance-проверками одного цикла, а не отдельными production-сценариями.

**Tech Stack:** Kotlin/JVM 11, Android SDK 26–36, AccessibilityService, foreground overlay service, OkHttp 4.12, `org.json`, JUnit 4, Android Keystore AES/GCM, Gradle wrapper.

**Spec:** [`docs/superpowers/specs/2026-10-13-opora-mvp-design.md`](../specs/2026-10-13-opora-mvp-design.md)

All Android/Gradle commands below run from `src/ScreenSaathi`; file paths and `git add` commands are relative to the repository root.

## Global Constraints

- Сохранить `applicationId = com.screensaathi`, `minSdk = 26`, `targetSdk = 36` и Java/Kotlin 11.
- Пользовательский интерфейс MVP — только русский. Внутренние legacy-имена можно удалять постепенно, если они не видны пользователю.
- Не менять текущий OpenAI → Mac proxy → VPN transport и не начинать миграцию на DeepSeek/Yandex SpeechKit/RuStore.
- Не добавлять screenshots, vision, автоклики, ввод, прокрутку, системные жесты и model-initiated app launch.
- Не подсвечивать финальные кнопки заказа, отправки заявления и оплаты; секреты и sensitive-дерево не должны покидать устройство.
- Не добавлять DSL, плагины приложений, allowlist поддерживаемых приложений и захардкоженные последовательности экранов.
- `TargetResolver`, `ScreenReaderService`, `HighlightView`, `SessionController` и `SafetyGuard` изменять только вместе с тестом/измерением, объясняющим необходимость; device-facing изменения проверять на физическом Samsung.
- В production одновременно существует не более одной подсветки. Любое изменение fingerprint делает старую цель недействительной.
- Не коммитить `local.properties`, ключи, `.superpowers/`, записи аудио, сырые деревья и device-specific данные.

## Review Focus

Ревью каждого инкремента в первую очередь проверяет:

1. Результат планировщика, полученный для старого fingerprint, не показывается после смены экрана или во время подтверждения цели.
2. Значения editable/password-полей, телефоны, email, коды, длинные числа и bounds отсутствуют в provider DTO и логах.
3. Дубликаты Accessibility click/window events не увеличивают шаг дважды и не оставляют старую рамку.
4. Просроченная, повреждённая или созданная до reboot капсула удаляется; target/highlight/fingerprint никогда не восстанавливаются.
5. Восстановление сети не образует бесконечный retry-loop: один invalid-response retry, затем понятная остановка; изменившийся экран всегда проходит новый safety-check.

---

## Task 1: Restore a green, locale-independent baseline

**Files:**

- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/VoiceDebug.kt`
- Test: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/VoiceDebugTest.kt`

- [ ] **Step 1: Pin the regression with an explicit Russian locale test**

  Add a test that temporarily selects `Locale("ru", "RU")`, renders a confidence value, expects a dot-decimal stable debug representation, and restores the previous locale in `finally`.

- [ ] **Step 2: Run the focused test and verify it fails**

  Run: `./gradlew testDebugUnitTest --tests 'com.screensaathi.session.VoiceDebugTest'`

  Expected: failure showing comma-formatted confidence under the Russian locale.

- [ ] **Step 3: Make formatting deterministic**

  Change confidence formatting in `VoiceDebug.toPanel()` to `String.format(Locale.ROOT, "%.2f", value)`. Do not alter the panel contract.

- [ ] **Step 4: Verify the focused and full baseline**

  Run:

  ```bash
  ./gradlew testDebugUnitTest --tests 'com.screensaathi.session.VoiceDebugTest'
  ./gradlew testDebugUnitTest assembleDebug
  ```

  Expected: all non-ignored unit tests pass and the debug APK builds.

- [ ] **Step 5: Commit**

  ```bash
  git add src/ScreenSaathi/app/src/main/java/com/screensaathi/session/VoiceDebug.kt src/ScreenSaathi/app/src/test/java/com/screensaathi/session/VoiceDebugTest.kt
  git commit -m "fix: make voice debug locale independent"
  ```

## Task 2: Establish the Samsung smoke baseline

**Files:**

- Modify: `src/ScreenSaathi/docs/DEVELOPMENT.md`
- Verify: `src/ScreenSaathi/app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 1: Authorize the connected Samsung**

  Run: `adb devices -l`

  Expected: the Samsung changes from `unauthorized` to `device` after the user accepts the USB debugging prompt. Do not continue physical verification against an emulator by accident; record the exact serial selected with `adb -s <serial>`.

- [ ] **Step 2: Install the unchanged baseline APK**

  Run:

  ```bash
  ./gradlew assembleDebug
  adb -s <samsung-serial> install -r app/build/outputs/apk/debug/app-debug.apk
  ```

  Expected: build and install succeed.

- [ ] **Step 3: Perform and record a manual smoke check**

  Confirm overlay permission, Accessibility permission, microphone permission, one voice transcription, one ordinary highlighted element, and Stop clearing the highlight. Record Android/One UI versions, APK commit and observed failures without recording user speech or raw UI-tree content.

- [ ] **Step 4: Commit only the sanitized device note**

  ```bash
  git add src/ScreenSaathi/docs/DEVELOPMENT.md
  git commit -m "docs: record Samsung MVP smoke baseline"
  ```

## Task 3: Build the planner-safe screen boundary

**Files:**

- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/screen/ScreenSnapshot.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/ScreenReaderService.kt`
- Create: `src/ScreenSaathi/app/src/main/java/com/screensaathi/screen/PlannerScreen.kt`
- Create: `src/ScreenSaathi/app/src/main/java/com/screensaathi/screen/ScreenSanitizer.kt`
- Create: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScreenSafetyGate.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/screen/ScreenSanitizerTest.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/ScreenSafetyGateTest.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/screen/TargetResolverTest.kt`

- [ ] **Step 1: Write failing sanitizer contract tests**

  Cover these cases:

  - `PlannerScreen` contains package, visible label/description, role and boolean flags, but no `Rect` or coordinates;
  - editable nodes expose only hint/content description, never their current `text`;
  - password nodes and hidden/empty values are omitted;
  - email, phone, probable OTP and long numeric sequences become stable redaction markers;
  - ordinary Russian labels remain readable;
  - serialization cannot contain the raw forbidden samples.

- [ ] **Step 2: Write failing local safety tests**

  Define `ScreenDisposition { ALLOW, PAUSE_AUTH, SAFE_HANDOFF }` and test password/input type, OTP/PIN/CVV/card/biometric labels, payment/final-order labels and known bank package/app-label aliases. Assert sensitive assessment happens on raw local data and returns no serializable tree.

- [ ] **Step 3: Run tests and verify missing types fail compilation**

  Run:

  ```bash
  ./gradlew testDebugUnitTest --tests 'com.screensaathi.screen.ScreenSanitizerTest' --tests 'com.screensaathi.session.ScreenSafetyGateTest'
  ```

  Expected: compilation/test failure because the boundary is not implemented.

- [ ] **Step 4: Extend the raw snapshot without breaking existing callers**

  Add defaulted fields to `ScreenElement`: `contentDescription`, `hintText`, `enabled`, `selected`, `checked`, `scrollable`, `password`, and `inputType`. Populate them in `ScreenReaderService` from `AccessibilityNodeInfo`. Keep bounds and raw editable text local; do not add them to any prompt serializer.

- [ ] **Step 5: Implement immutable provider DTO and sanitizer**

  Add `PlannerScreen`/`PlannerElement` with a single deterministic `toPromptText()` implementation, and `ScreenSanitizer.sanitize(snapshot): PlannerScreen`. Retire direct use of `ScreenSnapshot.toPromptText()` from the provider path; leave it temporarily deprecated only if legacy tests still need it.

- [ ] **Step 6: Implement `ScreenSafetyGate.assess(...)`**

  Keep bank aliases and final-action vocabulary as safety data only. The gate must not encode route order or guidance. A sensitive result carries a Russian explanation/category, not the forbidden source value.

- [ ] **Step 7: Preserve local target resolution**

  Update constructors in existing tests and add an assertion that `TargetResolver` still uses the raw local snapshot/bounds after the DTO split.

- [ ] **Step 8: Verify and commit**

  Run:

  ```bash
  ./gradlew testDebugUnitTest --tests 'com.screensaathi.screen.*' --tests 'com.screensaathi.session.ScreenSafetyGateTest'
  ./gradlew testDebugUnitTest assembleDebug
  ```

  Expected: tests pass; the APK builds; no provider DTO field can represent bounds or editable value.

  ```bash
  git add src/ScreenSaathi/app/src/main/java/com/screensaathi/screen src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScreenSafetyGate.kt src/ScreenSaathi/app/src/main/java/com/screensaathi/ScreenReaderService.kt src/ScreenSaathi/app/src/test/java/com/screensaathi/screen src/ScreenSaathi/app/src/test/java/com/screensaathi/session/ScreenSafetyGateTest.kt
  git commit -m "feat: add safe accessibility tree boundary"
  ```

## Task 4: Introduce the pure scenario state machine

**Files:**

- Create: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioModels.kt`
- Create: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioReducer.kt`
- Create: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioTelemetry.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/ScenarioReducerTest.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/ScenarioTelemetryTest.kt`

- [ ] **Step 1: Write transition-table tests before production types**

  Test the complete user-visible state graph: idle → recognizing; ambiguous STT → `Верно/Повторить`; one-shot answer → completed without goal confirmation; first guide → confirming goal; confirmation → guiding; physical tap → waiting/analyzing; changed screen invalidates target; unchanged screen does not advance twice; auth/network pause; final action → safe handoff; Stop → idle and cleanup.

- [ ] **Step 2: Add failure and manual-scroll tests**

  Assert only actual changed fingerprints increment scroll attempts, three changed fingerprints produce a recoverable failure, a repeated Accessibility event is idempotent, and planner-invalid gets exactly one automatic retry before a user-facing failure.

- [ ] **Step 3: Run the focused suite and verify it fails**

  Run: `./gradlew testDebugUnitTest --tests 'com.screensaathi.session.ScenarioReducerTest'`

  Expected: missing types/behavior fail.

- [ ] **Step 4: Implement minimal immutable models**

  Add:

  - `ScenarioMode { ONE_SHOT, GUIDANCE, SAFE_HANDOFF }`;
  - `ScenarioPhase` values from the approved spec;
  - `ScenarioSession(goal, targetPackage, mode, phase, stepNumber, lastSafeInstruction, scrollAttempts, replanFailures, createdAtMs, updatedAtMs)`;
  - sealed `ScenarioEvent` and `ScenarioEffect` types that name user/Android/provider events without Android classes;
  - `ScenarioTransition(session, effects)`;
  - `ScenarioReducer.reduce(session, event, nowMs)` as a pure function.

  Do not add a state unless it changes user-visible choices or a safety rule.

- [ ] **Step 5: Add telemetry boundary with a no-op default**

  Define `ScenarioTelemetrySink.record(ScenarioTelemetryEvent)` and `NoOpScenarioTelemetrySink`. The event type may contain category, duration, package/device/network metadata and install ID, but must have no fields for goal, transcript, UI labels/tree, audio or request bodies. Test the type/fake sink with synthetic metadata only; do not add a remote sender.

- [ ] **Step 6: Verify and commit**

  Run:

  ```bash
  ./gradlew testDebugUnitTest --tests 'com.screensaathi.session.ScenarioReducerTest' --tests 'com.screensaathi.session.ScenarioTelemetryTest'
  ./gradlew testDebugUnitTest assembleDebug
  ```

  ```bash
  git add src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioModels.kt src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioReducer.kt src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioTelemetry.kt src/ScreenSaathi/app/src/test/java/com/screensaathi/session/ScenarioReducerTest.kt src/ScreenSaathi/app/src/test/java/com/screensaathi/session/ScenarioTelemetryTest.kt
  git commit -m "feat: add pure guidance scenario state machine"
  ```

## Task 5: Generalize user decision controls and exact app launch

**Files:**

- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/overlay/OverlayCommand.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/OverlayService.kt`
- Modify: `src/ScreenSaathi/app/src/main/res/layout/overlay_pill.xml`
- Modify: `src/ScreenSaathi/app/src/main/res/values/strings.xml`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/AppLauncher.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/device/DeviceContext.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/overlay/OverlayDecisionTest.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/device/DeviceContextTest.kt`

- [ ] **Step 1: Write failing pure decision and app-resolution tests**

  Add `DecisionButtons(primaryLabel, secondaryLabel)` and `DecisionChoice`. Test Russian label pairs for `Верно/Повторить`, `Начать/Отмена`, `Повторить/Остановить`, and `Продолжить/Остановить`. Test exact installed-label resolution, ambiguity, and that no launch effect exists until a user-tap event selects a concrete `DeviceApp.packageName`.

- [ ] **Step 2: Replace the fixed confirmation flag**

  Replace `confirmationRequired` with optional decision button data in `OverlayCommand`; render only provided labels. Route both buttons through one `SessionController.onDecisionTapped(choice)` entry point. Keep the separate `choices` list for concrete app cards.

- [ ] **Step 3: Add exact package launch**

  Add `AppLauncher.launchPackage(context, packageName): Boolean`, using only `getLaunchIntentForPackage`. `launchApp(label)` may remain as a compatibility wrapper, but the universal loop must launch the exact package captured from the user-selected `DeviceApp`; the model never supplies it.

- [ ] **Step 4: Verify and commit**

  Run:

  ```bash
  ./gradlew testDebugUnitTest --tests 'com.screensaathi.overlay.OverlayDecisionTest' --tests 'com.screensaathi.device.DeviceContextTest'
  ./gradlew testDebugUnitTest assembleDebug
  ```

  ```bash
  git add src/ScreenSaathi/app/src/main/java/com/screensaathi/overlay/OverlayCommand.kt src/ScreenSaathi/app/src/main/java/com/screensaathi/OverlayService.kt src/ScreenSaathi/app/src/main/res/layout/overlay_pill.xml src/ScreenSaathi/app/src/main/res/values/strings.xml src/ScreenSaathi/app/src/main/java/com/screensaathi/AppLauncher.kt src/ScreenSaathi/app/src/main/java/com/screensaathi/device/DeviceContext.kt src/ScreenSaathi/app/src/test/java/com/screensaathi/overlay/OverlayDecisionTest.kt src/ScreenSaathi/app/src/test/java/com/screensaathi/device/DeviceContextTest.kt
  git commit -m "feat: add explicit scenario decisions and app launch"
  ```

## Task 6: Harden the planner contract without changing provider

**Files:**

- Create: `src/ScreenSaathi/app/src/main/java/com/screensaathi/sarvam/GuidancePlanner.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/sarvam/SarvamPlanner.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/sarvam/PlannerResult.kt`
- Modify: `src/ScreenSaathi/app/src/main/assets/prompts/planner_v1.md`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/sarvam/SarvamPlannerParseTest.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/sarvam/GuidancePlannerTest.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/evals/OpenEndedEvalTest.kt`

- [ ] **Step 1: Write failing outcome-classification tests**

  Define `PlannerOutcome.Success(plan)` and `PlannerOutcome.Failure(kind, reason)` with `NETWORK`, `INVALID_RESPONSE`, `REMOTE`, and `CONFIGURATION`. Test IOException, non-2xx HTTP, missing configuration, malformed JSON, forbidden action type, missing target, final-action response and valid one-shot answer separately.

- [ ] **Step 2: Write prompt/DTO privacy tests**

  Pass only `PlannerScreen`, goal, step number and last safe instruction to `GuidancePlanner.planNext(...)`. Assert the serialized request contains no coordinate/bounds field, raw editable sample or executable action vocabulary. The schema may return only `guide` or `answer` and one semantic target.

- [ ] **Step 3: Implement the narrow interface and adapter**

  Keep the provider and current base URL untouched. Adapt `SarvamPlanner.planOpenEnded` behind `GuidancePlanner`; leave the legacy nullable `plan()` only for code not yet migrated. Map transport/HTTP/parse/configuration failures deterministically and do not emit response bodies to logs.

- [ ] **Step 4: Tighten prompt/schema**

  Require Russian, preserve the original goal, one next step, no coordinates, no click/type/scroll/launch instruction, no final order/payment target, and honest `answer`/failure when no meaningful semantic target exists. Do not mention acceptance app names as route logic.

- [ ] **Step 5: Add the single-retry policy at the caller boundary**

  Retry automatically only `INVALID_RESPONSE`, once, with a format-repair instruction. Network/remote/configuration failures become typed events for the reducer. Test call counts: valid=1, invalid-then-valid=2, invalid-twice=2, network=1.

- [ ] **Step 6: Verify and commit**

  Run:

  ```bash
  ./gradlew testDebugUnitTest --tests 'com.screensaathi.sarvam.*' --tests 'com.screensaathi.evals.OpenEndedEvalTest'
  ./gradlew testDebugUnitTest assembleDebug
  ```

  ```bash
  git add src/ScreenSaathi/app/src/main/java/com/screensaathi/sarvam src/ScreenSaathi/app/src/main/assets/prompts/planner_v1.md src/ScreenSaathi/app/src/test/java/com/screensaathi/sarvam src/ScreenSaathi/app/src/test/java/com/screensaathi/evals/OpenEndedEvalTest.kt
  git commit -m "feat: harden one-step guidance planner contract"
  ```

## Task 7: Integrate the universal loop into `SessionController`

**Files:**

- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/SessionController.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/SafetyGuard.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/sarvam/SarvamStt.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/ScreenReaderService.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/UniversalGuidanceLoopTest.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/OpenEndedGuardTest.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/evals/LatencyProfileTest.kt`

- [ ] **Step 1: Write failing orchestration tests with fakes**

  Cover:

  - first `answer` renders immediately and completes without goal confirmation;
  - first `guide` is cached while `Начать/Отмена` is shown;
  - if fingerprint changed before `Начать`, cached plan is discarded and replanned;
  - user-selected app card is the only path to `launchPackage`;
  - after a physical tap, old highlight clears before waiting/replanning;
  - duplicate tap/window events cannot advance twice;
  - planner target is resolved against the same current raw snapshot; unresolved target asks for manual scroll;
  - final/sensitive assessment causes no planner/TTS call and no highlight;
  - Stop cancels pending work and invalidates late callbacks.

- [ ] **Step 2: Add optional STT confidence without inventing data**

  Extend `SarvamStt.Result` with nullable `confidence`. Parse it only when supplied by the provider. A value below `0.60`, or a deterministic ambiguous transcript such as an empty/too-short result, produces `Верно/Повторить`; absent confidence alone does not.

- [ ] **Step 3: Convert controller entry points into reducer events**

  Keep Android work in `SessionController`, but route goal recognition, decisions, screen snapshots, physical clicks, planner outcomes, Stop and TTS errors through `ScenarioReducer`. Execute effects on the existing worker/speech threads. Preserve turn/session tokens so late network/audio callbacks cannot update a stopped or newer session.

- [ ] **Step 4: Enforce the safety/sanitizer ordering**

  For every analysis: capture raw snapshot → clear stale highlight when needed → `ScreenSafetyGate.assess` → if allowed `ScreenSanitizer.sanitize` → planner → resolve target locally against the same fingerprint → render. Add a focused fake-transport assertion that the planner call count remains zero for sensitive screens.

- [ ] **Step 5: Implement manual-scroll accounting**

  Ask the user to scroll; count only a genuinely changed local fingerprint; reanalyze after each change. At three changed-but-unsuccessful screens show choices to rephrase, restart or stop. Never call an Accessibility scroll action.

- [ ] **Step 6: Run focused and full tests**

  Run:

  ```bash
  ./gradlew testDebugUnitTest --tests 'com.screensaathi.session.UniversalGuidanceLoopTest' --tests 'com.screensaathi.session.*' --tests 'com.screensaathi.evals.LatencyProfileTest'
  ./gradlew testDebugUnitTest assembleDebug
  ```

  Expected: green suite, one active highlight maximum, and no hardcoded app route in the new loop.

- [ ] **Step 7: Verify the first vertical route on Samsung before expanding**

  Install on the authorized Samsung and run the Яндекс Go burger flow. Confirm: goal confirmation; optional user-approved app launch; one current highlight; user performs every tap/type/scroll; the app can reach cart; final order/payment is never highlighted; proxy shows no call from a sensitive/final screen. Record timing/failures without raw tree content.

- [ ] **Step 8: Review the abstraction checkpoint**

  Compare `SessionController` branching before/after. Stop and revisit the design if adding another app would require a separate sequence or if the reducer only mirrors controller branches without reducing decision ownership.

- [ ] **Step 9: Commit**

  ```bash
  git add src/ScreenSaathi/app/src/main/java/com/screensaathi/session src/ScreenSaathi/app/src/main/java/com/screensaathi/sarvam/SarvamStt.kt src/ScreenSaathi/app/src/main/java/com/screensaathi/ScreenReaderService.kt src/ScreenSaathi/app/src/test/java/com/screensaathi/session src/ScreenSaathi/app/src/test/java/com/screensaathi/evals/LatencyProfileTest.kt src/ScreenSaathi/docs/DEVELOPMENT.md
  git commit -m "feat: integrate universal accessibility guidance loop"
  ```

## Task 8: Add bounded network pause and recovery

**Files:**

- Create: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/NetworkMonitor.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/SessionController.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioReducer.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/NetworkRecoveryTest.kt`

- [ ] **Step 1: Write failing recovery tests**

  Test network failure → `PausedForNetwork` with `Повторить/Остановить`; manual Retry; automatic reconnect; unchanged fingerprint permits one retry; changed fingerprint forces safety-check and fresh analysis; repeated connectivity callbacks are idempotent; Stop unregisters/cancels work.

- [ ] **Step 2: Implement lifecycle-safe connectivity observation**

  Wrap `ConnectivityManager` default network callback in `NetworkMonitor`. Register only while needed and always unregister on controller/service disposal. Connectivity never bypasses the reducer or calls the planner directly.

- [ ] **Step 3: Integrate bounded recovery**

  Keep goal and last safe instruction in memory. On reconnect compare the in-memory fingerprint only; never persist it. Use the current screen on changed fingerprint. Preserve the one-invalid-response retry limit across reconnects so callbacks cannot restart an unlimited cycle.

- [ ] **Step 4: Verify and commit**

  Run:

  ```bash
  ./gradlew testDebugUnitTest --tests 'com.screensaathi.session.NetworkRecoveryTest' --tests 'com.screensaathi.session.ScenarioReducerTest'
  ./gradlew testDebugUnitTest assembleDebug
  ```

  ```bash
  git add src/ScreenSaathi/app/src/main/java/com/screensaathi/session/NetworkMonitor.kt src/ScreenSaathi/app/src/main/java/com/screensaathi/session/SessionController.kt src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioReducer.kt src/ScreenSaathi/app/src/test/java/com/screensaathi/session/NetworkRecoveryTest.kt
  git commit -m "feat: pause and safely recover guidance on network loss"
  ```

## Task 9: Persist the minimal encrypted resume capsule

**Files:**

- Create: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ResumeCapsule.kt`
- Create: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ResumeCapsuleStore.kt`
- Create: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/AndroidResumeCapsuleStore.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/SessionController.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/SaathiApp.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/ResumeCapsuleTest.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/ResumeCapsuleStoreTest.kt`

- [ ] **Step 1: Write codec and lifecycle tests first**

  Assert the capsule contains only goal, target package, mode, step number, last safe instruction, wall timestamp and elapsed-realtime timestamp. Assert serialized data has no tree, labels, fingerprint, bounds, target, highlight, audio, contact value or secret.

- [ ] **Step 2: Write expiry/reboot/tamper tests**

  Test valid load under 30 minutes, TTL expiry, `currentElapsedRealtime < savedElapsedRealtime` reboot detection, corrupted JSON, failed authentication/tag verification, explicit Stop and successful completion. Every invalid case returns no capsule and deletes stored data.

- [ ] **Step 3: Implement pure codec/store ports**

  Keep JSON codec and key-value/cipher ports testable on JVM. Implement Android storage with `AndroidKeyStore` AES/GCM and `SharedPreferences` Base64 IV+ciphertext. Generate a non-exportable key; never log plaintext/ciphertext.

- [ ] **Step 4: Integrate safe restoration**

  Save only after a safe logical transition. On process start load a valid capsule and show `Продолжить/Остановить`. Continue by reading the live screen, rerunning `ScreenSafetyGate`, and replanning; never restore a target, highlight, raw snapshot or fingerprint.

- [ ] **Step 5: Verify and commit**

  Run:

  ```bash
  ./gradlew testDebugUnitTest --tests 'com.screensaathi.session.ResumeCapsule*'
  ./gradlew testDebugUnitTest assembleDebug
  ```

  ```bash
  git add src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ResumeCapsule.kt src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ResumeCapsuleStore.kt src/ScreenSaathi/app/src/main/java/com/screensaathi/session/AndroidResumeCapsuleStore.kt src/ScreenSaathi/app/src/main/java/com/screensaathi/session/SessionController.kt src/ScreenSaathi/app/src/main/java/com/screensaathi/SaathiApp.kt src/ScreenSaathi/app/src/test/java/com/screensaathi/session/ResumeCapsuleTest.kt src/ScreenSaathi/app/src/test/java/com/screensaathi/session/ResumeCapsuleStoreTest.kt
  git commit -m "feat: restore safe guidance from encrypted capsule"
  ```

## Task 10: Disable screenshot runtime paths and finish the Russian safety surface

**Files:**

- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/circle/CircleSession.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/SessionController.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/Phrases.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/sarvam/Language.kt`
- Modify: `src/ScreenSaathi/app/src/main/res/values/strings.xml`
- Modify: `src/ScreenSaathi/README.md`
- Modify: `src/ScreenSaathi/docs/ARCHITECTURE.md`
- Modify: `src/ScreenSaathi/docs/DATA_HANDLING.md`
- Modify: `src/ScreenSaathi/docs/DEMO.md`
- Modify: `src/ScreenSaathi/docs/DEVELOPMENT.md`
- Modify: `src/ScreenSaathi/docs/OPORA_MVP.md`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/circle/CircleSessionTest.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/PhrasesTest.kt`
- Modify: `src/ScreenSaathi/app/src/test/java/com/screensaathi/sarvam/LanguageTest.kt`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/BankSafetyIntegrationTest.kt`

- [ ] **Step 1: Write failing no-screenshot and bank-boundary tests**

  Inject fakes that fail the test if screenshot/vision capture is invoked. Assert circle/help behavior uses Accessibility tree only or reports it unavailable. For known bank package, assert highlight is cleared, no recording/planner/TTS network request occurs, and the overlay shows a local Russian safe-handoff message.

- [ ] **Step 2: Make screenshot capture structurally unavailable**

  Wire `CircleSession` to an unavailable capture provider or disable its screenshot gesture entry point for MVP. Remove runtime construction/calls of `AccessibilityScreenCapture`/vision from reachable MVP paths; retaining dead compatibility types is acceptable only if tests prove they cannot be invoked.

- [ ] **Step 3: Remove user-visible legacy language/provider content**

  Set Russian (`ru-RU`) as the only user-facing language/default, remove Hindi/Tamil/English UI choices and Sarvam branding from strings/current docs, while leaving internal class renames for later if renaming would increase risk. Do not change OpenAI endpoints or `applicationId`.

- [ ] **Step 4: Verify and commit**

  Run:

  ```bash
  ./gradlew testDebugUnitTest --tests 'com.screensaathi.circle.CircleSessionTest' --tests 'com.screensaathi.session.BankSafetyIntegrationTest' --tests 'com.screensaathi.session.PhrasesTest' --tests 'com.screensaathi.sarvam.LanguageTest'
  ./gradlew testDebugUnitTest assembleDebug
  ```

  ```bash
  git add src/ScreenSaathi/app/src/main/java/com/screensaathi/circle/CircleSession.kt src/ScreenSaathi/app/src/main/java/com/screensaathi/session src/ScreenSaathi/app/src/main/java/com/screensaathi/sarvam/Language.kt src/ScreenSaathi/app/src/main/res/values/strings.xml src/ScreenSaathi/app/src/test/java/com/screensaathi/circle/CircleSessionTest.kt src/ScreenSaathi/app/src/test/java/com/screensaathi/session src/ScreenSaathi/app/src/test/java/com/screensaathi/sarvam/LanguageTest.kt src/ScreenSaathi/README.md src/ScreenSaathi/docs/ARCHITECTURE.md src/ScreenSaathi/docs/DATA_HANDLING.md src/ScreenSaathi/docs/DEMO.md src/ScreenSaathi/docs/DEVELOPMENT.md src/ScreenSaathi/docs/OPORA_MVP.md
  git commit -m "feat: enforce tree-only Russian MVP safety surface"
  ```

## Task 11: Add acceptance fixtures without scenario hardcode

**Files:**

- Create: `src/ScreenSaathi/app/src/test/resources/ui_trees/yandex_go/*.json`
- Create: `src/ScreenSaathi/app/src/test/resources/ui_trees/samsung_settings/*.json`
- Create: `src/ScreenSaathi/app/src/test/resources/ui_trees/cdek/*.json`
- Create: `src/ScreenSaathi/app/src/test/resources/ui_trees/gosuslugi/*.json`
- Create: `src/ScreenSaathi/app/src/test/resources/ui_trees/bank/*.json`
- Create: `src/ScreenSaathi/app/src/test/resources/ui_trees/unknown_app/*.json`
- Create: `src/ScreenSaathi/app/src/test/java/com/screensaathi/evals/UniversalGuidanceAcceptanceTest.kt`
- Modify: `src/ScreenSaathi/docs/DEVELOPMENT.md`
- Modify: `src/ScreenSaathi/docs/TROUBLESHOOTING.md`

- [ ] **Step 1: Create synthetic/de-identified tree fixtures**

  Store only textual structural samples needed for tests; no screenshots, addresses, phone numbers, account data, real order contents or secrets. Fixtures describe independent screens and variations, not an ordered production script.

- [ ] **Step 2: Write the cross-app acceptance harness**

  Feed every fixture through the same safety → sanitizer → planner-fake → resolver/reducer path. Assert one target maximum, no executable action, final/sensitive handoff, and honest failure for an unusable tree. Include at least one unknown non-acceptance app to prove no allowlist.

- [ ] **Step 3: Add anti-hardcode assertions**

  Verify production session/planner code does not branch on acceptance package names or screen sequence IDs. Package-specific data may appear only in safety/compatibility policy with a named reason and test.

- [ ] **Step 4: Document fixture capture and honest limitations**

  Explain how developers convert observed UI-tree structure into de-identified fixtures and how Canvas/OpenGL/poorly semantic WebView/Compose/secure windows fail safely.

- [ ] **Step 5: Verify and commit**

  Run:

  ```bash
  ./gradlew testDebugUnitTest --tests 'com.screensaathi.evals.UniversalGuidanceAcceptanceTest'
  ./gradlew testDebugUnitTest assembleDebug
  ```

  ```bash
  git add src/ScreenSaathi/app/src/test/resources/ui_trees src/ScreenSaathi/app/src/test/java/com/screensaathi/evals/UniversalGuidanceAcceptanceTest.kt src/ScreenSaathi/docs/DEVELOPMENT.md src/ScreenSaathi/docs/TROUBLESHOOTING.md
  git commit -m "test: cover universal guidance acceptance routes"
  ```

## Task 12: Final automated and physical verification

**Files:**

- Modify: `src/ScreenSaathi/docs/DEVELOPMENT.md`
- Modify: `src/ScreenSaathi/docs/OPORA_MVP.md`

- [ ] **Step 1: Run the repository verification suite from a clean Gradle process**

  Run:

  ```bash
  ./gradlew --stop
  ./gradlew clean testDebugUnitTest assembleDebug
  git status --short
  ```

  Expected: all non-ignored tests pass, APK builds, and only intentional documentation/device-result changes remain. `.superpowers/`, `local.properties`, APKs and secrets are untracked/ignored.

- [ ] **Step 2: Audit forbidden operations statically**

  Search reachable MVP code for `performAction`, gesture dispatch, text setting, screenshot/media projection and planner-created app launch. Review every match; test helpers and unavailable compatibility classes must not be reachable from the scenario loop.

- [ ] **Step 3: Run each Samsung route five consecutive times**

  Verify Яндекс Go to cart/handoff, Samsung Settings to font control, CDEK to first safe pickup-point choice, Госуслуги to auth/personal-data boundary, bank local handoff, one one-shot question and one unknown app. Record crashes, ANRs, wrong highlights, any accidental action, request suppression on sensitive screens and next-guidance latency. Require five consecutive passes and p95 ≤10 seconds on stable network.

- [ ] **Step 4: Exercise recovery on Samsung**

  Test network loss/reconnect with unchanged and changed screen; low-confidence/ambiguous speech; three manual-scroll changes; Stop during a pending response; process kill and resume under 30 minutes; expired capsule; device reboot; unexpected app switch; TTS failure with visible text retained.

- [ ] **Step 5: Run the device matrix when hardware is available**

  Repeat the required routes five times on Pixel/clean Android and Xiaomi/Redmi. If those devices are not available, do not mark the MVP ready for pilot: report the Samsung result separately and leave the matrix criterion open.

- [ ] **Step 6: Update evidence and commit**

  Update current documentation with commit/APK identity, OS/device versions, sanitized outcomes, measured latency and open limitations. Do not include raw transcripts, UI labels from personal screens, tree dumps, tokens or proxy bodies.

  ```bash
  git add src/ScreenSaathi/docs/DEVELOPMENT.md src/ScreenSaathi/docs/OPORA_MVP.md
  git commit -m "docs: record Opora MVP verification evidence"
  ```

- [ ] **Step 7: Request final code review before integration**

  Use `superpowers:requesting-code-review`, resolve findings with `superpowers:receiving-code-review`, rerun `./gradlew clean testDebugUnitTest assembleDebug`, and only then use `superpowers:finishing-a-development-branch` to choose merge/PR/cleanup.
