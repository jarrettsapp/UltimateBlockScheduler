# PROJECT — status, findings, bugs & planned work (engineering master)

_The single living place for **where the project is**: current status, key findings, open trackers,
known bugs, planned work, and design assessments. For **what a correct schedule must satisfy** see
[RULES.md](RULES.md); for **how to run the solver** see [OPERATIONS.md](OPERATIONS.md)._

_Consolidated 2026-06-23 from PROJECT_STATUS_AND_NEXT_STEPS, REVIEW, CAPACITY_FIX_PLAN,
SCHEDULE_VERSIONING_PLAN, PHASE0_ACCELERATION, FEASIBILITY_ANALYZER_UNIT_BUG, TERMINOLOGY_CLEANUP_PLAN,
and SURROGATE_MODEL_ASSESSMENT._

> **Note for code readers:** source comments cite findings by their original IDs (`H1`, `H2`, `H3`,
> `M1`–`M4`, `B1`). Those IDs are preserved verbatim in [§ Code review](#code-review-all-findings-resolved)
> and [RULES.md §13](RULES.md#13-encoded-vs-real-rule-review-known-gaps) (B1/B2/I1). A comment that
> says "see REVIEW.md M1" now means [§ Code review → M1](#code-review-all-findings-resolved); "see
> RULES_REVIEW B1" means [RULES.md §13 → B1](RULES.md#13-encoded-vs-real-rule-review-known-gaps).

---

## Table of contents

1. [Top priority next](#top-priority-next)
2. [Key findings (don't re-derive)](#key-findings)
3. [Open items / trackers](#open-items--trackers)
4. [Phase-0 acceleration](#phase-0-acceleration-implemented-not-yet-tested)
5. [Known bug: FeasibilityAnalyzer unit mixing](#known-bug-feasibilityanalyzer-unit-mixing-not-fixed)
6. [Terminology cleanup](#terminology-cleanup-not-started)
7. [Done: capacity fix](#capacity-fix-done)
8. [Done: schedule versioning & comparison](#schedule-versioning--comparison-done)
9. [Code review (all findings resolved)](#code-review-all-findings-resolved)
10. [Design assessment: surrogate-model optimizer](#design-assessment-surrogate-model-optimizer)

---

## Top priority next

> **⚠️ CONSTRAINT-FIX BOUNDARY — 2026-06-23.** A hard-constraint correctness fix landed (finding
> **I1**, [RULES.md §13](RULES.md#13-encoded-vs-real-rule-review-known-gaps)): the 6
> `CANNOT_IMMEDIATELY_FOLLOW` rules barring each heavy from following a Sunday-call source pointed at
> **Outpatient GI (2)** instead of **Inpatient GI (19)**; and Inpatient GI → Younker 7 Nights was
> wholly unprotected. Both fixed in the live DB. **Consequence: every cfg* sweep result and schedule
> recorded on or before 2026-06-22 was solved against the OLD constraints and is a historical
> baseline only.** The next sweep must start a fresh post-fix baseline; do not compare new runs
> against pre-fix fragile/volunteer numbers as if equivalent. Pre-fix DB backup:
> `residency_scheduler.backup-pre-gi-fix-20260623-194812.db`.

_Set by the user 2026-06-22 (away starting Wed 2026-06-25, wants sessions running while gone)._

**Build a tool that 100% automates the config-sweep loop** so multiple configs run SEQUENTIALLY,
unattended, for DAYS. **STATUS: BUILT and verified** — `sweep_driver.py` + `queue.jsonl` +
`launch_sweep.ps1` (see [OPERATIONS.md §2](OPERATIONS.md#2-the-autonomous-sweep--one-command)). The
driver takes a queue and, for each config: writes config → launches headless (10 workers, standard
budget) → waits → verifies → scores → snapshots a version → renders the grid → updates the report →
moves on, surviving crashes and reboots. Remaining polish lives in [Open items](#open-items--trackers).

---

## Key findings

_Don't re-derive these._

- **⚠️ All findings in this list predate the 2026-06-23 I1 constraint fix** (see banner in
  [Top priority next](#top-priority-next)). The *qualitative* lessons (floor is the binding lever,
  target=2 beats 3, plateau timing) are expected to hold, but the specific vol/fragile/healthy
  numbers below were measured against the old constraints — re-confirm against a post-fix baseline
  before treating any as the production target.
- **Recommended config so far: floor OFF, target 2, weight ~75–120.** Best results vol=1/fragile=6/
  healthy=18 (cfgA v13, cfgD-robust v18). Trades 1 volunteer weekend for ~halving fragile vs. the
  floor-ON baseline v7 (0/11/14). Whether to accept 1 volunteer is a clinical/policy call.
- **target=2 beats target=3** (3 makes volunteers relatively cheaper → more of them).
- **The hard zero-volunteer floor is the binding constraint on fragile** (floor ON pins fragile ~11).
  See the floor proof in [RULES.md §11](RULES.md#11-the-coverage-floor-is-zero--proof).
- **Worker count matters a LOT:** 10 workers is the reliable baseline; 1–3 workers FAIL to seed
  Phase 0. Run SEQUENTIALLY at 10 workers, not parallel-low-worker
  ([OPERATIONS.md §13](OPERATIONS.md#13-deprecated-parallel-low-worker-runs)).
- **Plateau convergence (UPDATED):** the ~1094 "floor" was premature; the real low plateau is
  **~882–892 (fragile 4–6)**, reached via a LATE cascade (1234–1861s) that short budgets cut off.
  Repeats + longer P3 beat new weights. The trajectory CSV tells you whether a run actually
  plateaued — size budgets from the plateau, not from branch counts.
- **Solver variance is real** (Phase 3 ends FEASIBLE, not OPTIMAL) — but much of it is runs cut off
  before their plateau. Run each config a few times.
- **Branch count is a USELESS quality proxy** — use the objective trajectory.

---

## Open items / trackers

### Branch migration (decide soon)
- **`feature/solver-trajectory-capture`** holds the ONLY copy of the Phase-3 trajectory-capture code
  (commit `bf249b2`, in `CpSatSchedulerEngine.java`). NOT on main. All trajectory-capturing runs
  (including the sweep) must happen on this branch. **Decision needed:** merge to main so the
  automation doesn't depend on a side branch. Recommend merging before further automation work.
- Many helper files (`gen_grid.py`, `score_and_snapshot.py`, the docs, `cp.txt`, the sweep tooling)
  are untracked — decide what to commit vs. keep local.

### DB corruption (RESOLVED, keep watching)
- The DB was corrupted ~2026-06-21/22; repaired (kept versions 6, 7, 10, 12 + LIVE; lost v9).
  Corrupted copies saved as `residency_scheduler.CORRUPT-*.db` / `PRE-REPAIR-*.db`. Root cause never
  fully pinned — likely concurrent writes / the JavaFX app open during a solve. The sweep's
  single-writer design + rotating `backups/` exist to prevent recurrence. **ALWAYS keep the JavaFX
  app CLOSED during headless runs; never run two solves against the master at once.**

### Sweep polish (applied)
- **Cooldown:** `sweep_driver.py` idles 300s between runs (`COOLDOWN_BETWEEN_RUNS_S`) so CPU/fans
  recover during long sweeps.
- **Archive dedupe:** `archive_completed_lines` used to re-append prior-round lines to
  `queue.archive.jsonl` every run; now gated on presence in `queue.jsonl` + archive deduped.

---

## Phase-0 acceleration (implemented, not yet tested)

_Added 2026-06-23, in `CpSatSchedulerEngine.java`. **Source-only — the running sweep was compiled
before these changes and is unaffected.** Recompile and test after the current sweep finishes._

**Why Phase 0 was slow.** It is a pure feasibility search (build all hard constraints, find *any*
valid assignment — no objective, no direction). CP-SAT without an objective wanders randomly →
observed 90–600s with high variance. Goal: consistently under ~3 min (ideally ~60–90s) so the
standard budget can be tightened.

Three complementary changes, all active simultaneously:

- **A) Lightweight objective — maximize total occupancy.** The cheapest possible objective (a linear
  sum over existing occupancy BoolVars) gives CP-SAT a gradient signal instead of a directionless SAT
  search. Highest-impact change. Safe: Phase 1 rebuilds its model from scratch and applies Phase-0
  hints only as warm-start suggestions (`repairHint=true`).
- **B) Greedy round-robin seed hints.** `buildGreedySeedHints()` distributes each rotation's minimum
  blocks across eligible residents round-robin, left-packed, fed as `addHint()` before solving. With
  `repairHint=true`, CP-SAT repairs a close seed to feasibility in seconds. A bad seed is harmless —
  repaired or ignored; worst case Phase 0 takes as long as before.
- **C) Phase-0 solver parameters.** `setCpModelProbingLevel(0)` (probing is expensive and wasted for
  feasibility) and `setInitialPolarity(POLARITY_FALSE)` (prefer the unassigned branch first; on this
  sparse-assignment problem, finds conflicts faster). Search-strategy hints only — they cannot make
  the solve incorrect.

| | Before | Expected after A+B+C |
|---|---|---|
| Median Phase-0 time | ~3 min | ~60–90 s |
| 95th-percentile | ~8–10 min | ~2–3 min |
| Variance | high (random walk) | much lower (directed) |

**Testing plan (after the current sweep):** recompile on the trajectory branch; run the same config
3–5×; record Phase-0 times (`Phase 0 ✓` timestamp); compare median/max vs. the ~90–600s baseline;
verify Tier-1 still 0 and Phase-3 scores comparable. **Then** consider dropping the P0 budget
900s → 300s ([OPERATIONS.md §8](OPERATIONS.md#8-standard-budget--worker-count)).

**Refinement ideas:** a smarter seed honoring more hard constraints (heavy→heavy, earliest-start,
caps); per-worker polarity diversification; a log line reporting how many hint variables were set
(near-zero = the greedy logic needs adjustment).

---

## Known bug: FeasibilityAnalyzer unit mixing (NOT fixed)

_Filed 2026-06-22. Low urgency — advisory only._

**TL;DR.** `FeasibilityAnalyzer` undercounts required workload because it multiplies a value in
**domain blocks (4 weeks)** by a value in **slots (2 weeks)**, computing **15.5 required slots per
resident when the true number is 25**. **This does not affect the schedules the solver produces** —
the bad number is only used for a pre-solve *warning comparison* and a *diagnostics display*, never
to build the schedule.

**Root cause.** `rotation_requirements.min_blocks` is in domain blocks (×2 = true slots);
`RotationPolicy.allowedBlockLengths` is in slots, so `minLen = min(allowedBlockLengths)` varies (1
for 2-week rotations, 2 for 4-week). The two offending lines compute `minBlocks × minLen`, which only
equals the true slot count when `minLen == 2`. For every 2-week rotation (`minLen == 1`) it returns
**half** the requirement:

- `FeasibilityAnalyzer.calcTotalRequiredBlocks` (~line 304): `total += req.getMinBlocks() * minLen;`
- `FeasibilityAnalyzer.checkRotationPool` (~line 171): `... (int) Math.ceil(req.getMinBlocks()) * minLen`

Worked per-resident total: 3 correct 4-week rotations (ICU, BMC, Y7D) = 6.0 slots kept whole; 13
halved rotations (true 19) → 9.5; analyzer total = **15.5** (true = **25**). Second-order: `total` is
`int` but `minBlocks` is `double` (0.5 allowed), so each `+= 0.5` truncates, compounding the
undercount. (The TRUE 25 < 26 by design: 25 half-blocks are pinned by hard minimums, the 26th is the
flex slot filled by Elective / a 2nd Y7-Nights segment.)

**Why schedules are still correct.** The wrong number flows to exactly two consumers: a soft
*warning* (`FeasibilityAnalyzer.analyze`, biased toward staying silent since it's too small — and the
engine logs "Proceeding anyway…") and a diagnostic display (`ConstraintViewerPanel`). The actual
per-resident workload is enforced by a separate, correct mechanism
(`ConstraintBuilder.applyMaxBlocksPerResidentConstraints`, built in proper slot units). The analyzer
is an advisory layer bolted on the side, not in the construction path.

**The fix (when chosen).** Route both sites through `ScheduleUnits.blocksToSlots(...)` (already
`round(blocks × 2)` and used by the real model), dropping `minLen` from the requirement math
(allowed-length is about *segmentation*, not *total* requirement), keeping a `double` until the final
compare. Bundle with the broader decision on bringing the static analyzer's coverage of recent hard
constraints up to date (zero-volunteer floor, heavy→heavy ban, categorical caps, max-consec H+M are
all un-pre-checked).

---

## Terminology cleanup (NOT started)

_Filed 2026-06-22. DOCUMENTATION ONLY — do NOT start until the solver-tuning experiment is done and
the user explicitly green-lights it. This is the root of the unit bugs (see the bug above and
[RULES.md §1](RULES.md#1-terminology-read-this-first))._

**Target vocabulary (user decision 2026-06-22):** express everything in two units only — **Block** =
4-week clinical block (13/year); **Half-block** = 2-week unit (26/year). Eliminate every other time
word from the domain vocabulary (slot, week, full block, 4+2, min/max weeks).

**This is a REDEFINITION, not just a rename.** The current canonical model (`ScheduleUnits.java`) is
three-tier; the target renames the atom **slot → half-block** and **full block → block**, and retires
bare "block" for the 2-week meaning:

| current term | current meaning | target term | target meaning |
|---|---|---|---|
| **slot** | atomic 2-week unit (26/yr) | **half-block** | atomic 2-week unit (26/yr) |
| **full block** | 4-week = 2 slots | **block** | 4-week = 2 half-blocks |
| **half block** | 2-week = 1 slot | **half-block** | (same as the atomic unit) |
| **block** (legacy, ambiguous) | sometimes 4wk, sometimes 2wk | — | RETIRED |

So "slot"→"half-block" is *mostly* a mechanical replace, but "block" already appears everywhere
meaning 4-week blocks AND as the legacy ambiguous term — those must be disambiguated by hand.

**Extent (measured 2026-06-22):** **556 occurrences across 42 Java files.** `slot`/`Slot` = 188
(nearly all → half-block); `week`/`Week` = 299 (MIXED — some real calendar weeks, many UI labels);
`half_block` = 4, `halfBlock` = 0. Hotspots: `CpSatSchedulerEngine` (~49), `ConstraintBuilder` (~43),
`ScheduleConfigDAO` (~34), `ScheduleUnits` (30, the hub), `ScheduleConfig` (21),
`ObjectiveFunctionBuilder` (16), `HeadlessSolveRunner` (16), every tool/test. **DB columns** carry
legacy units too (`min_per_week`, `max_per_week`, `max_consecutive_weeks`, `allowed_block_lengths`
stored in weeks, `*.block_number`, etc.) → schema rename = migration + a data-units pass + DAO churn.

**Risks:** the conversions (`weeksToSlots`, `slotsToWeeks`, `blocksToSlots`, the DAO's `*2`/`/2`) are
load-bearing — renaming without re-deriving each risks reintroducing exactly the unit bugs we're
killing; DB migration is irreversible-ish (needs a backup + reversible migration + keeping
`schedule_version_assignments.block_number` consistent); ~70 user-facing UI labels may need re-scaling
("Min Weeks" spinners stepping by 2).

**Suggested phased approach (when chosen):** (1) lock the vocabulary in `ScheduleUnits` with
deprecated aliases; (2) rename code identifiers leaf→engine, compiling + running tests after each
module (pure rename, no behavior change); (3) DB column rename + migration as a separate backed-up
step with a data-units audit; (4) UI labels last with before/after screenshots; (5) each phase: full
solve + `MetricsReportRunner` to prove identical output. Estimate: large; its own dedicated branch
with no other work in flight.

---

## Capacity fix (DONE)

_Drafted 2026-06-18; implemented. Earlier schedules (v1/v2/v3) violated hard per-block capacity
limits because the config and model didn't encode them correctly. The capacity-valid schedule became
the new baseline (v4)._

**The two defects fixed:**
1. **Wrong max caps** — `rotation_config.max_per_week` was too high. The real rules (see
   [RULES.md §2](RULES.md#2-per-block-rotation-capacities-hard-limits)): ICU categorical ≤1/total ≤2;
   VA categorical ≤2; Broadlawns total ≤2; Y7 Days total = 2.
2. **Y7 Days under-coverage** — the rule is ≥2 total every block (BMC supplies the static 2nd body
   except blocks 7 & 13), but the solver excluded BMC filler during the solve and left Y7D at 1 in
   22/26 blocks.

**Mechanisms added:**
- **Categorical-only caps** — new `RotationPolicy.categoricalMaxPerBlock` (default 0 = no separate
  cap) + `ConstraintBuilder.applyCategoricalCapConstraints()`, registered in
  `orderedConstraintSteps()`, persisted via a new `rotation_config.categorical_max_per_block` column.
  Set ICU → 1, VA → 2. This is the cap that ignores aux (distinct from the aux-aware total cap).
- **Total cap correction** — effectively just VA `max_per_week` 3 → 2 (ICU/BMC totals were already 2).
- **Y7 Days static BMC coverage + carve-outs** — credit BMC's Y7D coverage during the solve for the
  blocks where BMC is present (blocks 1–6, 8–12), so `effectiveMin = 2 − 1 = 1` (1 categorical); at
  block 7 a TY supplies the 2nd (effectiveMin 1); at block 13 aux = 0 → effectiveMin 2 (2
  categoricals). Two options were considered — C1 (fix the BMC aux rows in the DB) vs. C2 (inject a
  static aux-coverage map in code); C2 was recommended for reliability/self-containment.

**Validation:** capacity checker → zero violations on ICU/VA/BMC/Y7D; coverage and transitions
re-measured against the capacity-valid baseline (capacity caps remove some freedom the solver was
exploiting). Tests: `RuleConstraintTest` (ICU ≤1 cat, VA ≤2 cat, BMC ≤2 total, Y7D ≥2/block, block-13
= 2 cats) + a round-trip test for the new column.

---

## Schedule versioning & comparison (DONE)

_Drafted 2026-06-18; implemented. Three related features so you can solve repeatedly, keep multiple
"final production" schedules, and track improvements — all inside the app._

**1. Named snapshots.** Two tables added via migrations: `schedule_versions` (year, name, created_at,
notes, tier1/2/3 scores, feasible, summary; `UNIQUE(schedule_year, name)`) and
`schedule_version_assignments` (version_id, resident_id, rotation_id, **block_number**). Storing by
`block_number` (1–26) rather than `block_id` keeps a version loadable even if the year's block rows
are rebuilt. `ScheduleVersionDAO`: save / list / load (replaces live assignments) / delete /
getAssignments. UI: "Save as version…" and "Versions…" (list with Load / Delete / Compare + scores +
timestamps); loading confirms (it overwrites live) and offers to auto-save the current schedule
first.

**2. Metrics comparison.** A `ScheduleMetrics` service (pure function: assignments + tier config →
metrics record) is the single source of truth so the app and any report agree. Metrics use the
authoritative workload tiers ([RULES.md §7](RULES.md#7-workload-tiers--transition-quality)), NOT
`rotation_type`: capacity compliance (ICU/VA/BMC/Y7D + block-13), call coverage (volunteer/fragile/
healthy via the eligibility model), transitions (heavy→different-heavy, consecutive heavy+medium
histogram, runs > 6 weeks), Saturday Y8-Pulm floor (info). `VersionCompareView` shows one column per
version with best values highlighted; an "Export comparison" button writes md/HTML/PDF.

**3. Long-solve limits + presets.** Phase spinners raised 600 → **3600** s; a preset dropdown sets
all four phases at once (Quick / Standard / Long / Overnight / Custom); last-used limits persist; the
"Stop" button commits the best result found so far at the phase boundary.

---

## Code review (all findings resolved)

_Review 2026-06-17; all findings resolved on branch `fix/review-findings`. **The original finding IDs
below (H1–H3, M1–M4) are the ones cited in source-code comments** — keep them stable._

**What's done well:** the tiered lexicographic solve (Phase 0 feasibility → 1 clinical → 2 quality →
3 pattern) with inter-phase warm-start hints and `≤ best` locks + graceful 3→2→1→0 fallback;
pre-solve feasibility analysis + stepwise/removal diagnosis (tells the user *which* constraint causes
infeasibility); auxiliary-resident coverage pre-counting.

| Finding | Severity | Resolution |
|---|---|---|
| **H1** — "block" means 4wk (domain) vs 2wk (code): terminology collision | 🔴 | Resolved. Decision (per owner): keep the 26 two-week-**slot** internal grid; added `model/ScheduleUnits.java` as the documented unit hub. (Full rename still planned — see [Terminology cleanup](#terminology-cleanup-not-started).) |
| **H2** — `maxBlocksAllowed` interpreted in incompatible units across call sites (`/2` vs `/4` vs raw) | 🔴 | Resolved. The field is entered in **weeks**; weeks→slots is ÷2. Fixed the `/4` bugs in `SchedulingService` + `TimefoldSchedulerService` and the raw-weeks diagnostic use; all conversions route through `ScheduleUnits.weeksToSlots()`. Added `ScheduleUnitsTest`. |
| **H3** — single shared SQLite `Connection` across threads; `getInstance()` unsynchronized | 🔴 | Resolved. `getInstance()` synchronized; racy `SELECT 1` replaced with `Connection.isValid()`; WAL + `busy_timeout` enabled. Shared-connection model kept deliberately (a pool would touch every DAO, exceeds current need) — tradeoff documented in code. |
| **M1** — stepwise-diagnosis constraint order/labels drift from the real model (can blame the wrong constraint) | 🟠 | Resolved. Added `ConstraintStep` + `orderedConstraintSteps()`; `buildBaseModel`, stepwise diagnosis, and removal diagnosis all iterate that one list; drill-downs key off the failing constraint's label, not a magic index. |
| **M2** — default `allowed_block_lengths` possibly wrong unit | 🟠 | Resolved (not a bug). DB column is in **weeks** (`'4'` = one 4-week block), converted to slots on load; added a DDL comment and routed conversions through `ScheduleUnits`. |
| **M3** — broad `catch (Exception ignored) {}` hides real solver/model bugs | 🟠 | Resolved. Added a `Logger`; objective/hint/solution-extraction failures now log (SEVERE for dropped assignments); per-element catches counted + reported in aggregate. |
| **M4** — tests miss the highest-risk paths (unit conversion, rule semantics, DB round-trips) | 🟠 | Resolved. Added `ScheduleUnitsTest`, `RuleConstraintTest`, `RotationPolicyRoundTripTest`; suite grew 17 → 27. The Phase 0→3 lock flow remains an integration-level gap (noted as optional follow-up). |
| **Low** — `launch.log` committed; magic `26`; "Three-phase" javadoc; `ConstraintBuilder` numbering | 🟡 | Resolved. `*.log` git-ignored; `26` → `ScheduleUnits.SLOTS_PER_YEAR`; "Four-phase"; numbering fixed; README terminology updated. |
| **Low** — doubly-nested `residency-scheduler/residency-scheduler/` directory | 🟡 | ⏭️ Deferred — flattening rewrites every path in history/CI/docs; best as its own dedicated change. |

**Recommended follow-ups (not done):** an integration test for the Phase 0→3 lock flow against a
seeded DB; flatten the doubly-nested directory; consider a connection pool (HikariCP) if DB
concurrency grows.

> See also the **encoded-vs-real rule review** (findings B1/B2/I1 + missing rules) in
> [RULES.md §13](RULES.md#13-encoded-vs-real-rule-review-known-gaps) — B1 (under-enforced minimums)
> is FIXED and is cited in code as "RULES_REVIEW B1".

---

## Design assessment: surrogate-model optimizer

_2026-06-23. Should we replace human config-design with a Bayesian-optimization surrogate? **TL;DR:
not now.**_

**What we have:** ~18 result rows in `sweep_results.csv` + ~17 trajectory CSVs. The config space is
small and mostly pinned: floor effectively locked OFF, target locked at 2, **weight is the one knob
being swept** (75–250), P3 budget being pushed up, runs are just repeats. So we're tuning ~one
continuous dial against a noisy objective with ~18 noisy samples — analyzed by eye via
`design-solver-batch` (informal human Bayesian reasoning).

**A surrogate** (Gaussian Process / gradient-boosted trees — light CPU, *not* GPU/deep-net) would
learn `weight → expected outcome` with error bars and use an acquisition function to pick the next
weight. **Better:** handles noise honestly (models mean + spread, won't chase a lucky draw),
quantifies "we've learned enough here," removes judgment bias, documents the search as a curve.
**Worse:** overkill for a 1-D dial (grid + repeats already covers a line); noise can fool it too with
~18 points and an unsettled scalar objective; new failure modes + maintenance; hides the reasoning.

| Data points | Verdict |
|---|---|
| < ~20 (where we are) | Too few — a GP would guess between sparse noisy dots. Keep human design. |
| ~30–50 | Useful as an **advisor** — fit a curve + error bands to inform the batch; you still decide. **Best near-term step.** |
| ~75–100+ | Can **drive** selection autonomously with a sanity-clamp. |

But the real gate is **dimensionality**, not count: BO earns its keep only once we tune **3+
interacting knobs** at once (e.g. weight × P3-budget × a second soft-objective weight). While weight
is the only live dial, more grid points beat a model.

**Recommendation:** don't switch now. Cheap high-value next step — add an *analysis* layer (not a
decision-maker): fit a simple curve with error bars to `sweep_results.csv`, plot
expected-fragile-vs-weight. Revisit the full optimizer at ~30–50 runs OR when a second/third knob
opens up. GPU has no role anywhere here.
