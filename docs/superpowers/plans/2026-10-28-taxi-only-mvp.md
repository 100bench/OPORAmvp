# Taxi-only OPORA MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a Samsung-testable OPORA build that accepts one spoken taxi goal in an already-open Yandex Go and uses OpenAI plus the live Accessibility tree to text-guide and highlight every next control through the final order button.

**Architecture:** Keep the proven microphone, STT, Accessibility reader, overlay and OpenAI transport. Specialize the active universal guidance path for taxi ordering: bypass safety/app-routing/confirmation gates, allow the model to select a live actionable element by semantic identity or snapshot index, and suppress TTS and unrelated controls.

**Tech Stack:** Kotlin, Android AccessibilityService and overlay windows, OpenAI structured tool calls, JUnit 4, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-28-taxi-only-mvp-design.md`

## Global Constraints

- Branch `codex/taxi-only-mvp` starts from `codex/opora-mvp` commit `d235039`.
- Yandex Go is opened manually before voice capture.
- Step sequence, labels, IDs and coordinates are never hardcoded.
- Guidance is text plus highlight only; the user performs every tap.
- Auth, address, sensitive-screen and final-action gates must not interrupt this experiment.
- Verification before handoff is focused tests, `assembleDebug`, APK install and device readiness only.

## Review Focus

- A planner index absent from the exact planning snapshot must never create a highlight.
- A disabled, hidden, off-viewport or non-actionable indexed node must be rejected.
- A final `Заказать` target must parse and resolve as ordinary guidance.
- A password or authentication screen must reach the planner instead of local handoff.
- A spoken goal must enter taxi guidance without app launch, intent routing or TTS playback.

---

### Task 1: Model-selected live target contract

**Files:**
- Modify: `src/ScreenSaathi/app/src/main/assets/prompts/planner_v1.md`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/sarvam/SarvamPlanner.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioModels.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/screen/TargetResolver.kt`
- Test: `src/ScreenSaathi/app/src/test/java/com/screensaathi/sarvam/TaxiPlannerContractTest.kt`
- Test: `src/ScreenSaathi/app/src/test/java/com/screensaathi/screen/TaxiTargetResolverTest.kt`

**Interfaces:**
- Produces: `ScenarioTarget.elementIndex: Int?` and `TargetResolver.resolveIndex(index, snapshot)`.
- Produces: OpenAI `target.index`, constrained to actionable indices in the supplied `PlannerScreen`.

- [ ] Write failing tests proving final-order guidance parses, an index-only target parses, and only an eligible actionable index resolves.
- [ ] Run the two new test classes and confirm the expected failures.
- [ ] Add `target.index` to the structured schema/parser and remove final-action refusal from this branch.
- [ ] Specialize the prompt for one taxi goal and require one next visible target whenever possible.
- [ ] Add index-backed local resolution without accepting elements absent from the same snapshot.
- [ ] Run the two tests and commit.

### Task 2: Direct taxi loop and text-only presentation

**Files:**
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/UniversalGuidanceLoop.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/ScenarioReducer.kt`
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/session/SessionController.kt`
- Test: `src/ScreenSaathi/app/src/test/java/com/screensaathi/session/TaxiOnlyGuidanceTest.kt`

**Interfaces:**
- Consumes: index-aware planner results and `TargetResolver.resolveIndex` from Task 1.
- Produces: voice transcript → immediate current-screen planning → `ShowGuidance` with resolved bounds.

- [ ] Write failing tests proving auth/final screens call the planner, first guidance renders without confirmation, app launch is not requested, and index fallback resolves ambiguity.
- [ ] Run `TaxiOnlyGuidanceTest` and confirm the expected failures.
- [ ] Bypass `ScreenSafetyGate`, local modal decisions and target-package routing in the universal loop.
- [ ] Present the first model guide immediately and resolve exact ID/text before the model-selected index fallback.
- [ ] Route successful STT directly into the universal loop with no local action proposal or app selection; suppress `SpeakText` presentation.
- [ ] Run the focused tests and commit.

### Task 3: Minimal overlay and Samsung handoff

**Files:**
- Modify: `src/ScreenSaathi/app/src/main/java/com/screensaathi/OverlayService.kt`
- Test: `src/ScreenSaathi/app/src/test/java/com/screensaathi/overlay/TaxiOverlayPolicyTest.kt`

**Interfaces:**
- Consumes: existing `OverlayCommand` text and highlight bounds.
- Produces: visible microphone, instruction and highlight with unrelated entry controls hidden.

- [ ] Write a focused failing policy test for taxi-only control visibility.
- [ ] Add a small Android-free visibility policy and apply it to hide typed input and language/general-purpose controls while retaining microphone, text and stop/close behavior.
- [ ] Run the three taxi-focused test classes plus the new overlay test.
- [ ] Run `./gradlew assembleDebug`, install with `adb install -r`, restore `adb reverse tcp:8787 tcp:8787`, and verify the Samsung is connected with the OPORA services ready.
- [ ] Commit the final branch state and hand the manual taxi scenario to the user immediately; defer broad regression until after their result.
