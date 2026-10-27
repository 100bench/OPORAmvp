# Task 3 report — actionable targets and typed recovery

Status: DONE_WITH_CONCERNS (physical target scenario unavailable).

## Scope and measured reason

Implemented the approved Task 3 brief from baseline `50ae682`. The supplied physical failure was a Samsung application viewport ending at y=2153 while the selected node began at y=2157, placing guidance over bottom/system controls. A synthetic regression reproduces those measured bounds without retaining personal UI data.

Read ScreenSaathi AGENTS.md and its architecture, decisions, development, troubleshooting and roadmap references. Used test-driven-development and verification-before-completion workflows; no subagents were spawned.

## Changes

- `screen/TargetResolver.kt`: all natural-language, exact planner-label and exact resource-ID routes share strict `eligibility(viewportOrLegacy)`. Semantic labels normalize to their explicit clickable ancestor before ranking and ambiguity. Existing conservative nearby/containing editable-field association remains; ungrounded static labels are no longer highlighted. Candidates deduplicate by final index, with equal overlapping bounds as a conservative fallback. Distinct controls remain ambiguous, including partial overlaps. Existing semantic scores and ambiguity margin are retained.
- Resolver outcomes now distinguish `NotVisible`, `NotActionable`, `Ambiguous` and `NotFound`. Disabled/password elements cannot become targets. An explicitly invalid ancestor cannot fall through to an unrelated field.
- `session/ScenarioModels.kt`, `ScenarioReducer.kt`: typed `TargetUnavailable` event and `ShowTargetIssue` effect. Among target-resolution issues, only NOT_VISIBLE enters bounded manual-scroll recovery; other issues clear guidance and allow rephrase/retry. Existing unrelated timeout/unreadable-tree recovery is retained.
- `session/UniversalGuidanceLoop.kt`: consumes typed resolution instead of nullable geometry. Both ID and text paths normalize through the shared resolver. Planner text must be an exact sanitized label, and filled editable values remain excluded. A local-only sanitized projection retains offscreen labels for diagnosis, while final eligibility always uses original live visibility and viewport. Provider requests still use the normal sanitized viewport-filtered boundary.
- `session/SessionController.kt`: four short distinct Russian messages and appropriate clarification/retry controls, no false target rendering and no nullable-target generic-scroll fallback message. Direct debug-query handling also recognizes the new outcomes.
- `screen/ScreenSnapshot.kt` and loop freshness digest: parent-approved extension discovered during self-review. Viewport-only changes now invalidate rendered/cached/in-flight geometry even when labels are unchanged. Generation checks and bounded stale-response replanning remain unchanged.
- Three focused test classes updated: resolver, reducer and guidance loop. Old static-row expectations were deliberately tightened; old absent-target scroll tests now expect typed rephrase/retry, while the bounded-scroll test uses an actually offscreen target. Two old Rect constructor fixtures were changed to explicit coordinate assignment because the Android unit-test stub constructors retain no geometry.

## RED evidence

1. `./gradlew testDebugUnitTest --tests 'com.screensaathi.screen.TargetResolverTest'`: 17 tests, 3 failures before resolver implementation: measured Samsung offscreen node returned Found, clipped/disabled eligibility was ignored, and two separate labels sharing an ancestor were incorrectly ambiguous.
2. Resolver GREEN then loop typed-recovery RED: 18 tests, 1 failure in the new ID/text unavailable matrix because no typed issue was emitted. Matrix covers NOT_VISIBLE, disabled/static NOT_ACTIONABLE, distinct-control AMBIGUOUS and absent NOT_FOUND through both routes.
3. Typed reducer interface introduced with no-op handler, then its recovery test failed at missing ClearHighlight before reducer behavior was implemented.
4. Additional regression for disabled exact planner label plus an active loose-prefix control failed before adding exact-label resolution; it now refuses the unrelated control.
5. Parent requested viewport-only freshness coverage: both tests failed before including viewport in signature/localIdentity. Coverage includes cached confirmation, already-rendered guidance, and a response arriving after viewport shrink. Bounded stale replanning is asserted.

