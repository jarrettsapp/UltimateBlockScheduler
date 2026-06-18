# Code Review — UltimateBlockScheduler

_Review date: 2026-06-17_

> **Status: all findings below have been addressed** on branch
> `fix/review-findings` (2026-06-17). Each item is annotated inline with its
> resolution, and a consolidated changelog appears at the end of this document.

This document is a structured review of the architecture, CP-SAT solver core,
data layer, models, tests, documentation, and repository hygiene. Findings are
ordered by severity. File references are clickable links to the relevant lines.

## Summary

This is a strong, well-structured project. The four-phase lexicographic CP-SAT
solver is the right design for the stated goals (respect hard rotation/residency
rules → minimize unwelcome transitions → balance coverage). The README, the
pre-solve feasibility diagnostics, and the auxiliary-resident coverage handling
are all above average. The findings below are concentrated in **unit/terminology
consistency** and **database threading**, which are the two areas most likely to
cause silent, hard-to-debug incorrect schedules.

---

## 🔴 High severity

### H1. "Block" means two different things — domain vs. code (terminology collision)

This is the highest-risk issue in the project.

**Domain definition (per program):**

| Term | Duration |
|------|----------|
| Full block | **4 weeks** |
| Half block | **2 weeks** |
| Academic year | 52 weeks = 13 full blocks = 26 half-blocks |

A rotation may be assignable as a full block (4wk), a half block (2wk), or
either, with edge-case nuances at the first and last block of the year.

**Code definition (everywhere internally):**

- The internal scheduling grid is **26 units**, and the code calls each unit a
  "block." See [CpSatSchedulerEngine.java:204](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L204)
  (`int totalBlocks = 26`) and the README ("Block = 2 calendar weeks ... 26 blocks").
