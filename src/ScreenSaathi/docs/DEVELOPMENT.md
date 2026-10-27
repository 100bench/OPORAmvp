# Development

## Requirements

JDK 21 · Android SDK (compileSdk 36.1, build-tools 36.1.0) · minSdk 26. Gradle
itself is not required — the wrapper is committed.

## Setup

```bash
cd src/ScreenSaathi
cp local.properties.example local.properties
# edit local.properties: set sdk.dir and openai.api.key
```

The key stays on the development computer. It is read by the small local
gateway in `scripts/openai_proxy.py` and is never compiled into the APK.

## Build

```bash
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Test

```bash
./gradlew testDebugUnitTest       # JVM only, no device
```

3 tests are `replay_only` fixtures that skip without a live provider key — this
is expected, not a failure.

## Universal guidance loop

Text and voice goals enter the same `UniversalGuidanceLoop`; text skips only
STT. The user-facing MVP is Russian-only. The loop is app-agnostic and sends the planner one sanitized UI-tree
snapshot at a time. Its required order is:

1. capture the raw accessibility snapshot locally;
2. run `ScreenSafetyGate` on the raw tree and stop before network/TTS for auth,
   banking, payment and final-action screens;
3. sanitize into `PlannerScreen` (no coordinates or editable values);
4. request one `guide` or `answer` from the planner;
5. resolve a guide target against the same raw snapshot and keep its geometry
   on-device;
6. render one highlight and wait for the user's physical tap or scroll.

The planner cannot click, type, scroll or launch an app. When the user's own
goal contains one unambiguous full label from the locally discovered app list,
the controller may offer that exact verified app card; launch is emitted only
after the user selects the card. A changed UI-tree
fingerprint causes replanning; duplicate events are ignored. After three
changed manual-scroll views without a resolvable target, the UI offers
rephrase, restart or stop instead of guessing.

Focused regression command:

```bash
./gradlew testDebugUnitTest \
  --tests 'com.screensaathi.session.UniversalGuidanceLoopTest' \
  --tests 'com.screensaathi.session.*' \
  --tests 'com.screensaathi.evals.LatencyProfileTest'
```

The no-screenshot/Russian/banking boundary has its own focused suite:

```bash
./gradlew testDebugUnitTest \
  --tests 'com.screensaathi.circle.CircleSessionTest' \
  --tests 'com.screensaathi.session.BankSafetyIntegrationTest' \
  --tests 'com.screensaathi.session.PhrasesTest' \
  --tests 'com.screensaathi.sarvam.LanguageTest'
```

### UI-tree acceptance fixtures

The fixtures under `app/src/test/resources/ui_trees/` are independent samples;
the original acceptance set is synthetic, while `real/` contains explicitly
labelled sanitized captures and synthetic fallbacks (see `docs/evals/DATASET.md`).
They are not ordered scripts and production code must
not branch on their package names, case ids or sequence. The acceptance harness
feeds every sample through the real local safety gate, sanitizer, a read-only
planner fake, target resolver and scenario reducer:

```bash
./gradlew testDebugUnitTest \
  --tests 'com.screensaathi.evals.UniversalGuidanceAcceptanceTest'
```

To add a regression fixture:

1. Reproduce the screen on a development device and use the sanitized debug
   capture below. Never persist or print a raw Accessibility tree or take a screenshot.
2. Create a new, independent JSON sample containing only the minimum structural
   roles, synthetic labels, resource ids, state flags and approximate synthetic
   bounds required to reproduce the behavior.
3. Remove names, addresses, phone numbers, account/order contents, tokens and
   editable values. Replace the package/app label when its real identity is not
   needed for a named local safety rule.
4. Add the resource path to `UniversalGuidanceAcceptanceTest.FIXTURE_PATHS` and
   declare the expected local phase. Ordinary readable trees may reach exactly
   one `guide` target; auth, banking, payment and final-action trees must reach
   local handoff with zero planner calls; unusable trees must fail honestly.
5. If a production package exception is genuinely required, put it only in a
   named safety/compatibility policy and add a focused reason-bearing test. Do
   not add an app or screen-sequence branch to the guidance loop.

Canvas/OpenGL surfaces, secure windows and poorly semantic WebView or Compose
trees may expose no reliable control. The tree-only MVP does not guess and has
no screenshot fallback: it asks the user to scroll/continue manually or enters
safe handoff when local risk evidence is present.

### Sanitized captures from a physical device

Build/install the debug APK and keep the Accessibility service bound. From
`src/ScreenSaathi`, capture one independent screen (Python 3 and adb required):

```bash
./scripts/capture_ui_fixture.sh search-empty \
  app/src/test/resources/ui_trees/real/search-empty.json \
  'Поиск' 'Продолжить'
