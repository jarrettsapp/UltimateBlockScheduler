# TIMEFOLD BUILD PLAN — revive the legacy Timefold path as the Phase-3 soft-objective optimizer

**Date:** 2026-06-25. **Status:** ITEMS 1–4 BUILT + VALIDATED; Item 5 (A/B) in progress. Supersedes
the open questions in `TIMEFOLD_HANDOFF.md` — that doc gathered the facts; this one is the executable
plan.

## PROGRESS (2026-06-25)
- ✅ **Item 1 (warm start):** `buildSolutionFromVersion` + `commitToVersion`; round-trip byte/metric
  faithful (v159 → identical vol2/frag6/heal17). `tools/TimefoldWarmStartRunner` (spent=0 ⇒ no-solve).
- ✅ **Item 2 (hard model):** `RotationFeasibilityConstraintProvider` (full 15-family port),
  categorical-only entities + aux as fixed facts (`AuxAssignment`, `TimefoldFacts.auxCount` derived
  FROM THE VERSION). Built via `buildFeasibilityProblemFromVersion` (config/rules mirror the engine).
  `tools/TimefoldFeasibilityValidator`: 25/25 harvest versions hard==0/medium==0; corrupt version
  correctly flagged (only 7 distinct seeds exist, not 25). zeroVolunteerFloor left inert (config OFF).
- ✅ **Item 3 (soft):** `sundayCoverageShortfall` == `score_grid` line 33 EXACTLY; weight/target read
  from live config (95/2, not the stale 150). Verified Timefold soft == shortfall×95 on v159/100/130/160.
- ✅ **Item 4 (round-trip):** `tools/TimefoldOptimizeRunner` warm-start → optimize → assert hard==0
  preserved → `commitToVersion` → `solve_runs` row (version_id + originating seed, timefold-marked).
  VERIFIED on v159 60s: construction-heuristic no-op (warm start intact), 0hard/0medium preserved over
  2256 LS steps, soft −950→−855 (volunteer 2→1). The crux CP-SAT couldn't do, working.
- ✅ **Item 5 (A/B) — ALL POSITIVE.** Timefold 300s, hard==0 preserved every run, score_grid-confirmed:

  | start (seed)        | before (vol/frag/heal) | after (vol/frag/heal) | shortfall |
  |---------------------|------------------------|-----------------------|-----------|
  | v140 (a94bb29f, best) | 4 / 5 / 16           | 4 / **3** / 18        | 13 → **11** |
  | v100 (02b17b26)     | 2 / 8 / 15             | **1** / 8 / 16        | 12 → **10** |
  | v120 (6398ea76, worst) | 2 / 11 / 12         | 2 / **8** / 15        | 15 → **12** |

  Timefold BEAT the frag=5 seed ceiling (→3) and improved every start, including rescuing the worst
  seed by 3 fragile. Confirms the soft objective is improvable from a feasible start and Timefold is
  the Phase-3 optimizer. NOTE: this is with Timefold's DEFAULT move set — custom moves (handoff Topic B)
  should lift this further; and TY is currently pinned (handoff Topic A) which caps the reachable floor.

**NEXT (deferred to a new session — see `TIMEFOLD_OPTIMIZATION_HANDOFF.md`):** (A) model TY as MOVABLE
FILLER (not fixed facts) to widen the search space; (B) port/adapt custom move sets from the user's
other Timefold project + tune SolverConfig; then wire into the harvest/production pipeline.

---

**The bet (one line):** mass-harvest + cherry-pick Phase-2 schedules (unchanged), then hand a chosen
**Phase-2 schedule** to **Timefold as a warm start** so Timefold does what CP-SAT Phase 3 cannot —
optimize the soft Sunday-coverage objective from a feasible point without breaking the hard rules.

**Decisions locked this session:**
- Timefold is **100% a go**.
- Warm-start source = **Phase-2 schedule** (already Tier-1=0 / Tier-2=0; starts in the feasible region).
- Integration = **revive the existing legacy Timefold module** (it's already in the tree, just dormant
  and built for a different job — see Gaps below). Round-trip through the **version tables**, not the
  live `assignments` table, so results are comparable to harvest runs.
- Constraint scope = **full hard-model port** (Tier-1, Tier-2, special caps, GI fix) — additive onto
  the existing scaffolding.
