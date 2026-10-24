# Opora Address Screen Safety Plan

**Goal:** An address field in Yandex Go remains available for step guidance, while visible password, PIN, SMS-code, bank, payment, and final-order screens still stop before a network call.

**Spec:** `docs/superpowers/specs/2026-10-24-opora-scenario-stabilization-design.md`, task 2.

**Constraints:** Accessibility UI tree only. Do not record an address or a raw screen tree. Do not introduce app-specific screen sequences. Keep the safety gate local and deterministic.

## 1. Identify the trigger on Samsung

- Open Yandex Go → Taxi → address entry, with no address typed.
- Evaluate the same live `ScreenSnapshot` that `UniversalGuidanceLoop` uses. Record only the rule category, matched field kind (`text`, `hint`, `contentDescription`, `resourceId`, `password`, or `inputType`), element visibility, editability, and the technical resource ID when needed to distinguish map controls. Do not log field values or the raw tree.
- Confirm whether the pause comes from a hidden node, map `pin` wording, or another visible control. If the exact state cannot be reproduced, retain this as an unverified hypothesis and do not claim the 15:32 incident explained.

## 2. Reproduce in focused tests

**File:** `app/src/test/java/com/screensaathi/session/ScreenSafetyGateTest.kt`

- Add a visible address field with a hidden auth node and assert `ALLOW`.
- Add a map pin or address selector with non-editable `resourceId` containing `pin` and assert `ALLOW` if the live trigger proves that case.
- Retain visible password and numeric password tests; add visible OTP/PIN field and visible SMS-code text tests.
- Run the focused test and observe the new address case fail before changing the gate.

## 3. Apply the narrow gate correction

**File:** `app/src/main/java/com/screensaathi/session/ScreenSafetyGate.kt`

- Limit label and input-type checks to `visibleToUser` elements, matching the screen sanitizer's visibility boundary.
- If the live match is an ambiguous `pin` resource ID, distinguish route markers from PIN entry using the element role and visible address-field context; keep explicit visible auth language and password input flags effective.
- Preserve the separate bank, financial, and final-action branches.
- Update the local explanation only if the screen is genuinely a data-entry pause; do not tell the user an address is a secret.

## 4. Verify and commit

- Run the focused safety tests and complete unit tests; assemble and install the APK.
- Reopen the same Yandex Go address surface on Samsung. A text request must guide the address field without an auth pause, while a synthetic visible password/OTP surface still pauses locally before the proxy is called.
- Keep exact matched values and any personal address out of logs, test fixtures, and commits.
- Commit the correction separately from other scenario work.

## Current verification state (24 October 2026)

- Regression tests now cover a visible address with hidden auth controls, `Map pin`, `source_pin` and `pin_slot`, an ID-only final payment button, and visible English/Russian PIN prompts. A `pin_slot` without both an enabled editable address field and a visible route marker remains blocked. All tests and the APK build pass.
- On Samsung, the live address screen had two `address_input` fields. Temporary diagnostics identified two non-editable map controls, `source_pin` and `pin_slot`, as consecutive false auth triggers. No address value or raw tree was saved.
- The final APK without diagnostics is installed. After the narrower safety review fix, a text request from the overlay on that same screen reached the existing Mac OpenAI proxy (HTTP 200) and moved to `GUIDING`, with no auth pause. ADB entered an English equivalent because its `input text` command did not accept Cyrillic; the user checks spoken wording separately.

