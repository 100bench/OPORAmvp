# Opora auto.ru visible plate target plan

**Goal:** On a tree-readable form like auto.ru's visible «Госномер» section, Opora points to the relevant input. On the subsequent VIN screen, an explanatory mention of «госномер» must not cause a misleading ring; uncertain targets leave the screen unhighlighted with manual guidance.

**Scope:** This replaces the previously planned auto.ru registration pause. Keep OpenAI through the Mac proxy, UI-tree-only perception, no automatic taps or typing, and no plate values in logs or fixtures. Preserve the current auto.ru screen during device tests.

## Measured evidence on Samsung

- Foreground activity remains `ru.auto.ara/.SimpleFragmentActivity`; UIAutomator exposes two visible EditText controls with empty IDs below a separate static «Госномер» label. Opora's service reports a settled snapshot with 20 elements and a nonclickable `TextView` for that label.
- A text request reached the existing proxy and got HTTP 200. A temporary local diagnostic reported `unresolved_target idPresent=true textPresent=false`: the planner supplied an ID that did not resolve to a visible element, and the loop immediately emitted the generic manual-scroll message.
- Another attempt reported `unreadable_before settled=false count=20`. Temporary event diagnostics observed Opora's own overlay sending screen-change events that can reset the 250 ms settle timer. These diagnostics are temporary and must be removed.
- The auto.ru form later changed to VIN. A local `HIGHLIGHT Госномер` diagnostic selected an explanatory TextView mentioning both VIN and госномер, so the ring appeared in the wrong place. It was cleared immediately. No plate or VIN values were read or saved.

## Implementation

1. Add failing tests: an invented planner ID gets one bounded repair request; a second invalid target still fails safely; a grounded static label directly above one editable field resolves to that field; ambiguous or distant fields are not guessed.
2. Ignore only Opora **overlay** content/focus/click events for app-screen settling and replanning. Preserve events from the same-package demo Activity, external apps, and system windows. Do not change tree extraction or leak editable values.
3. Strengthen repair prompting: IDs must match the provided element list exactly; if absent, return an exact visible label. On an unresolved target, replan at most once against a fresh safe snapshot, then use existing manual recovery.
4. Associate an exact, short, noninteractive field label with one nearby visible editable field only when geometry is unambiguous. Reject loose matches in long explanatory text and unclear field associations, while keeping unrelated bare text rows targetable. Log metadata only. Keep target bounds local. Do not hardcode auto.ru, «Госномер», or a screen sequence.
5. Replace the generic scroll-only recovery copy with a no-ring instruction to check labels, scroll or fill the field manually, and continue when the screen changes.
6. Remove temporary trace/action code, run focused and full tests, build, install on Samsung, and repeat the text request while keeping auto.ru open. Confirm HTTP 200, a real highlighted editable field on the plate form, and no misleading ring on the VIN form. Record any remaining visual check for the user.

## Verification notes

- Full `testDebugUnitTest assembleDebug` passed after the safe-label and actionable-ID checks. Tests cover a real field label, VIN explanatory text (including an exact full-text planner target), invented IDs, filled editable values, and an unrelated static row above a search field.
- The plate form was observed on Samsung before the final VIN refinement: a text request received an OpenAI response and highlighted the plate input; the user confirmed that guidance worked. This exact plate screen was no longer available for a second physical run after the refinement.
- On the current VIN form, a temporary debug-only text entry exercised the real planner through the existing Mac proxy and ended with no ring. The proxy returned HTTP 200. The debug entry and probe log were removed, then a clean APK was installed.
- With the clean APK on the same VIN form, `HIGHLIGHT Госномер` left the highlight overlay invisible (`mViewVisibility=0x8`). The diagnostic request was cleared afterward. No screen images, field values, VIN, or plate number were collected for these checks.