```

Every optional argument is an explicitly reviewed **static** UI label, including
a hint on a proven-empty editable field. Supply none by default. Never approve user/account/order content,
addresses, names, phone numbers, email, VINs, plate numbers, OTPs, passwords,
tokens, bank/payment data or other personal data, even if shown as static text.
Never capture or store audio. No screenshots, OCR or automatic actions are used.
Keep auth, banking, payment and final-action handling local; this tool never
calls the planner, STT or TTS, and never authorizes bypassing those boundaries.

The debug-only `FixtureExportReceiver` requires the shell/system DUMP permission.
It sanitizes entirely in memory before opening an output file under the app's
private `files/fixtures/`. Password nodes are omitted. Editable `text` and
`contentDescription` are always empty, including when explicitly approved.
An editable `hintText` survives only when explicitly approved and the reader
proves the original value empty (blank raw text, or Android showing exactly the
hint as text). Unknown or filled fields lose their hints. This local emptiness
flag is never a planner field or serialized fixture field.
All other text/hints/descriptions become `[REDACTED]` unless their complete value
is caller-approved; approved labels still pass through phone/email/long-number/
OTP redaction. Bounds, flags and retained clickable ancestry are preserved for
local replay. Resource IDs survive verbatim only from the codec's explicit,
short allowlist of generic Android dialog structure; class names survive only
from its fixed standard-role allowlist. All other external IDs/classes become
opaque encounter-order aliases **in memory before any file is opened**. Equal
IDs/classes retain equal aliases and distinct values remain distinct within a
fixture. Aliases contain no raw substrings, hashes or encoded personal values;
they are not identities for matching a later live tree. Static-label approval
cannot authorize an external ID or class name. Future live evaluations must use
the same safe alias trees and separate physical smoke checks. Review approved
labels before staging: pattern matching cannot identify every personal value.
The receiver logs only status, count and a safe private relative path; failures
never log the tree, arguments or exception content. The receiver and manifest
entry are absent from release builds.

The helper validates names (`[A-Za-z0-9][A-Za-z0-9_-]{0,63}`), removes stale
captures, checks broadcast success, then reads only the sanitized private file
with `adb exec-out run-as com.screensaathi`. Destinations must be `.json` files
inside `app/src/test/resources/ui_trees/real/` (resolved paths, including symlinks).
`--dry-run` permits any destination for validation but performs **no adb calls or
file writes**, for example:

```bash
./scripts/capture_ui_fixture.sh --dry-run search-empty /tmp/search-empty.json
./gradlew testDebugUnitTest --tests 'com.screensaathi.screen.UiTreeFixtureTest'
```

Review the sanitized file before committing it; never attach raw dumps to issues
or reports. Remove the debug private file with
`adb shell run-as com.screensaathi rm files/fixtures/search-empty.json` when done.
The version 2 schema is for local fixture replay only; `captureKind` is
`sanitized_real` or `synthetic`. Required strict metadata records source, UTC
capture date, device model, Android/app versions and a safe start-state slug.
The exporter derives technical values locally; never put account identity into
the capture name. Synthetic metadata uses `not_applicable` for device/date/version.
The decoder rejects version 1, raw schemas and unknown fields; recapture older
fixtures rather than silently inventing provenance. A null viewport is
an explicit legacy capture until the reader supplies exact application-window
bounds; do not infer live bounds from the union of nodes.

## Install and run on a device

**A physical Android device is required.** Accessibility services, overlay
windows and the microphone don't behave meaningfully on an emulator.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then, on the device: open ScreenSaathi → grant microphone → grant "display
over other apps" → enable the Accessibility Service → tap **Start assistant**.

## Route AI requests through the development computer

This is the normal live-AI setup for a physical phone connected over USB. The
phone does not need its own VPN and does not need root.

In terminal 1:

```bash
python3 scripts/openai_proxy.py
```

In terminal 2:

```bash
adb reverse tcp:8787 tcp:8787
adb reverse --list
```

Leave the proxy running while testing. `adb reverse` lasts only while the USB
device is connected (and may need to be repeated after reconnecting). The
proxy listens only on Mac loopback, so it is not exposed to the local network.

OpenAI sees the Mac's egress route, not the phone's. Enable the Mac VPN before
starting a live test and confirm that `api.openai.com` is routed through the
VPN. A `403` with `unsupported_country_region_territory` means the USB reverse
and local proxy are working, but the Mac is still exiting through an unsupported
region.

## Physical smoke baseline — Samsung Galaxy Note10+

Checked 2026-10-17 with APK commit `b49d85a` on serial `RF8MA183F5A`:

- Samsung SM-N975F, Android 12 / API 31, One UI 4.1.
- Overlay, microphone and Accessibility permissions were enabled; the overlay
  and screen-reader services were both bound after an in-place APK update.
- The phone reached the loopback development proxy through
  `adb reverse tcp:8787 tcp:8787`; with the Mac VPN active, OpenAI model,
  planner and TTS endpoints returned successfully.
- A command typed into the overlay completed the Android → proxy → planner →
  TTS path. MediaPlayer prepared, started and completed the returned audio.
- Using UI-tree data only (no screenshots), the resolver found and highlighted
  an ordinary visible control in Yandex Go.
- OpenAI's streamed WAV response used unknown RIFF/data lengths. Commit
  `b49d85a` finalises those lengths after download; the focused regression test
  and the physical playback both pass.
- Voice/STT scenarios and the Stop control remain manual acceptance checks by
  agreement with the tester; do not mark them passed from this baseline.
- On 2026-10-18 the Task 7 universal-loop APK was installed in place and the
  accessibility service remained bound. The tester then manually exercised the
  generic taxi-ordering guidance route and reported that the interaction works
  as intended. This is scenario evidence only: production code contains no
  taxi-specific sequence, package branch or screen identifier.
- The final Task 7 safety build treats visible controls such as `Заказать такси`
  and `Заказать еду` as possible final actions. They are never highlighted or
  turned into an imperative instruction; the loop hands control back locally.
  The same wording remains valid in the user's goal.
- Final debug APK installed on 2026-10-18: version `1.0` (`versionCode=1`),
  SHA-256 `28f0b910998eabe69e11c7016388a65d1533b912a65f63689e3e7a2123452286`.
  `adb reverse tcp:8787 tcp:8787` was active and `ScreenReaderService` remained
  enabled and bound after the in-place update.
- The Task 10 tree-only build, SHA-256
  `55b44a7920b4916b0cb7c5ef0547c5a1d1a52267be5404d6d8912faa323a4f4a`,
  was then installed in place. USB reverse remained active. Android reported
  accessibility `capabilities=1` (window-content retrieval only), confirming
  the installed service no longer requests screenshot capability.

## Final verification checkpoint — 2026-10-18

The verification candidate was built from commit `1132e44`. Its debug APK has
SHA-256
`394dd448f9598372dba25d609822725181132e45519bd2acda08b14ea246271f` and was
installed on Samsung `RF8MA183F5A` (SM-N975F, Android 12 / API 31, build
`N975FXXS9HWHA`). The Accessibility service is enabled and bound with
`capabilities=1`; USB reverse `tcp:8787 -> tcp:8787` is active and the Mac
gateway reports that its development key is configured.

Automated evidence for this candidate:

- A stopped, clean Gradle process completed `clean testDebugUnitTest
  assembleDebug`. A forced non-cached unit run then executed 331 tests with no
  failures or errors; the three opt-in live tests were skipped as designed and
  golden replay remained 8/8.
- With the Mac VPN enabled, five live question calls and five live guidance
  calls all succeeded through the production OpenAI route. Across the 15
  latency samples, p50 was 2239 ms and p95 was 3212 ms, below the 10-second
  acceptance limit. The five evidence-free samples all failed closed.
- The 15-case live safety evaluation produced zero device actions, zero
  unauthorised actions and zero critical failures, with p50 2169 ms. Six
  ambiguous, missing-evidence or adversarial cases returned no plan; the
  legacy report labels those `MAJOR`, but they are expected safe refusals and
  the test passed.
- A static audit found no `performAction`, gesture dispatch or
  `ACTION_SET_TEXT` in production. Text setters affect only the app's own UI.
  The old screenshot and vision compatibility types are unconstructed and are
  not retained or invoked by the scenario loop. Planner action-shaped output
  is downgraded to guidance; an app launch can occur only after the user
  selects an exact locally discovered, enabled launchable-app card.

Physical evidence is deliberately narrower than the automated evidence. The
tester manually completed the generic taxi-ordering guidance scenario on the
Samsung and reported that it works and that they like the interaction. That is
one user acceptance result for the app-agnostic UI-tree loop; it is not five
consecutive runs of every required route and it does not justify a taxi-specific
branch. The exact candidate was installed while the phone was on its secure
lock screen, so no PIN was entered and no additional UI interaction was
claimed.

The following acceptance work remains open before calling the MVP pilot-ready:

- five consecutive runs of every required Samsung route and the recovery
  matrix;
- manual voice/STT and Stop checks by the tester;
- the same route matrix on physical Pixel/clean-Android and Xiaomi/Redmi
  devices (the connected Android emulator does not satisfy this requirement);
- migration of the pilot build from the temporary OpenAI/Mac development route
  to DeepSeek and Yandex Kit. RuStore publication remains the product owner's
  separate responsibility.

The device previously had an APK signed with a different key. It required a
one-time uninstall before this debug build could be installed, so the old
package's local data and permissions were not retained.

## Debugging on-device

```bash
# Everything from our own process
adb logcat -d --pid=$(adb shell pidof com.screensaathi | tr -d '\r')

