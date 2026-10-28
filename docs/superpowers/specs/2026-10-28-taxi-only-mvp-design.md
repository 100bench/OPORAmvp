# Taxi-only OPORA MVP Design

## Intent

Produce a fast experimental branch for one manual scenario: the user opens
Yandex Go, taps OPORA's microphone and states a taxi-ordering goal. OPORA then
uses the current Accessibility UI tree and OpenAI to choose each next visible
control, renders a short Russian text instruction and draws a ring around that
control until the user reaches and taps the final order button.

The screen sequence, labels, resource IDs and coordinates are not hardcoded.
Every step is selected from the current UI tree by the model and resolved to a
live on-device element.

## Goals

- Keep voice input and OpenAI planning through the Mac development gateway.
- Support only taxi ordering in an already-open Yandex Go session.
- Replan from the current UI tree after every user tap or screen transition.
- Always highlight the model-selected visible actionable element, including
  address fields, authentication-related screens and the final order button.
- Show guidance as text in OPORA; do not play TTS guidance.
- Finish after the user taps the highlighted final order button. OPORA never
  performs taps or submits the order itself.

## Non-goals

- Other apps, generic questions, circle selection, typed commands, app launch,
  app choice, resume, multi-language guidance and broad production safety.
- Screenshots, OCR, hardcoded Yandex Go screen sequences or element IDs.
- Full regression and production readiness before the first manual Samsung run.

## Constraints and active path

- The experiment starts from `codex/opora-mvp` at `d235039` and lives on
  `codex/taxi-only-mvp`.
- Keep the existing Accessibility reader, overlay geometry, voice recorder,
  STT transport and OpenAI planner transport because they already work on the
  connected Samsung.
- Bypass the local screen safety gate, final-action refusal, action-policy
  confirmation and TTS effects for this taxi-only path.
- Keep the semantic planner projection so the model receives a stable UI tree;
  geometry remains on the phone. This projection must not pause guidance.
- Resolve exact model-provided resource ID or visible text first. When that is
  ambiguous, allow the model-selected actionable element index from the same
  snapshot as the fallback. Never invent coordinates or target a node absent
  from that snapshot.
- Hide or disable user-facing controls unrelated to voice taxi guidance. Keep
  the floating OPORA entry point, microphone, instruction text, highlight and
  stop/close behavior needed for the manual run.
- If Yandex Go exposes no actionable accessibility node, show a short text
  failure instead of fabricating a highlight; this is the only honest limit on
  “always highlight”.

## Approaches considered

1. **Selected: taxi-only path inside the existing engine.** Fastest route that
   preserves the working device integration while bypassing unrelated policy
   and UI paths.
2. Prompt-only change. Rejected because local safety and final-action policies
   would still block the requested scenario.
3. Rewrite as a new minimal app. Rejected for the first experiment because it
   would duplicate the working Accessibility, overlay and microphone plumbing
   and delay the Samsung test.

## Acceptance for the first build

1. Open Yandex Go manually on the Samsung.
2. Tap OPORA's microphone and say a taxi-ordering goal in Russian.
3. OPORA displays text only and highlights the next visible control chosen from
   the live UI tree.
4. After each user tap, OPORA replans and moves the highlight to the next
   control without auth, address, sensitive-screen or final-action handoff.
5. OPORA highlights the final “Заказать” control and does not tap it.
6. Verification before handoff is limited to focused JVM tests, a debug build,
   APK installation and a basic device/process readiness check. The user runs
   the full voice scenario manually; broader regression follows afterward.
