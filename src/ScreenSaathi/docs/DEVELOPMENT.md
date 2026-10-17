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

# OpenAI planner/STT/TTS transport errors
adb logcat -s SarvamPlanner:* SarvamStt:* SarvamTts:*

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