# Target resolution decisions
adb logcat -s TargetResolver:*

# Session/turn/highlight state
adb logcat -s SessionController:*

# OpenAI planner/STT/TTS transport errors from this process
adb logcat -d --pid=$(adb shell pidof com.screensaathi | tr -d '\r') | grep -E "Planner|Stt|Tts|OpenAI"

# Confirm exactly 2 overlay windows (highlight layer + pill) — more means a
# duplicate registration, fewer means the service isn't running
adb shell dumpsys window windows | grep -c "Window{.*com.screensaathi}"

# Confirm the accessibility service is bound and hasn't crashed
adb shell dumpsys accessibility | grep -E "Bound services|Crashed services"

# Real window position/frame (never guess from a screenshot — see
# docs/TROUBLESHOOTING.md for why)
adb shell dumpsys window windows | grep -A20 "Window{.*com.screensaathi}:" | grep -E "Window #|frame=|mAttrs="

# Manual highlight trigger (exercises the same resolver/overlay the voice
# path uses; only STT is skipped)
adb shell am broadcast -n com.screensaathi/.HighlightReceiver \
  -a com.screensaathi.HIGHLIGHT --es query "Where to"

# Dump what the resolver currently sees on screen
adb shell am broadcast -n com.screensaathi/.HighlightReceiver \
  -a com.screensaathi.DUMP_SCREEN
