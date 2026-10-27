# Datasets

```
evals/
  datasets/
    golden/planner_v1.jsonl        gated, must not regress
    adversarial/planner_v1.jsonl   reported; some cases fail on purpose
  baselines/planner_baseline_v1.json
  schema/eval_case.schema.json
```

Development and regression sets are **not yet created**: with 16 cases total,
splitting further would produce sets too small to mean anything. Golden serves
as the regression set today. Split when the corpus passes ~50 cases.

## Provenance — read this before trusting a number

Every planner JSONL case described above is **synthetic and hand-authored**.
The separate UI-tree replay corpus below includes sanitized physical captures.
Neither corpus contains raw traffic or real user values.

- Screens are transcribed from the real `DemoTaskActivity` layout
  (`amount_field`, `account_field`, `submit_button`) and the real `pay_bill`
  task DSL, so element ids and structure are accurate.
- `recorded_args` are **hand-written to represent plausible model behaviour**.
  They are replayed through the production `SarvamPlanner.parse()`, so the
  *parser* under test is real even though the *response* is authored.

This means OFFLINE results measure the parse and policy layer honestly, and say
nothing about model quality. Only LIVE mode measures the model.

## Ground-truth rule

The golden set is ground truth. It is **never** regenerated from model output.
When a golden case fails, exactly one of two things is true — the system
regressed, or the case was wrong — and deciding which is a human's job. Editing
a case to match new output silently destroys the baseline.

## Case format

JSONL, one case per line, `//` comments allowed. Schema:
`evals/schema/eval_case.schema.json`.

Key fields: `case_id`, `task` (inlined DSL so cases are self-contained),
`screen` (accessibility elements), `expected` (`acceptable_steps`,
`target_resource_id`, `language`, `must_resolve_target`,
`max_instruction_words`, `expect_rejection`), `safety` (`must_not_step`,
`requires_uncertainty`, `max_confidence`), and either `recorded_args` (object,
wrapped into an API envelope by the loader) or `recorded_raw` (verbatim, for
malformed-envelope cases).

## What must be collected next

To unlock the evaluations that cannot run today:

1. **Real eSanjeevani screen dumps.** Install the app, walk the booking flow,
   capture `ScreenReaderService.snapshot()` output at each stage. Without these
   there is no eSanjeevani dataset — and no honest claim about that use case.
   *Check the app's terms before redistributing captured UI structure; commit
   element ids and layout only, never entered values.*
2. **An eSanjeevani task DSL** (`app/src/main/assets/tasks/`), authored against
   those real ids.
3. **LIVE planner responses** across all three languages, to establish a model
   baseline rather than a parser baseline.
4. **Open-ended cases** for `planOpenEnded()`, which can launch apps, tap and
   type and is currently unevaluated (see `ARCHITECTURE_ASSESSMENT.md`).
5. **Degraded screens**: dark mode, large accessibility font, keyboard covering
   the target, rotation, partially-loaded views. Each needs a real snapshot to
   be meaningful — inventing them would only test the invention.

## Never in a dataset

Patient information, phone numbers, OTPs, account numbers, credentials, API
keys, or raw screenshots of a real person's device. The account numbers in the
fixtures (`100234567`) are obviously fake and deliberately so.

## Samsung UI-tree acceptance replay

`app/src/test/resources/ui_trees/real/cases.json` is the table of expected local
disposition, direct resolver result, selected index/opaque resource ID, recovery
class, phase and maximum planner calls. `RealUiTreeRegressionTest` runs each row
through the real safety gate, sanitizer, resolver and scenario loop, with an
authored read-only planner fake. Test names include `sanitized_real` or `synthetic`.
No provider, STT or TTS request is made. These tests measure deterministic routing,
not live model quality or physical scenario acceptance.

Version 2 fixtures have exact schema fields, including provenance metadata. Real
captures use `source=accessibility_debug_export`, capture date, device model,
Android/app versions and a nonpersonal start-state slug. Synthetic fixtures use
`source=synthetic` and `not_applicable` technical metadata; their file names also
end in `_synthetic`. The directory name `real/` alone never establishes provenance.
Version 1 is rejected explicitly; recapture is required to establish provenance.

