# Compact Opora overlay implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A small floating Opora circle opens and hides a compact card, avoids guided targets, and can be removed by a confirmed long press until the next launch.

**Architecture:** Keep the existing `OverlayService` and `SessionController`. Make the overlay window's actual dimensions follow its visible presentation. Keep the last rendered command while the card is hidden. Put placement decisions in the existing Android-free `AssistantPlacement` helper; the service applies them to `WindowManager.LayoutParams`.

**Tech Stack:** Android Kotlin, XML views/drawables, JUnit, Figma reference `vCfGuo8jss01yCpQjPX7x7` nodes `4:10`, `4:30`, `17:74`, `17:127`, `17:208`.

**Spec:** `docs/superpowers/specs/2026-10-24-opora-scenario-stabilization-design.md`, task 5.

## Global Constraints

- Keep OpenAI through the existing Mac proxy and Accessibility UI-tree-only perception.
- No automatic taps, input, scrolling, screenshot/OCR, auth, payment or final order confirmation.
- The user tests voice; the developer tests text and the real Samsung overlay.
- The Figma draft guides the circle and compact card, not a rewrite of all app screens.

## Review Focus

- A collapsed circle intercepts only its visible touch area; taps beside it reach the underlying app.
- A new instruction while manually collapsed remains available when the circle is reopened.
- Highlight placement does not cover the highlighted control, including targets near screen edges.
- Keyboard and system bars keep the circle and card reachable after resize, rotation or drag.
- Confirmed removal ends guidance and removes the circle until the user starts Opora again; cancelled removal preserves it.

---

### Task 1: Window footprint and circle/card presentation

**Files:** `src/ScreenSaathi/app/src/main/java/com/screensaathi/OverlayService.kt`, `overlay/AssistantUiState.kt`, `res/layout/overlay_pill.xml`, `res/drawable/`, `res/values/colors.xml`; test `overlay/AssistantPresentationTest.kt`.

**Interfaces:** A pure presentation decision returns the outer window width for circle versus card. `OverlayService` applies the width/height to its existing `pillParams` and repositions after layout.

- [ ] Write a failing test: collapsed/minimized uses a circle-sized window; expanded/listening/thinking/guiding uses a card-sized window; manual collapse retains the latest instruction.
- [ ] Run focused tests and verify the new case fails for the expected missing behavior.
- [ ] Implement the smallest presentation state and XML/style update. Reuse Figma mascot asset as a local Android drawable; keep Android controls reachable at large font scale.
- [ ] Run focused tests; install and inspect real `WindowManager` frames in both states on Samsung.

### Task 2: Tap toggle, drag and target avoidance

**Files:** `src/ScreenSaathi/app/src/main/java/com/screensaathi/OverlayService.kt`, `overlay/AssistantPlacement.kt`; test `overlay/AssistantPlacementTest.kt`.

**Interfaces:** Circle tap opens the last card; the card's circle tap hides it. `AssistantPlacement` chooses a safe card position around the highlighted rectangle, or returns a collapse decision when no non-overlapping position exists.

- [ ] Write failing placement tests for center, edges, keyboard, large target and no-space cases, plus a pure toggle-state test.
- [ ] Run focused tests and verify the intended failures.
- [ ] Wire tap/drag and placement without changing `SessionController` turn ownership. Apply the measured window dimensions to the hit region; suppress click after drag.
- [ ] Run focused tests and verify first-tap access to underlying highlighted controls on Samsung.

### Task 3: Confirmed removal and hidden diagnostics

**Files:** `src/ScreenSaathi/app/src/main/java/com/screensaathi/OverlayService.kt`, `res/layout/overlay_pill.xml`; tests for the confirmation decision and stop/cancel state.

**Interfaces:** A long press on the circle opens an in-card `Убрать Опору?` confirmation. Confirm calls the existing stop path and stops the service; cancel restores the last card. Move the debug-panel gesture to an expanded-card control so it does not conflict with removal.

**Existing behavior to preserve:** `OverlayService` currently uses the collapsed pill tap to start the microphone, long press on that row to show diagnostics, and long press on the expanded mic to enter circle selection mode. The new circle tap must reveal a visible mic control with one additional tap; keep circle selection available on that expanded mic and move only the diagnostic gesture.

- [ ] Write failing tests for confirm/cancel state and retained instruction.
- [ ] Run focused tests and verify the failures.
- [ ] Implement removal and preserve the existing debug access in the card.
- [ ] Run focused tests, all unit tests, `assembleDebug`, install and check removal/relaunch on Samsung.

### Task 4: Final physical verification

**Files:** Update the spec/plan only for actual findings.

- [ ] Check circle/card touch footprint with `dumpsys window`, dragging, keyboard, system bars, large font and a highlighted target near the circle on Samsung; use UI tree, no screenshots.
- [ ] Run `testDebugUnitTest`, `assembleDebug`, a clean-clone `assembleDebug`, and `git diff --check`.
- [ ] Remove temporary diagnostics and commit the verified task 5 changes separately from task 4.

### Measured outcome (Samsung, 2026-10-24)

- The closed mascot used a 270 × 270 px (96 dp) touch window; the opened card used 973 px (346 dp) width. Taps outside the circle reached the app below it. Tapping the mascot opened and closed the card; dragging it in either state preserved the intended size.
- A long press opened removal confirmation. Cancel restored the circle; confirm stopped `OverlayService`, and a later app launch restored it.
- The text field opened the keyboard on the first tap. At keyboard top 1362 px, the 938 px card moved to y=391 px and ended at y=1329 px. After Back it returned to y=1215 px and restored `FLAG_NOT_FOCUSABLE` so UI-tree reading could see the app beneath again.
- For a measured button at `[79,2089][1001,2153]`, the card moved from y=1215 to y=1118 px, leaving a 33 px gap. At temporary system font scale 1.5 the card remained within the display and resized for extra content; the previous scale 1.1 was restored.
- Unit tests and `assembleDebug` passed; temporary geometry broadcasts and logs were removed. The clean-clone build and final install were checked on the combined branch.