- Build order = **warm-start spike FIRST** (de-risk the crux), then constraints, then soft objective.

---

## 0. What already exists (verified 2026-06-25, do NOT assume the handoff doc's estimate)

The legacy Timefold code is real and reasonably structured, but it was built to **construct a schedule
from empty**, not to optimize a given one. Files:
- `src/main/java/com/residency/solver/RotationSchedule.java` — `@PlanningSolution` (HardMediumSoft).
- `src/main/java/com/residency/solver/ResidentBlockAssignment.java` — `@PlanningEntity`, one per
  (resident × block); planning var = `Integer rotationId`, **`nullable=true`**, id = `res_block`.
- `src/main/java/com/residency/solver/RotationConstraintProvider.java` — generic constraints only.
- `src/main/java/com/residency/service/TimefoldSchedulerService.java` — `buildSolution(year)` builds
  entities with `rotationId=null` and solves from empty; commits to the **live `assignments` table**.
- `pom.xml` — `ai.timefold.solver:timefold-solver-core:1.14.0`, Java 17. Dependency is present.

### The three gaps that define this build
1. **No warm-start path.** Every entity starts `null`; a `minimiseUnassignedSlots` SOFT constraint +
   `nullable=true` confirm it fills from empty. **Nothing loads an existing schedule as the initial
   solution.** This is the crux and Item 1 below. (Structurally the OPPOSITE of CP-SAT #5025 — Timefold
   has no warm-start crash; you just pre-set the planning variables.)
2. **Wrong constraint model.** The provider has generic capacity/prereq/eligibility/max-blocks. It is
   MISSING everything the CP-SAT pipeline actually cares about:
   - ❌ Tier-1 (inpatient → *different*-inpatient transition ban at a block boundary)
   - ❌ Tier-2 (per-rotation per-block under/overcoverage as defined in config)
   - ❌ **Sunday-coverage soft objective (weight 95) — the entire point**
   - ❌ ICU/VA/BMC special caps; Inpatient-GI protection ([[i1-gi-protection-fix]])
   - Its SOFT level ("fill empty slots") is irrelevant once we warm-start a full schedule.
3. **Wrong round-trip table.** Reads/writes live `assignments`; must read **`schedule_version_assignments`**
   (Phase-2 start) and write a **new version** + record into `solve_runs` (with `version_id`, `seed_id`)
   so a Timefold result is comparable to any harvest schedule via `score_grid()`.

---

## 1. ITEM 1 — Warm-start spike (DE-RISK THE CRUX FIRST)

**Goal:** prove Timefold ingests a committed Phase-2 schedule as its starting solution and round-trips
it through `score_grid()` **unchanged**, using the *existing* constraints. If this is clean, the bet is
alive; if not, we learn it cheaply before porting anything.

