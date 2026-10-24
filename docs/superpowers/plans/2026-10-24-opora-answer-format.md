# Opora One-Shot Answer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Russian question such as «Что находится на экране?» reliably yields one safe, helpful Opora answer instead of two invalid-format attempts.

**Architecture:** Keep the existing forced `set_next_step` tool call and `UniversalGuidanceLoop`. Treat `action_type=answer` as the authoritative terminal intent when `is_done` is a Boolean, while retaining all other schema, language, and safety checks. Clarify the shipped prompt so a screen description sounds like Opora speaking to an older person. A new question still replaces and ends the previous scenario.

**Tech Stack:** Kotlin, Android Accessibility, org.json, JUnit4, Gradle; OpenAI via existing Mac proxy for an opt-in live check.

**Spec:** `docs/superpowers/specs/2026-10-24-opora-scenario-stabilization-design.md`, task 1.

## Global Constraints

- Use only Accessibility UI-tree data; no screenshot, OCR, or vision path.
- Do not add automatic taps, input, scrolling, payment, or order confirmation.
- Keep OpenAI through the Mac proxy/VPN and the Russian-only surface.
- The user tests spoken wording; the developer checks text requests and the network chain on Samsung.
- A question asked during guidance ends that previous scenario; do not add a conversation-resume mechanism.

## Review Focus

1. `answer` with Boolean `is_done=false`: return a terminal answer; test in Task 1.
2. `guide` with `is_done=true`: remain invalid; regression test in Task 1.
3. `answer` with a non-Boolean `is_done`: remain invalid; test in Task 1.
4. Foreign-language or final-action instruction: remain invalid; existing parser tests plus Task 1 test run.
5. Screen describes an inaccessible target: do not invent a tap; live synthetic check in Task 2.

---

## File structure

- `app/src/main/java/com/screensaathi/sarvam/SarvamPlanner.kt`: tool response parsing only; no session decisions.
- `app/src/test/java/com/screensaathi/sarvam/SarvamPlannerParseTest.kt`: response shape, terminal semantics, and safety regressions.
- `app/src/main/assets/prompts/planner_v1.md`: user-facing Opora persona and response rules.
- `app/src/test/java/com/screensaathi/evals/LatencyProfileTest.kt`: optional production-route check for one-shot screen question and existing guide case.

### Task 1: Make a typed `answer` terminal

**Files:**
- Modify: `app/src/test/java/com/screensaathi/sarvam/SarvamPlannerParseTest.kt`
- Modify: `app/src/main/java/com/screensaathi/sarvam/SarvamPlanner.kt`

**Interfaces:**
- Consumes: `SarvamPlanner.parseGuidanceOutcome(raw: String, goal: String): PlannerOutcome`.
- Produces: `PlannerOutcome.Success` with `PlannerResult.actionType == "answer"` and `isDone == true` for a valid typed answer, regardless of the model's Boolean `is_done` value. `guide` still requires `is_done == false` and a semantic target.

- [x] **Step 1: Write a failing parser test.** Feed `guidanceResponse` arguments with `is_done:false`, `action_type:"answer"`, no target and `instruction:"На экране открыты настройки."`. Assert success, `isDone == true`, empty target resource ID/text. Add an `answer` with `is_done:"false"` to the invalid-schema cases; retain the existing `guide`-true case.
- [x] **Step 2: Run the focused test.** Run `./gradlew testDebugUnitTest --tests 'com.screensaathi.sarvam.SarvamPlannerParseTest'`; expect the new answer case to fail while the old strict parser is active.
- [x] **Step 3: Make the smallest parser change.** In `parseGuidanceOutcome`, accept `answer` for either Boolean `is_done` value and derive `PlannerResult.isDone` from `actionType == "answer"`; keep required fields, type checks, one tool call, Russian check, final-action policy, and `guide` consistency unchanged.
- [x] **Step 4: Verify the parser.** Run the focused test above; expect all tests in this class to pass.
- [x] **Step 5: Commit this independently testable change.** Stage only the two Task 1 files and commit `fix: accept typed terminal planner answers`.

### Task 2: Make screen answers sound like Opora

**Files:**
- Modify: `app/src/main/assets/prompts/planner_v1.md`
- Modify: `app/src/test/java/com/screensaathi/evals/LatencyProfileTest.kt`

**Interfaces:**
- Consumes: existing `set_next_step` schema and parser from Task 1.
- Produces: Russian one-shot answer guidance for `Что находится на экране?` that names only supported screen context and a few useful visible options, in calm language for an older user. The prompt must require `is_done=true` for `answer`, but parser tolerance handles a Boolean mismatch.

- [x] **Step 1: Add a synthetic live-eval case.** In `profileCases()`, add a required `question_screen` case with `goal="Что находится на экране?"` and two neutral controls `"Настройки"` and `"Профиль"`. Add `expectedActionType: String?` to `ProfileCase`; for this case set it to `"answer"`. Extend `Sample.succeeded` to require the expected action type, a Russian `instruction` without `"скрин"`, and one short offer to help the user with their next goal (`помог`, `подскаж`, or `скажите, что`). This differentiates helpful Opora guidance from a bare inventory. Preserve existing cases and latency assertions. This is an opt-in live test, so it does not call the API in an ordinary unit run.
- [x] **Step 2: Run existing local tests as a baseline.** Run `./gradlew testDebugUnitTest --tests 'com.screensaathi.evals.LatencyProfileTest'`; the live case remains opt-in without credentials.
- [x] **Step 3: Refine the shipped prompt.** Identify the assistant as «Опора», describe its older user's need for a short screen explanation, require `answer` with `is_done=true` for direct screen questions, mention only elements actually available in the UI tree, and avoid claims about screenshots or unseen context.
- [x] **Step 4: Verify complete local regression.** Run `./gradlew testDebugUnitTest`; expect zero failures.
- [ ] **Step 5: Verify production route and device.** With the existing Mac proxy and Samsung connected, run `SCREENSAATHI_EVAL_LIVE=1 REPEATS=1 ./gradlew testDebugUnitTest --tests 'com.screensaathi.evals.LatencyProfileTest'` and one text request in the overlay. Record status/latency and whether the answer was useful, without saving speech or raw UI tree. The user then performs the voice check.
- [x] **Step 6: Commit this independently testable change.** Stage the prompt and eval files and commit `feat: give concise Opora screen answers`.

## Verification notes

- Parser regression failed before the fix and passed after it; full local unit tests and `assembleDebug` passed on 24 October 2026.
- The opt-in live eval used the Mac proxy with one repetition: `question_screen` and two other required cases succeeded, all four HTTP calls returned 200, and sample p95 was 7.3 seconds.
- The new APK was installed on Samsung `RF8MA183F5A`; an English text equivalent was submitted through the overlay because `adb shell input text` failed on Cyrillic. The proxy returned HTTP 200. UI Automator did not expose the floating window answer, so the visual phone response and the user’s voice check remain open.
