# Opora modal obstruction implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When an accessible popup blocks a guided task, Opora points to its unique close control or asks the person to dismiss it manually, then resumes the same goal after the popup disappears.

**Architecture:** Preserve dialog ancestry while flattening the Accessibility tree. Before network planning, the device-local guidance loop checks an active dialog for a unique semantic dismiss target. A new reducer pause handles dialogs without an accessible close control; the popup's presence participates in screen identity so dismissal triggers replanning.

**Tech Stack:** Android Kotlin, AccessibilityService, pure Kotlin reducer, JUnit.

**Spec:** `docs/superpowers/specs/2026-10-24-opora-scenario-stabilization-design.md`, task 4.

## Global Constraints

- Use UI-tree perception only; no screenshot, OCR, automatic tap or scroll.
- Keep the current OpenAI route and preserve the user's original goal.
- The user taps a highlighted close control or closes the popup manually.
- No app/package-specific route or hardcoded screen sequence.
- Never log or commit raw popup text, addresses or account values.

## Review Focus

- A full-screen empty `modal_views_container` without a visible dialog does not trigger a popup pause.
- An exact close label outside the active dialog cannot be highlighted as its dismiss control.
- Two plausible close controls cause a safe manual instruction rather than a guessed ring.
- A dialog with no readable close control prompts dismissal, never scrolling; disappearance alone resumes the goal.
- Auth/payment/final-action safety still runs before popup guidance or any network call.

---

### Task 1: Keep dialog ancestry in local screen snapshots

**Files:** `ScreenReaderService.kt`, `screen/ScreenSnapshot.kt`, `session/UniversalGuidanceLoop.kt`; tests `screen/ScreenSnapshotTest.kt`, `session/UniversalGuidanceLoopTest.kt`.

**Interfaces:** `ScreenSnapshot.activeDialog: Boolean` and `ScreenElement.inDialog: Boolean` default to false. The reader marks a visible `Dialog`/`AlertDialog` node and its descendants. `ScreenSnapshot.signature()` and the loop's local freshness identity include the flag.

- [ ] Write failing tests: dialog presence changes a screen fingerprint, and a planner result from before a dialog appeared is rejected even if visible labels stayed the same.
- [ ] Run the focused tests and confirm those failures.
- [ ] Implement the local flags and ancestry propagation without adding popup text to logs or the provider DTO.
- [ ] Run focused and baseline tests.

### Task 2: Resolve a close control only inside the dialog

**Files:** Create `screen/ModalDismissResolver.kt`; test `screen/ModalDismissResolverTest.kt`.

**Interfaces:** `ModalDismissResolver.resolve(snapshot: ScreenSnapshot): Result` returns `NotModal`, `CloseTarget(ScreenElement)` or `ManualDismiss`. A target requires a visible, enabled, clickable, non-password descendant with an exact close/dismiss label, description or resource-id meaning; one nested static × label may map to its unique containing clickable ancestor.

- [ ] Write failing tests for a unique labelled cross, an unlabeled cross, no close target, two crosses, a matching control outside the dialog and an inert modal container on a normal screen.
- [ ] Run the focused tests and verify the expected failures.
- [ ] Implement exact semantic matching and unique local geometry; do not choose a control merely because it sits in a screen corner.
- [ ] Run focused tests.

### Task 3: Preserve the goal through manual popup dismissal

**Files:** `session/ScenarioModels.kt`, `session/ScenarioReducer.kt`, `session/UniversalGuidanceLoop.kt`, `session/SessionController.kt`; tests `session/ScenarioReducerTest.kt`, `session/UniversalGuidanceLoopTest.kt`.

**Interfaces:** A local `ManualDismissRequired` event enters `WAITING_FOR_MODAL_DISMISS` and emits a no-ring instruction. `ScreenObserved` on a changed dialog state reanalyzes the current screen without incrementing scroll attempts. A close target uses the existing `ShowGuidance` path with an instruction to tap the close control.

- [ ] Write failing reducer/loop tests for close target, no-cross pause, dismissal resume, retained original goal, no-scroll copy and safety precedence.
- [ ] Run focused tests and confirm failures.
- [ ] Implement the local branch before planner invocation and the new pause effect/copy.
- [ ] Run focused and full tests; verify no provider call while the dialog is active.

### Task 4: Real-device acceptance

**Files:** Update this plan with measured results and only de-identified structural fixture data if useful.

- [ ] Install on Samsung. Check a tree-readable popup in Лавка and the original prizes popup if it can be reopened. Verify the close control is highlighted only when unambiguous, otherwise there is no ring and no scroll instruction.
- [ ] Have the person close the popup; verify the same goal replans on the newly visible screen. Do not perform the tap on their behalf during acceptance.
- [ ] Run `testDebugUnitTest`, `assembleDebug`, clean-clone `assembleDebug`, and `git diff --check`; remove diagnostics and commit this task separately.

### Measured outcome (Samsung, 2026-10-24)

- The Лавка prizes popup no longer appeared during acceptance; its exact controls remain unverified.
- A native AppCompat dialog on the same Samsung had a partial-screen `FrameLayout` root, `parentPanel`, `alertTitle`, `buttonPanel` and 11 dialog descendants. Its ordinary dismissal button produced `WAITING_FOR_MODAL_DISMISS` with no ring and no scroll instruction. After closing it, the original goal returned to analysis automatically.
- In a temporary test variant, the same dialog had one explicit `Закрыть` button. The UI-tree branch emitted `ShowGuidance` with a ring, then resumed the goal after that button was pressed. Temporary button text, diagnostic broadcast and diagnostic logs were removed before the final build.
- Unit tests and `assembleDebug` passed. The combined branch was also built from a clean local clone without transferring an API key.
