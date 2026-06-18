# Implementation Plan — Rotation Capacity Fixes

_Drafted 2026-06-18 for review before any code changes._

## Problem

The generated schedules (v1/v2/v3) violate hard per-block capacity limits because the
app's config and model don't encode them correctly. Validated against the real-world
schedule. Two distinct defects:

1. **Wrong max caps** — `rotation_config.max_per_week` is too high:
   ICU=2 (→ should be 1 categorical), Broadlawns=2 (→ total ≤ 2), VA=3 (→ 2 categorical),
   Younker 7 Days=2 (the *total* is right, but coverage is under-filled — see #2).
2. **Younker 7 Days under-coverage** — the rule is **≥ 2 total every block**, supplied by
   the BMC group statically. But the solver *excludes* BMC filler coverage during the
   solve (`buildFillerExclusions`), so it sees Y7D as needing only the config min and
   leaves it at 1 in 22/26 blocks.

## Confirmed rules (from the real schedule + owner)

| Rotation | Rule | Scope |
|---|---|---|
| **ICU** | categorical ≤ 1; total (cat+TY) ≤ 2 | categorical-only cap + total cap |
| **VA** | categorical ≤ 2 (never 3) | categorical-only cap |
| **Broadlawns** | categorical + TY ≤ 2 total | total cap (no separate cat cap needed) |
| **Younker 7 Days** | ≥ 2 total every block; BMC supplies the static 2nd body **except block 7 and block 13** | static aux coverage + carve-outs |

**Younker 7 Days carve-outs:**
- Blocks 1–6, 8–12 (4-week blocks): **BMC provides +1** → solver needs only 1 categorical.
- Block 7: **a TY supplies the 2nd** (no BMC that block).
- Block 13: **a categorical supplies the 2nd** → block 13 needs **2 categoricals** on Y7D.

### Slot ↔ block mapping (critical, must verify in code)
The model uses 26 **slots** (2 weeks each); `block_number` is 1-based → slot = `block_number − 1`.
A "block" in the rules is a **4-week block = 2 slots**:
- **Block 7** = slots **12, 13** (labels 7A, 7B)
- **Block 13** = slots **24, 25** (labels 13A, 13B)

## Design

### Part A — Categorical-only max caps (new mechanism)
The existing `applyCoverageConstraints` enforces `Σ categorical_occ ≤ maxPerBlock − auxCount`
— an aux-aware **total** cap. We need a separate **categorical-only** cap that ignores aux.

- **Add** `RotationPolicy.categoricalMaxPerBlock` (int, default 0 = "no separate cap").
- **Add** `ConstraintBuilder.applyCategoricalCapConstraints()`: for each rotation with
  `categoricalMaxPerBlock > 0`, add `Σ categorical_occ[b] ≤ categoricalMaxPerBlock` for
  every slot b. (Categoricals = the non-auxiliary residents already passed in `residents`.)
- **Call** it from the model-build path alongside the other constraint steps (register in
  `orderedConstraintSteps()` so it's part of base model + diagnostics).
- **Config:** new schedule_config key `categorical_max_per_block_ids` is overkill; instead
  store per-rotation via the existing `rotation_config` table. Add column
  `categorical_max_per_block` (default 0) + DAO load/save, mirroring `max_per_week`.

Set values: ICU → 1, VA → 2. (Broadlawns left at 0 — its rule is total ≤ 2, handled by Part B.)

### Part B — Correct the total max caps (config values)
Fix `rotation_config.max_per_week` (stored value is the TOTAL per-block cap):
- ICU: 2 → **2** (total cat+TY ≤ 2; the categorical≤1 is Part A) — *already 2, leave.*
- Broadlawns: 2 → **2** (total ≤ 2) — *already 2, leave.*
- VA: 3 → **2** (and categorical ≤ 2 via Part A; TY rarely present) —
  **NOTE:** real schedule shows VA total never exceeds 2, and the rule is *categorical* ≤ 2.
  Set total cap to 2 to be safe; confirm TY-on-VA never needs a 3rd slot. (Real data: VA
  cat+TY max = 2, so total ≤ 2 is correct.)
- Younker 7 Days: leave total cap at 2; coverage handled in Part C.

So Part B is effectively just **VA: 3 → 2**. (ICU/BMC totals already 2.)

### Part C — Younker 7 Days static BMC coverage + carve-outs
The cleanest fix that matches reality: **credit BMC's Y7D coverage during the solve** for
the blocks where BMC is actually present, and require the solver to cover the rest.

Option C1 (recommended — data-driven, least special-casing):
- **Stop excluding** the BMC→Y7D filler from the solve-time aux pre-count *for the slots
  where BMC is genuinely on Y7D*. Concretely: ensure the DB's aux assignments for the BMC
  group reflect BMC on Y7D for blocks 1–6, 8–12 (slots 0–11, 14–23), absent at block 7
  (slots 12–13) and block 13 (slots 24–25). Then `getAuxiliaryCoverage` naturally returns
  Y7D aux=1 for those slots and 0 for blocks 7 & 13.
- Set Y7D `min_per_week = 2` (total floor). With aux=1 on the covered blocks, the
  aux-aware `effectiveMin = 2 − 1 = 1` → solver puts 1 categorical (correct). On block 7,
  aux includes a TY (=1) → effectiveMin = 1 → 1 categorical (correct, 2 total with TY).
  On block 13, aux=0 → effectiveMin = 2 → **2 categoricals** (correct per rule).
- This requires the BMC group's Y7D assignments to exist in the DB aux data with the right
  slot pattern, and removing Y7D from `fillerExclusions` (or making the exclusion
  slot-aware). **Verify** current BMC aux rows before deciding how much to adjust.

Option C2 (more explicit, if C1's data is messy):
- Hard-code a `staticAuxCoverage` map (rotationId → slot → count) injected into
  `auxCoverage` for Y7D: +1 for slots 0–11 and 14–23, +0 for 12–13 and 24–25. Keep
  `min_per_week = 2`. Same solver effect, independent of BMC DB rows.

**Decision needed:** C1 (fix the data) vs C2 (inject static coverage in code). C1 is more
faithful but depends on BMC aux rows; C2 is robust and self-contained. Leaning **C2** for
reliability, with a comment pointing to the real-world rule.

## Files touched
- `cpsat/ScheduleConfig.java` — add `RotationPolicy.categoricalMaxPerBlock`.
- `cpsat/ConstraintBuilder.java` — add `applyCategoricalCapConstraints()`; (C2) inject
  static Y7D aux coverage, or consume it in `applyCoverageConstraints`.
- `cpsat/CpSatSchedulerEngine.java` — register the new constraint step in
  `orderedConstraintSteps()`; (C2) build the static aux-coverage map.
- `db/ScheduleConfigDAO.java` + schema — persist `categorical_max_per_block`.
- `db/DatabaseManager.java` — add column `rotation_config.categorical_max_per_block` (migration).
- Config data — set ICU.categoricalMax=1, VA.categoricalMax=2, VA.max_per_week=2,
  Y7D.min_per_week=2.

## Tests
- `RuleConstraintTest`: ICU never > 1 categorical/slot; VA never > 2 categorical/slot;
  Broadlawns cat+aux never > 2; Y7D total ≥ 2 every slot; block-13 has 2 categoricals on Y7D.
- Round-trip test for the new `categorical_max_per_block` persistence.

## Validation after re-solve
Re-run the capacity checker (the script that found the violations) → expect **zero**
violations on ICU/VA/BMC/Y7D. Then re-measure coverage + transitions; **expect the
weekend-coverage and transition numbers to change** (capacity caps remove some freedom the
solver was exploiting). The v1/v2/v3 comparison and the report will need regenerating with
the capacity-valid schedule as the new baseline.

## Open decisions for owner
1. **C1 vs C2** for Y7D static coverage (fix DB data vs inject in code). _Recommend C2._
2. After re-solve, treat the new capacity-valid schedule as **v4** and supersede v3 as the
   candidate-best? (The current v3 is invalid — it violates capacity — so it cannot be the
   final schedule regardless.)
