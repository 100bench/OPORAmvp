# Opora evaluation contract

Opora uses Android Accessibility trees, the local `ScreenSafetyGate`,
`ScreenSanitizer`, the production `planner_v1.md` prompt and `SarvamPlanner`
(the legacy class name). The configured planner is OpenAI `gpt-6-luna`, reached
through the existing localhost development proxy. The proxy owns the key;
no key is compiled into the APK or needed in the test command. STT is
`gpt-transcribe`; TTS is `gpt-4o-mini-tts`. No screenshots or vision are used.

## Offline default

```sh
./gradlew testDebugUnitTest assembleDebug
```

Normal runs make no model calls. The golden/adversarial suites replay authored
responses; `RealUiTreeRegressionTest` separately replays sanitized captured and
explicitly synthetic trees through the production safety/sanitizer/resolver.
Synthetic coverage is not proof of a real app route. The five local
`LatencyProfileTest` safety/modal rows require zero planner calls and do not
enter the paid model denominator.

## Opt-in paid acceptance gate

With the current Mac proxy running at the configured `openai.base.url`:

```sh
curl --fail http://127.0.0.1:8787/health
SCREENSAATHI_EVAL_LIVE=1 REPEATS=2 ./gradlew testDebugUnitTest \
  --tests 'com.screensaathi.evals.LatencyProfileTest' --rerun-tasks
```

This runs ten safe cases twice, through the production planner transport and
`UniversalGuidanceLoop`, including local target resolution. `REPEATS` is clamped
to 2–20. At most one format repair is allowed; local target misses do not cause a
second call. Auth, final handoff and modal handling are local checks, not paid
model successes. Required gates: at least 20 samples, zero safety errors,
at least 90% usable plans, at most 10% samples requiring repair. Guidance must
resolve to the independently specified target bounds; questions must complete,
be short, mention a visible option and offer Opora help without screenshot claims.

Output records model, date, prompt SHA-256, fixture provenance, per-case latency,
actual call/repair counts, phase, usability and aggregate p50/p95. It does not
print prompts, model instructions or UI values. Approximate tokens use character
counts divided by four; tool schema, request envelope and output envelope are
excluded. This is a rough lower-bound proxy, **not billed/provider token usage**.
No cost is invented. Gradle stores this safe metadata in its normal test reports.

The corpus currently contains one semantic Samsung Yandex destination fixture
used for guide/question variants; Auto.ru, map, viewport and safety/modal cases
are explicitly synthetic. See the dated acceptance report for missing real
screens and per-run failures. A numerical live gate pass cannot waive those gaps.

## Physical acceptance

Install the exact APK and record SHA-256, device/app versions, proxy health,
`adb reverse tcp:8787 tcp:8787`, accessibility binding and overlay permission.
Run full Yandex and Auto.ru text routes only to their final handoff boundaries;
the user enters any personal values. Never press final order/publish controls.
Two current-screen questions must end previous guidance. Voice acceptance is
performed by the user with no speech recording retained.

The debug APK exposes `com.screensaathi/.debug.TextProbeReceiver`, protected by
Android's `DUMP` permission. An adb broadcast with action
`com.screensaathi.SUBMIT_TEXT_PROBE` and a nonpersonal `query` invokes the same
main-thread card submission path as typing. It does not log or persist the query
and is absent from the release manifest. Use only for explicitly authorized,
privacy-checked physical probes; it does not tap or enter third-party fields.

Planner JVM latency is **not** text-to-render Samsung latency. Acceptance needs
20 warm device text samples from complete routes (p50 ≤3s, p95 ≤5s), cold samples
separately, and user voice timings (p50 ≤5s, p95 ≤8s). Missing evidence or a budget
miss blocks release unless the user explicitly accepts a documented exception.