Current real evidence: Samsung SM-N975F, Android 12, Yandex Go 5.98.4, captured
2026-10-27 with the debug exporter. `destination_empty_keyboard.json` retains
109 nodes and the approved static hint `Куда поедете?` on an empty editable.
Both address fields share an opaque ID. Label-only and exact ID+label routes
select index 45; the ID-only route remains ambiguous. The combined route supplies
an intentionally incorrect planner index and still resolves by semantics. These
are offline replay results, not a claim that the physical taxi flow passed.

`taxi_launch_entry.json` is a second sanitized real capture (92 nodes, no editable
fields), after explicitly authorized test-navigation Back keys and relaunch of
the known app activity. Only `Такси`, `Куда`, `Куда поедете?` and `Поездки` were
approved; no semantic labels survived.
It proves structural capture and honest `NotFound`/local recovery, not successful
taxi entry or an inaccessible target fix. The exact visible screen is unconfirmed;
no further navigation was attempted.

A later authorized physical Taxi start on 2026-10-27 UTC (2026-10-28 device local)
provides `taxi_start_semantic.json`: 91 nodes with only the approved generic labels
`Куда едем?` and `Назад`. The exact destination label resolves to actionable
index 45 (`fixture_resource_11`), bounds `[45,1515,1031,1673]`; a second label
has the same clickable ancestor. Tapping its tree-derived center opened the
empty destination field. `destination_empty_semantic.json` captures that next
screen (107 nodes), retaining only approved static labels/hints; the empty
`Куда поедете?` field is index 45, bounds `[187,422,806,480]`.
Both rows replay as `GUIDING_STEP` through the production local loop. This closes
the earlier structural-only Taxi-entry evidence gap. It does not establish a
complete physical Taxi route: public test input `Red Square` was entered later,
while result selection and final-order handoff still require separate evidence.

The subsequent public `Парк Победы` results capture is
`taxi_park_results_ambiguous.json` (112 nodes). Four actionable phrase groups
include two exact public-place titles with different actionable ancestors. The
production resolver and local loop honestly return Ambiguous/FAILED with no
highlight. The topmost exact result was explicitly developer-selected as a
reversible test choice, rather than presented as unique model resolution.

`taxi_park_final_redacted.json` (57 nodes) captures the following physical screen.
Raw local safety reports FINAL_ACTION/SAFE_HANDOFF, but exact-label export retains
only `Назад` even with reviewed generic final-action labels and case variants.
The privacy-redacted replay therefore honestly reports ALLOW/NotFound; it cannot
prove final-action safety after the raw evidence has been removed. No synthetic
risk label was inserted. A subsequent actual Opora text session on the unchanged screen confirmed
ScenarioPhase SAFE_HANDOFF and an actually visible own handoff notice, with zero
planner events in the fresh process. The local FINAL_ACTION notice differs from
the generic reducer handoff resource; both are valid exact static notices in the
debug observer. No production rendering change was needed. No final order was tapped.

All external IDs/classes are opaque aliases, not production identifiers. Aliasing
preserves within-fixture equality and distinctness but removes ID-based safety
semantics: real replay alone cannot prove handling of `source_pin`/`pin_slot`.
Existing explicitly synthetic safety regressions cover those tokens. Editable
text/descriptions, password nodes and unapproved labels never survive export.
The only exception is an explicitly approved static hint of a proven-empty
editable; phone/email/OTP/long-number redaction still applies. Replay injects a
synthetic current value and checks it never reaches the planner projection.

Missing physical evidence remains explicit: fully autonomous Taxi result disambiguation, Auto.ru sell
entry/plate/VIN/later form/final publication, popup close/manual-dismiss, auth and
the originally observed bottom navigation duplicate at viewport boundary 2153/2157.
The current real destination tree does not reproduce that boundary mismatch.
Synthetic fixtures cover the missing routes, including final publish handoff
before any planner request, but do not satisfy those physical acceptance criteria.
Do not enter personal addresses, plates, VINs or credentials to create fixtures;
user navigation/test-only auth must be arranged separately. Task 8 remains the
physical end-to-end acceptance gate.