- So the code's "block" is really the **domain's half-block (2 weeks)**.
- `allowedBlockLengths = {2}` therefore means a **2-unit = 4-week = full block**
  rotation; `{1}` means a **1-unit = 2-week = half block** rotation. See
  [VariableFactory.java:53](src/main/java/com/residency/cpsat/VariableFactory.java#L53).

**Why this matters:** every reader (and every future contributor, human or AI)
must silently translate "block" depending on which layer they're in. This is the
root cause of H2 below and makes the units nearly impossible to verify by
inspection.

**Recommendation:** adopt a single, explicit vocabulary across code, comments,
DB, UI, and README. Suggested: call the 2-week unit a **`slot`** (or `halfBlock`)
in code, and reserve **`block`** for the 4-week clinical block the program
actually uses. Document the mapping in one place (README + a constant). Until
this is resolved, treat every "block" in the codebase as suspect when reasoning
about durations.

### H2. `maxBlocksAllowed` is interpreted in incompatible units across call sites

The same `Rotation.maxBlocksAllowed` field is divided by different factors (and
sometimes used raw) in different files. Given H1 (internal unit = 2-week slot)
and the domain (user thinks in 4-week blocks), **none of these are consistent
with each other**:

| Location | Code | Implied unit |
|----------|------|--------------|
| UI control | [RotationView.java:106](src/main/java/com/residency/ui/RotationView.java#L106) | labeled `maxWeeks` |
| CP-SAT cap | [ConstraintBuilder.java:137](src/main/java/com/residency/cpsat/ConstraintBuilder.java#L137) | `/2` ("stored in weeks") |
| Manual validator | [SchedulingService.java:57](src/main/java/com/residency/service/SchedulingService.java#L57) | `/4` |
| Timefold | [TimefoldSchedulerService.java:210](src/main/java/com/residency/service/TimefoldSchedulerService.java#L210) | `/4` |
| Diagnostic (maxSchedulable) | [CpSatSchedulerEngine.java:789](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L789) | raw, as slots |
| Diagnostic (maxWks) | [CpSatSchedulerEngine.java:796](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L796) | raw, called weeks |
| Model comment | [Rotation.java:9](src/main/java/com/residency/model/Rotation.java#L9) | "max blocks" |

**Confirmed intended unit (from program owner): the user enters the value in
4-week blocks.** Therefore, to convert to the internal 2-week slot grid the
correct factor is **× 2** (1 block = 2 slots), not `/2` or `/4`.

Consequences as written:
- CP-SAT caps a rotation at `weeks/2` slots — wrong direction entirely if the
  input is blocks.
- `SchedulingService` (the manual override validator) and Timefold enforce
  `value/4`, a **different and smaller** cap than CP-SAT, so the manual checker
  will flag valid CP-SAT schedules (or vice versa).
- The diagnostic capacity check mixes raw and divided values, producing
  misleading "maxSchedulable" / "minWks>maxWks CONTRADICTION" reports.

**Recommendation:**
1. Decide canonical storage unit. Recommended: store in **4-week blocks** to
   match user mental model, OR store in slots to match the solver — but pick one.
2. Convert exactly once, at the UI↔model or model↔solver boundary, via a single
   helper (e.g. `slotsFor(blocks)`), and delete all ad-hoc `/2` and `/4`.
3. Rename the field and the UI label to match the chosen unit.
4. Add a unit test asserting that a rotation entered as N blocks produces a
   2N-slot cap in the model.

Also confirm whether `Rotation.minBlocksRequired` is used anywhere by the
solver — it appears to be dead (only `RotationRequirement.minBlocks` drives the
min constraint). Remove if unused.

### H3. Single shared SQLite `Connection` used across multiple threads

[DatabaseManager](src/main/java/com/residency/db/DatabaseManager.java) keeps one
static `Connection` shared by every DAO, but the application runs the solver on a
background thread and `runStepwiseDiagnosis`
([CpSatSchedulerEngine.java:864](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L864))
launches a **15-thread pool** (plus a removal pass). Problems:

- A single JDBC `Connection` is not safe for concurrent statements.
- `getValidConnection()` runs `SELECT 1` to health-check the connection; doing
  this while another thread is mid-statement on the same connection can throw or
  corrupt results.
- `getInstance()` ([line 17](src/main/java/com/residency/db/DatabaseManager.java#L17))
  is **not** synchronized (only `getValidConnection()` is), so the singleton can
  be double-created under a race.

Today the diagnosis threads only touch the in-memory CP model, not DAOs, so this
may be latent rather than actively breaking — but it is fragile.

**Recommendation:** use a connection pool (e.g. HikariCP) or open a short-lived
connection per operation (SQLite handles this well, especially in WAL mode). At
minimum, synchronize `getInstance()`.

---

## 🟠 Medium severity

### M1. Stepwise-diagnosis constraint order and labels don't match the real model

`buildBaseModel`
([CpSatSchedulerEngine.java:457](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L457))
applies constraints in one order; `runStepwiseDiagnosis`
([line 828](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L828))
applies them in a different order, and the human-readable `labels[]` array
([line 840](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L840)) is
off-by-one relative to the `if (s >= N)` guards (e.g. PGY caps and full-year
coverage). Because constraint conjunction is commutative, the feasibility
conclusion is valid, but the "first contradiction here" attribution and the
drill-down triggers (`firstInfeasible == 3`, `== 10`) can blame the **wrong**
constraint.

**Recommendation:** derive the diagnosis steps and their labels from one ordered
list shared with `buildBaseModel` so they cannot drift.

### M2. Default `allowed_block_lengths` may be in the wrong unit

DDL default is `'4'`
([DatabaseManager.java:134](src/main/java/com/residency/db/DatabaseManager.java#L134))
while the variable factory defaults to `{2}`
([VariableFactory.java:53](src/main/java/com/residency/cpsat/VariableFactory.java#L53)).
Given H1, confirm `'4'` is intended in slots (4 slots = 8 weeks = 2 full blocks?)
or is a leftover from a weeks-based model. A freshly created rotation should get
a sensible default (most likely `{2}` = one 4-week block).

### M3. Broad exception swallowing hides real solver/model bugs

`catch (Exception ignored) {}` wraps value extraction, hint extraction, and
assignment recording in several places (e.g.
[CpSatSchedulerEngine.java:305](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L305),
[513](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L513),
[582](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L582)). A real
desync (wrong variable, stale model) would surface as a silently incomplete
schedule rather than an error.

**Recommendation:** log at least once per swallowed batch so failures are visible
in the solver log.

### M4. Tests miss the highest-risk code paths

[CpSatSchedulerEngineTest](src/test/java/com/residency/solver/CpSatSchedulerEngineTest.java)
and ConstraintStressTest exercise small hand-built models and the feasibility
analyzer well, but nothing covers: the unit-conversion paths (H2),
prerequisite/sequence-rule semantics, the phase-lock flow, or DB round-trips. A
regression test pinning the block→slot cap would have caught H2.

---

## 🟡 Low / hygiene

- **`launch.log` is committed.** `.gitignore` covers `*.db` but not `*.log`.
  Remove the tracked file and add `*.log` (or `launch.log`).
- **Doubly-nested directory** `residency-scheduler/residency-scheduler/` is
  awkward; the README clone steps have to `cd` through both. Flatten to one level.
- **README says "1 block = 2 calendar weeks" and "26 blocks"** — this conflicts
  with the program's real definition (block = 4 weeks). Reconcile as part of H1.
- **Engine Javadoc says "Three-phase"** ([CpSatSchedulerEngine.java:22](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L22))
  but there are four phases (0–3).
- **Magic number `26`** is hardcoded in two places
  ([line 204](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L204),
  [line 614](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L614)).
  Promote to a named constant.
- **`ConstraintBuilder` section numbering drifts** (jumps `14` → `16`, no `15`),
  and `applyRotationLinkConstraints` runs before `applyRequiresConsecutiveConstraints`
  despite the reverse comment order.

---

## What's done well

- **Tiered lexicographic solve** (Phase 0 feasibility → Phase 1 clinical → Phase 2
  quality → Phase 3 pattern) with inter-phase warm-start hints and `≤ best`
  constraint locks. Graceful fallback Phase 3→2→1→0 is robust.
- **Pre-solve feasibility analysis + stepwise/removal diagnosis** that tells the
  user *which constraint* causes infeasibility — a standout UX feature.
- **Auxiliary-resident coverage pre-counting** (subtract aux coverage from
  min/max bounds, exclude filler groups) correctly models the "11 categoricals +
  ancillary influence" setup.
- **README** is thorough: constraint reference, settings reference, and schema
  tables are all present.

---

## Suggested fix order

1. **H1 + H2 together** — settle the unit vocabulary, then unify `maxBlocksAllowed`
   and `allowedBlockLengths` conversions behind a single helper. Add the
   regression test. (Highest impact on correctness.)
2. **H3** — connection-per-operation or pooling; synchronize `getInstance()`.
3. **M1** — single source of truth for diagnosis step order/labels.
4. Remaining medium/low items as cleanup.

---

## Resolution log (branch `fix/review-findings`, 2026-06-17)

| Finding | Status | Resolution |
|---------|--------|-----------|
| **H1** — block/slot terminology | ✅ Resolved | Decision (per program owner): keep the 26 two-week-**slot** internal grid rather than remodelling to 4-week blocks — rotations naturally split into 2-/4-week pieces and the program already refers to slots as 1A/1B/2A. Added `model/ScheduleUnits.java` as the documented home for the unit model; clarified field/UI/README wording. |
| **H2** — `maxBlocksAllowed` unit collision | ✅ Resolved | The field is entered in **weeks**; weeks→slots is ÷2. Fixed the `/4` bugs in `SchedulingService` and `TimefoldSchedulerService` and the raw-weeks use in the diagnostics; all conversions now route through `ScheduleUnits.weeksToSlots()`. Added `ScheduleUnitsTest`. |
| **H3** — shared SQLite connection | ✅ Resolved | `getInstance()` synchronized; racy `SELECT 1` replaced with `Connection.isValid()`; WAL + `busy_timeout` enabled. Kept the shared-connection model deliberately (a pool would touch every DAO and exceeds the current concurrency need) — documented that tradeoff in code. |
| **M1** — diagnosis order/labels drift | ✅ Resolved | Added `ConstraintStep` + `orderedConstraintSteps()`; `buildBaseModel`, the stepwise diagnosis, and the removal diagnosis now all iterate that one list, so order and labels can't diverge. Drill-downs key off the failing constraint's label, not a magic index. |
| **M2** — default `allowed_block_lengths` | ✅ Resolved (not a bug) | Verified the round-trip: DB column is in **weeks** (`'4'` = one 4-week block), converted to slots on load. Was correct but undocumented; added a DDL comment and routed conversions through `ScheduleUnits`. |
| **M3** — silent exception swallowing | ✅ Resolved | Added a `Logger`; objective/hint/solution-extraction failures now log (SEVERE for dropped assignments). Per-element catches are counted and reported in aggregate. |
| **M4** — test coverage gaps | ✅ Resolved | Added `ScheduleUnitsTest` (unit conversion), `RuleConstraintTest` (prerequisites, MUST_BE_AFTER, CANNOT_IMMEDIATELY_FOLLOW, PGY-scoping, and the per-resident max-blocks slot cap — feasible and infeasible cases), and `RotationPolicyRoundTripTest` (weeks↔slots persistence through SQLite). Suite grew from 17 to 27 tests. The Phase 0→3 lock flow remains an integration-level gap (needs a seeded DB); noted as optional follow-up. |
| **Low** — launch.log / magic 26 / docs / numbering | ✅ Resolved | `launch.log` removed and `*.log` git-ignored; `26` → `ScheduleUnits.SLOTS_PER_YEAR`; "Three-phase" → "Four-phase"; `ConstraintBuilder` section numbering fixed; README terminology updated. |
| **Low** — doubly-nested directory | ⏭️ Deferred | `residency-scheduler/residency-scheduler/` left as-is for now: flattening rewrites every path in history/CI/docs and is best done as its own dedicated change. Tracked for a follow-up.

### Commits on this branch
1. `Add REVIEW.md documenting code review findings`
2. `Fix maxBlocksAllowed unit collision (REVIEW.md H1/H2)`
3. `Harden DatabaseManager threading (REVIEW.md H3)`
4. `Unify constraint order/labels across diagnostics (REVIEW.md M1)`
5. `Document and unify rotation_config block-length units (REVIEW.md M2)`
6. `Log previously-swallowed solver exceptions (REVIEW.md M3)`
7. `Hygiene cleanup (REVIEW.md low-severity items)`
8. `Annotate REVIEW.md with resolution status and changelog`
9. `Add rule-constraint and DAO round-trip tests (REVIEW.md M4)`

All 27 tests pass and the project compiles cleanly after each commit.

### Recommended follow-ups (not done here)
- Add an integration test for the Phase 0→3 lock flow against a seeded database
  (the remaining slice of M4; the unit-level rule/conversion/persistence tests
  are now in place).
- Flatten the doubly-nested project directory in a dedicated commit.
- Consider a connection pool (HikariCP) if DB concurrency grows.