Intermediate focused failures in five legacy loop tests were expected obsolete scroll-phase assertions; they were updated to the approved typed behavior. No unexplained failure remains.

## GREEN and build evidence

- Baseline `./gradlew testDebugUnitTest assembleDebug`: successful (up-to-date).
- Final focused resolver/reducer/loop/acceptance run: successful, 77 tests. Includes acceptance app-agnostic replay, stale response and Stop generation checks, safety short circuits, raw-value exclusion and all new regressions.
- Final full `./gradlew testDebugUnitTest assembleDebug`: successful. XML results: **417 tests, 0 failures, 0 errors, 3 skipped** (opt-in live tests). After the initial full run (415 tests), the parent-approved viewport fix justified repeating full verification.
- `git diff --check`: clean.
- Fresh empty clone of baseline plus the exact tracked Task 3 working patch, with only SDK path and empty API key in local.properties: `assembleDebug` successful. Repeated with `--no-build-cache --rerun-tasks` to ensure actual compilation without inherited task/build outputs. The clone is `/var/folders/r6/qn9gzpfn2mzg3zms8jxjtg700000gn/T/opora-task3-fresh-tc1_y5sh/repo`.
- Build logs: `/tmp/opora-task3-focused.log`, `/tmp/opora-task3-full-final.log`, `/tmp/opora-task3-fresh-build-final.log`.
- Default sandbox could not write Gradle wrapper locks and could not access adb. Required narrowly scoped escalations succeeded; no automatic approval rejection occurred.

## Physical evidence and limitations

- Connected physical Samsung SM-N975F, serial RF8MA183F5A; before install foreground was Samsung launcher, not the requested Yandex Go address screen.
- Installed the final tested APK in place with `adb -s RF8MA183F5A install -r ...`: **Success**.
- Exact APK SHA-256: `07b6b54ce4b053450fd006dc704c23a52b7c0638d2d3dbafcf063922ce0e4c4c`.
- After installation, Accessibility remained enabled and bound; service reported `capabilities=1` (window-content retrieval only). No settings changes or force-stop were performed.
- A temporary fixture exported via the existing sanitized debug exporter successfully contained 45 structural nodes and viewport [0,0,1080,2280]. Only the approved generic label “Настройки” was allowed, and zero such labels were present. No raw UI tree, personal values, screenshot, OCR or audio was read or persisted. Local and device temporary fixture files were removed.
- Foreground then reported NotificationShade, and window policy reported keyguard `showing=true`, `occluded=false`. The phone was not unlocked or bypassed. No tap, typing, scrolling, app navigation or synthetic action was performed.
- **Not verified physically:** address-field highlighting, the measured offscreen failure after the fix, user-visible recovery messages, manual target taps, voice/STT/TTS, or any full route matrix. Installation/binding/capture evidence is not claimed as scenario acceptance. The user must open the requested ordinary screen to complete the same-device scenario check.

## Self-review and follow-up

- No package-specific routes, fixed target coordinates, automatic actions, screenshot fallback or new raw/personal-value logs. Fixture codec/privacy boundary and SafetyGuard remain untouched.
- Scoring operates on normalized usable controls. Disabled/offscreen matches cannot participate in ambiguity against a usable target. Equal-geometry deduplication is intentionally conservative; partial overlaps alone do not establish equivalence.
- Exact resource IDs are resolved locally using the same eligibility and normalization. If an existing ID identifies an unavailable control, its typed issue is preserved rather than silently substituted with a different text target.
- Task 5 overlap deliberately avoided: a valid local miss still receives the existing one format-repair planner call. Typed issue is dispatched after that bounded retry. Task 5 should remove this duplicate call and adjust call-count assertions (including viewport in-flight test's documented 3 calls).
- Existing `TargetNotFound` recovery for unreadable/unsettled/churning trees and the physical-tap timeout still use their pre-existing manual recovery. They are distinct from the new typed target-resolution failures and were not redesigned here.
- Main concern is missing same-device scenario reproduction after install; it is explicitly blocked by device foreground/keyguard state, not by automated failures.