**Steps:**
1. Add `RotationSchedule buildSolutionFromVersion(int year, long versionId)` to
   `TimefoldSchedulerService`: same as `buildSolution(year)`, but after building entities, set each
   entity's `rotationId` from `schedule_version_assignments(version_id, resident_id, rotation_id,
   block_number)`. Map `block_number → blockId` via `blocks` (same join `score_and_snapshot.py:209`
   uses). With every variable non-null, Timefold's Construction Heuristic is a no-op and Local Search
   starts from our schedule.
2. Run with a **trivial termination** (e.g. 5s, or unimprovedSpentLimit ~1s) and the existing
   constraints — we are NOT optimizing yet, only checking ingestion + commit.
3. Write the result to a **new version** via the version-table writer (mirror
   `score_and_snapshot.py:235`), NOT the live `assignments` table.
4. Run `score_grid()` on the new version and assert metrics **match the source Phase-2 version**
   (fragile/healthy/volunteer/heavy_heavy identical — Timefold shouldn't have moved a feasible
   schedule under a trivial budget).

**Exit criteria:** new-version metrics == source-version metrics. Round-trip is byte-faithful on the
286 categorical cells. **If this passes, the warm-start mechanism is proven and the whole bet stands.**

---

## 2. ITEM 2 — Port the hard model (full)

Replace/augment the generic `RotationConstraintProvider` so a known-feasible Phase-2 schedule scores
**0 hard**. Source of truth = `ConstraintBuilder` + live config (see `TIMEFOLD_HANDOFF.md` §2b).

- **Tier-1 (HARD):** for each resident, adjacent blocks b→b+1 where both are inpatient and *different*
  inpatient rotation = forbidden. (Post-call terms dormant — trigger lists unset; skip.)
- **Tier-2 (HARD):** per-rotation per-block undercoverage (< minPerBlock after aux credit) and
  overcoverage (> maxPerBlock) = forbidden.
- **Special caps (HARD):** ICU (cat≤1 / total≤2), VA (cat≤2), BMC (cat+TY≤2), Y7-Days floor; Inpatient
  GI protection per [[i1-gi-protection-fix]] (Inpatient GI = rotation 19, NOT Outpatient GI = 2).
  Cross-check against [[rotation-capacity-rules]].
- Keep the base feasibility constraints that are correct; **delete `minimiseUnassignedSlots`** and the
  reliance on `nullable` once warm-started (decide: keep nullable for safety, or make non-null).

**Validation:** load several known-feasible harvest versions via Item 1; each must score **hard==0**.
Any non-zero hard on a known-feasible schedule = a porting bug, fix before proceeding.

---

## 3. ITEM 3 — Add the Sunday-coverage soft objective (the optimizer)

Reproduce `score_grid()`'s exact definition (verified at `score_and_snapshot.py:33`):

> For weekend `w` (1..25): a categorical resident **covers** iff their rotation at block `w` ∈ `SRC`
> (sunday-source) AND ∉ `HEAVY` at `w` AND ∉ `HEAVY` at `w+1` (not entering heavy next block).
> coverers==0 → volunteer (full penalty), ==1 → fragile (partial), ≥2 → healthy (none). Target=2.

- **SOFT, weight 95:** penalize shortfall below target=2 per weekend. Use the exact `SRC` /`HEAVY`
  rotation-id lists from config (`sunday_source_rotation_ids`, `heavy_rotation_ids`).
- **4+2 pattern (weight 15):** OPTIONAL — user OK dropping; port only if time permits.
- **Categorical soft-cap excess (weight 5):** minor; port last.

**Validation:** Timefold's reported soft score must move in lockstep with `score_grid()` fragile/
volunteer when assignments change. Build a tiny test: perturb one cell, confirm both agree on the
direction/magnitude of the soft change.

---

## 4. ITEM 4 — Version-table round-trip + solve_runs record

- Read start from `schedule_version_assignments` (Item 1 already does this).
- Write result as a **new version**; insert a `solve_runs` row carrying `version_id` and the source
  `seed_id` (8-char prefix) so the Timefold result sits alongside harvest runs in all existing
  analysis/views. Mark the run so it's distinguishable as a Timefold (vs CP-SAT) result.
- `score_grid()` on the result = the recorded metrics (single source of truth).

---

## 5. ITEM 5 — A/B against the seed ceiling

Concrete target: best known schedule seed **`db9c7078`** = fragile 5 / healthy 16 / volunteer 4 /
heavy→heavy 0. From its OWN Phase-2 schedule, Timefold should at minimum **match** it and ideally
**reduce fragile** via the Sunday objective. Pool currently = 168 distinct seeds.

- Pick a handful of cherry-picked Phase-2 starts, run Timefold (real budget, e.g. 2–10 min), record
  each via Item 4, compare with `score_grid()`.
- **Success = Timefold reduces fragile below the seed's Phase-2 fragile on a meaningful fraction of
  starts** (proving the soft objective is improvable from feasible — the premise we're betting on).

---

## Build order & rationale
1 (warm-start spike) → 2 (hard port) → 3 (soft objective) → 4 (round-trip) → 5 (A/B).
Item 1 first because it's the crux the user flagged and it's cheap to disprove. Items 2–3 are the bulk
("significant investment"). Nothing here touches the default CP-SAT pipeline — the Timefold path stays
a separate, opt-in module reading/writing the same SQLite version tables.

## Open items to settle during the build (not blockers)
- Keep `@PlanningVariable(nullable=true)` (safety) vs. make non-null (cleaner once always warm-started)?
- Exact aux-credit handling in Tier-2 (mirror `ConstraintBuilder` precisely).
- Termination/budget tuning for Item 5 (start 2 min, size from where Timefold's best-score plateaus —
  reuse the trajectory-plateau discipline from [[plateau-convergence-finding]]).