```

## Dangerous areas — measure before changing

See `docs/DECISIONS.md` for the full reasoning. Short version: don't touch
`TargetResolver`, `ScreenReaderService`, `SessionController`'s turn/generation
logic, `HighlightView`'s clear-vs-animate split, or `SafetyGuard` without
reproducing a real, measured failure first — several of the choices in those
files fixed specific bugs that don't announce themselves in a screenshot.

**Never `am force-stop com.screensaathi` during testing.** It disables the
Accessibility Service, which silently invalidates everything downstream —
this produced multiple false "bug" reports during development that were
actually test-harness artifacts. Only force-stop *other* apps (Uber, PhonePe)
to reset their state.

## Release process

1. Bump version if applicable, run `testDebugUnitTest` clean.
2. `./gradlew assembleDebug`, note the commit SHA the build came from.
3. `sha256sum app/build/outputs/apk/debug/app-debug.apk`.
4. Install the exact built APK on a device and re-run the scenarios in
   `docs/DEMO.md`. Don't ship an APK you haven't installed.
5. `gh release create vX.Y.Z --target <sha> --title "..." --notes-file ...`,
   attach the APK and its checksum.
6. Confirm from a **fresh clone** (not the working directory) that the same
   commit builds and the release asset downloads and installs correctly.
