# Data handling

This is a factual accounting of what ScreenSaathi reads, records, and sends,
written because the audit that produced this document correctly identified
that none of it was written down anywhere. It is **not** a legal privacy
policy — have one written by someone qualified before any production or
public release, and don't rely on this document in that person's place.

## What is read

`ScreenReaderService` (an `AccessibilityService`) reads the **foreground
window only**, while the service is enabled:

- Resource id, visible text, class name, on-screen bounds, and
  editable/clickable flags for each element — see `screen/ScreenSnapshot.kt`.
- Its own overlay's views are explicitly filtered out (`OVERLAY_IDS` in
  `ScreenReaderService.kt`) so the assistant never reads its own chrome.
- It does **not** take screenshots, does **not** read the clipboard, and does
  **not** read any app other than whichever one is currently in the
  foreground. It cannot see background apps, notifications content, or other
  users' data on a shared device.

## What is recorded

`WavRecorder` captures 16 kHz mono PCM audio from the microphone only between
a mic-tap-to-start and mic-tap-to-stop — there is no continuous listening, no
wake word, and no background recording. See `sarvam/WavRecorder.kt`.

## What leaves the device

The Android app sends three kinds of request to the localhost Mac development
gateway over USB reverse. It does not call OpenAI directly. The gateway then
forwards those requests to `api.openai.com`:

| Call | What is sent | What comes back |
| --- | --- | --- |
| `gpt-transcribe` STT | The recorded `.wav` clip | Transcript + detected language |
| `gpt-6-luna` planner | The transcript and a sanitized semantic projection of the current UI tree, without coordinates, password/current editable values or screenshot pixels | One structured guidance target and Russian instruction, or one short answer |
| `gpt-4o-mini-tts` | The instruction text and a voice instruction | Synthesized audio (WAV) |

No cloud third party other than OpenAI receives these requests in the current
development configuration. There is
no analytics SDK, no crash reporter, no ad SDK, and no telemetry of any kind
in the dependency list (`gradle/libs.versions.toml`) — this is a design
decision (see the top-level product rules: "no telemetry"), not an oversight.

## What is stored

- The input `.wav` file is written to `context.cacheDir`, one file per voice
  "turn," and deleted immediately after the STT call completes
  (`SessionController.process()`), whether it succeeds or fails.
- The synthesized TTS `.wav` is written to `context.cacheDir` for playback and
  deleted as soon as playback stops or fails (`AudioPlayer.stop()`), the same
  rule the input clip already followed.
- No separate/full transcript log, audio, UI tree, coordinates, element
  identifiers or screen image is written to persistent storage.
- A resumable safe guidance step may store one encrypted capsule in the app's
  private preferences. It contains only the goal, target package, guidance
  mode, step number, last safe instruction and timestamps; it expires after
  30 minutes and is deleted on stop, completion, restart or invalid data.
  The `goal` is the active user request; when that request came from voice, it
  is the recognized transcript text. No earlier turns or raw audio are kept.

## What a user cannot currently do

There is no in-app control to review what was sent, no in-app opt-out of the
cloud calls, and no on-device processing option. Stopping the development
gateway disables live AI. These are real gaps for anything beyond a demo — tracked
in `docs/PARKING_LOT.md` and the repository's issues labeled
[`audit-followup`](https://github.com/NITISH-R-G/ScreenSaathi/issues?q=is%3Aissue+label%3Aaudit-followup).

## Permissions and why each one is requested

| Permission | Why | What it does NOT grant |
| --- | --- | --- |
| `SYSTEM_ALERT_WINDOW` | Draw the floating pill and cursor over other apps | Cannot read other apps' content by itself |
| `RECORD_AUDIO` | Capture a voice clip when the mic is tapped | No background or continuous listening |
| `BIND_ACCESSIBILITY_SERVICE` | Read the foreground screen's elements to find what to point at | Not a screenshot API; text and layout only, foreground app only |
