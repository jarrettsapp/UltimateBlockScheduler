# HANDOFF: Timefold is the Phase-3 optimizer — now tune TY modeling + custom move sets

**Date:** 2026-06-25. **For a NEW session.** The Timefold revival (Items 1–5 of
`TIMEFOLD_BUILD_PLAN.md`) is BUILT and the core bet is PROVEN: Timefold warm-starts from a feasible
Phase-2 schedule and improves the Sunday-coverage objective without breaking feasibility — the role
CP-SAT Phase 3 could never fill (OR-Tools #5025). Read `TIMEFOLD_BUILD_PLAN.md` and memory
[[timefold-warmstart-built]] first. This doc sets up the TWO follow-on topics we deliberately deferred.

---

## ★ PRODUCTION CONFIG (LOCKED 2026-06-26)

Phase-3 = `TimefoldOptimizeRunner <year> <srcVersion>` (no extra args). Defaults, all in
`TimefoldSchedulerService`:

| Setting | Default | Constant / knob |
|---|---|---|
| Multi-start parallel starts | **10** | `DEFAULT_STARTS` · env `TF_STARTS` |
| Per-start time budget | **600 s** | `DEFAULT_SPENT_SECONDS` · env `TF_SPENT` or arg 3 |
| Objective | **tiered fragile-dominant, ON** | env `TF_TIER=0` to disable → legacy flat shortfall |
| Move set | **R0** (lean, default change+swap) | env `TF_VARIANT=R1..R4` |
| Move threads | none (single-thread per start) | env `TF_THREADS` — **Enterprise-only, will crash on Community** |

**Why this config (from the benchmarks, §6):** best-of-N multi-start with the tiered objective + lean R0
ties or beats every custom-move variant on every seed, rescues the worst seed (v120 frag 6→4), and
collapses run-to-run variance (MS worst rep ≈ ST best rep). Budget is long because time-to-best lands
late (median ~240 s). Multi-start runs N independent solvers from the SAME warm start with distinct
random seeds (1000+i) and keeps the best — Community-legal CPU parallelism (Enterprise `moveThreadCount`
is unavailable and throws `ClassNotFoundException`).

**Tiered objective weights** (env-overridable `TF_WV`/`TF_WF`/`TF_RBASE`/`TF_RDEPTH`):
`soft = −(volunteer×Wv=100) − (fragile×Wf=1000) + Σ_healthy(Rbase=10 + Rdepth=3×(coverers−2))`.
FRAGILE ≫ volunteer by design (a fragile weekend is a hidden single-point-of-failure; a volunteer
weekend is honestly uncovered). Sunday coverage is the SOLE soft objective — it competes only with
itself; hard (15 families) + medium (1) are separate lexicographic tallies driven to 0 first.

**Move-set variants — how to switch on later** (`TF_VARIANT=`, all keep feasibility hard0/med0):
- **R0** — lean: default change + swap only. PRODUCTION default.
- **R1** — phased: FIRST_FIT CH → Late Acceptance (size 400, hands off at 60 s unimproved or 70 % budget)
  → Step-Counting HC (size 400). Structure only.
- **R2** — R1 + native `ruinRecreateMoveSelector` (10–30 % ruin, weight 150) in the LA phase. LNS escape.
- **R3** — R2 + `SundaySwapMoveFactory` (weight 200): same-block rotation swap between two SAME-PGY
  mutually-eligible residents → per-block counts invariant ⇒ feasible by construction; biased to weak
  (vol/frag) weekends.
- **R4** — R3 + `SundayEjectionChainMoveFactory` (weight 200): demand-driven — for a weak weekend, swap a
  non-source cell onto a Sunday-source (donor takes the old rotation) to MANUFACTURE a coverer.
- Custom-move classes live in `com.residency.solver.move/` (`RotationReassignMove` is the atomic
  multi-step executor; 1.14.0 classic `AbstractMove` — NO preview move API in this version). Benchmark
  verdict: at single-thread R0 was already best; under multi-start the variant barely matters, so R2–R4
  are kept available but OFF. They may earn their place at longer budgets or combined more sparingly.

**Benchmark harness (reusable):** `TimefoldMoveBenchRunner <year> <srcVer> <secs> <rep>` writes
`move_bench.csv` (single) or `move_bench_multistart.csv` (when `TF_STARTS` set) — captures
time-to-best + before/after metrics, does NOT commit a version. Matrix drivers were
`/tmp/move_bench_matrix.sh` and `/tmp/ms_matrix.sh` (4 variants × 4 seeds × reps).

---

## 0. State of play (what's done — don't redo it)

- **Item 1–4 DONE + validated.** Warm-start round-trip (byte/metric faithful), full 15-family hard
  model (`RotationFeasibilityConstraintProvider`, 25/25 harvest versions hard==0, corrupt version
  correctly flagged), Sunday soft objective (== `score_grid` shortfall × 95, exact), real
  optimize→commit→`solve_runs` round-trip.
- **Item 5 (A/B) — ALL 3 POSITIVE (300s each, hard=0 preserved, score_grid-confirmed):**
  v140 (best seed a94bb29f): frag **5→3**, heal 16→18, shortfall 13→11 — BEAT the seed ceiling;
  v100: vol **2→1**, shortfall 12→10; v120 (worst): frag **11→8**, shortfall 15→12.
  Every start improved; the worst seed was rescued by 3 fragile. Confirms the soft objective IS
  improvable from a feasible start. THIS IS WITH DEFAULT MOVES + TY PINNED — Topics A & B below should
  lift it further.
- **Tools:** `TimefoldWarmStartRunner` (ingestion), `TimefoldFeasibilityValidator` (hard==0 gate),
  `TimefoldOptimizeRunner <year> <srcVersion> [spentSeconds]` (the real optimizer). Run via
  `target/classes` + `cp.txt` classpath; Java 21 on PATH.

## 1. PRODUCTION INTENT — the pipeline this becomes

The end-state pipeline (confirmed direction): **mass-harvest + cherry-pick Phase-2 seeds (unchanged) →
hand the chosen Phase-2 schedule(s) to Timefold for FINAL soft-objective optimization → score_grid →
record as a comparable version/solve_run.** Timefold replaces the dead CP-SAT Phase 3. Next-session
work is making that handoff (a) model the problem with full search freedom and (b) optimize Timefold
itself for our shape. Two topics:

## 2. TOPIC A — TY residents should be MOVABLE FILLER, not fixed facts

**The issue (user-identified).** Item 2 modeled ALL auxiliary residents as FIXED problem facts
(`AuxAssignment` + pre-counted `TimefoldFacts.auxCount`). That is correct for **BMC** residents (truly
fixed coverage) but WRONG for **TY** residents: TY are *filler* — they have no requirements of their
own; they exist to fill gaps so categorical constraints are satisfiable. Pinning TY at their Phase-2
placement **freezes part of the feasible region and can obstruct the best categorical arrangement**
(if a better categorical schedule needs a TY body in a different slot, the pin forbids it). Since TY
coverage feeds the Tier-2 min/max bounds the Sunday objective trades against, this directly caps how
good a schedule Timefold can reach.

**Decision (locked):** model **TY as MOVABLE FILLER with their own rules** — let Timefold relocate TY
coverage as categoricals move, governed by the SAME rules TY followed in the CP-SAT solver (so behavior
matches and is easier to reason about). BMC stays fixed.

**What the new session must do:**
- **Clarify the TY rules from CP-SAT** (deferred — user to specify / we extract from
  `AuxFillerService` + `AuxFillerRotationDAO` + filler-exclusion logic). Key questions to answer:
  which rotations can a TY fill (the filler value-range), any caps/eligibility, and how
  `AuxFillerService` currently regenerates TY post-solve (CP-SAT pre-counts TY coverage from the
  committed schedule, then RE-FILLS post-solve — see `CpSatSchedulerEngine` ~line 1245 + the
  filler-exclusion set in `buildFillerExclusions`). Decide whether Timefold plans TY directly or we
  keep the pre-count + re-fill split.
- **Model choice:** TY become planning entities (own light value-range + filler constraints) OR a
  movable-fact scheme where TY coverage recomputes as categoricals move. Likely: TY as planning
  entities with a restricted value range = filler-eligible rotations, plus constraints mirroring the
  filler rules; keep BMC as fixed `AuxAssignment` facts.
- **Re-validate hard==0** on the 25 versions after the change (the existing validator is the gate;
  ensure aux/TY split is loaded correctly — currently `buildFeasibilityProblemFromVersion` derives aux
  coverage FROM THE VERSION, which is the right hook to split BMC-fixed vs TY-movable).
- **Re-run the A/B** — TY freedom should only EXPAND what Timefold can reach; confirm it does not
  regress feasibility and ideally improves the achievable fragile/shortfall floor.

**Where to make the change:** `TimefoldSchedulerService.buildFeasibilityProblemFromVersion` (split
auxResidents into BMC-fixed vs TY-movable when building `auxCoverage`/`AuxAssignment` vs new TY
entities); `RotationFeasibilityConstraintProvider` (TY filler constraints); `TimefoldFacts` (carry the
TY filler value-range + rules). BMC detection is already there (`bmcIds` via residentGroup=="BMC").

## 3. TOPIC B — port/adapt custom MOVE SETS from the user's other Timefold project

**The lead (user has this).** The user has an extensive, somewhat-similar Timefold project with CUSTOM
MOVE SETS. Our current `solveFeasibility` uses Timefold's DEFAULT move selectors (change + swap).
Suspicion: the **default move set is the bottleneck, not the model** — from a feasible start, most
single-cell change moves break a hard constraint and are rejected, so improvement is rare/slow (the
60s run found only one feasibility-preserving improvement; 300s found a few more). Custom moves built
for a rotation-scheduling shape (block-segment swaps, resident-pair exchanges, ruin-&-recreate over a
weekend, etc.) could dramatically widen what Timefold reaches from a feasible start.

**What the new session must do:**
- **Get the other project** (user will provide path/code next session) and study its move selectors,
  ConstraintProvider, and SolverConfig (especially custom `MoveListFactory` / `MoveIteratorFactory`,
  acceptance/forager tuning, and any phase config).
- **Map its moves onto our entity model** (which by then may include movable TY). Candidate moves for
  OUR problem: swap two categoricals' rotations within a block (preserves per-block counts → stays
  feasible); shift a resident's segment by ±1 block; pairwise weekend-coverage swaps targeting
  fragile/volunteer weekends; ruin-and-recreate a low-coverage weekend.
- **Tune the SolverConfig** for our shape: termination from the best-score plateau (reuse the
  trajectory-plateau discipline from [[plateau-convergence-finding]]), Late Acceptance vs Tabu,
  multi-threaded solving, and a real budget. Current default termination is just a spent-time limit.
- **Re-A/B** with the custom moves to quantify the lift over default moves (same starts: v140/v100/v120).

## 4. Quick reference for the new session
- Plan + progress: `TIMEFOLD_BUILD_PLAN.md`. Facts gathered: `TIMEFOLD_HANDOFF.md`. Dead CP-SAT path:
  `SINGLE_PHASE_DEADEND.md` (don't retry it).
- Best available seeds (year 2 harvest versions): a94bb29f frag5 (v140), d5305c04 frag6 (v150),
  then frag8+ (only 7 distinct seeds exist in versions). Memory best-seed db9c7078 (frag5) is from the
  pool but not among the committed harvest versions — regenerate its Phase-2 version if needed.
- Canonical metrics = `score_grid()` in `score_and_snapshot.py`; Timefold metrics computed by
  `TimefoldSchedulerService.computeMetrics` (proven == score_grid). Soft weight/target read from live
  config (95/2).
- Validation gate before trusting ANY model change: `TimefoldFeasibilityValidator 2 <25 versions>` must
  stay 25/25 hard==0/medium==0.

## 5. Order of operations (suggested)
1. Topic A first (TY movable filler) — it changes the entity model, which Topic B's moves operate on.
   Clarify TY rules → remodel → re-validate hard==0 → re-A/B.
2. Then Topic B (custom moves) on the finalized entity model → tune SolverConfig → re-A/B → lock the
   production budget.
3. Then wire into the harvest/production pipeline as the standard final-optimization step.

> **DECISION 2026-06-25 (overrides the order above for the move-set work):** do **moves FIRST on the
> CURRENT TY-pinned model** to get fast signal on the lift, *then* redo Topic A and re-run. Some rework
> is accepted in exchange for an early read on whether custom moves break the plateau. Section 6 is the
> grounded plan; it supersedes Topic B's "get the other project" step (the project has been located and
> inventoried below).

---

## 6. TOPIC B — move-set inventory from `Scheduler 5.0` + test plan

The "other Timefold project" = **`C:\Users\Jarrett\Desktop\Scheduler 5.0`** (NOT 4.0 — 4.0 uses only
declarative change/swap + LA→Tabu). **5.0 is treated strictly READ-ONLY; no edits were made there.**

### 6.1 What 5.0 has (where to look)
- **`src/main/java/com/scheduler/solver/move/`** — the custom moves + selection filters.
- **`src/main/java/com/scheduler/solver/experiment/VariantCatalog.java`** — the gold: it programmatically
  layers those moves onto a phased `SolverConfig` as an **escalating ladder of named A/B variants**
  (`baseline → ch-priority → selector-targeting → hard-repair-neighborhoods → targeted-ruin →
  late-phase-retune → soft-fill-bias → soft-plateau-repair → soft-late-polish`). Each variant adds ONE
  technique on top of the prior — exactly the incremental, measured discipline we want.
- `src/main/resources/solver/solverConfig.xml` — their phased baseline (CH → 4 ruin/LA/Tabu/SCHC cycles
  → open SCHC). Uses the native `ruinRecreateMoveSelector` with descending ruin % (50%→5%).
- `MIGRATION_LOG.md` — empirical history; key line: **ruin weight was their single biggest lever**
  (`ruinRecreateMoveSelector` is native in Timefold 2.0 community; weight 1.0 ⇒ ~0.5% of moves, so it
  must be weighted up explicitly).

### 6.2 CAVEAT — different problem shape (nothing ports verbatim)
5.0's planning entity is a `ShiftAssignment` (a weekly call shift) whose `@PlanningVariable` is **which
resident**. Ours is `ResidentBlockAssignment` whose variable is **which rotationId** (`nullable=true`).
So their classes don't copy over — but the **patterns** map. Their generic executor is
`AssignmentReassignMove` (applies an atomic list of `(entity→value)` steps); every "custom move" is a
`MoveListFactory` that enumerates smart step-lists. Our analogue executes
`changeVariable(rotation, entity, rotationId)`.

### 6.3 The 6 move factories + 2 selection filters, mapped to OUR problem

| 5.0 factory | Strategy | Our analogue / value |
|---|---|---|
| `UnassignedShiftFillMove` | fill an unassigned entity w/ best eligible value | LOW — our warm start has no unassigned cells |
| `ResidentWeekConflictRepairMove` | find double-booked resident-weeks, reassign/clear | LOW — we're already hard==0 |
| `AutoCallRepairMove` | force a required pre-assignment, displace+rehome conflict | MED — analogue = honor pinned BMC/TY coverage |
| **`TwoWeekEjectionChainMove`** | target *violating* shifts/weeks, move a resident in & eject→rehome the displaced one | **HIGH (hard-focused ejection chain)** |
| **`SoftPlateauRepairMove`** | sort entities by soft *opportunity score*; only emit reassigns whose soft-gain ≥ threshold (600) | **HIGH — directly our Sunday-coverage objective** |
| **`SoftEjectionChainMove`** | 2-step chains gated by soft candidate scoring | **HIGH — soft-aware feasibility-preserving chain** |
| `FocusedAssignmentSelectionFilter` | narrow generic change/swap to hard-violation cells | MED |
| `SoftAssignmentSelectionFilter` | accept only cells that can improve soft score (ranked wknd / credit deficit / type min·cap / post-rotation window) | **HIGH — cheap focus so default moves stop wasting steps** |

### 6.4 WHY custom moves should help us (the diagnosis)
Our `TimefoldSchedulerService.solveFeasibility` currently builds a **bare `SolverConfig`** — only a
`withSpentLimit`, no CH, no phases, no move config ⇒ Timefold's **default change+swap only**. From a
feasible start, a single rotation change almost always trips a hard constraint and is rejected, so
improvement is rare/slow (handoff: 60s found ONE improving move). An **ejection chain** moves a
categorical AND rehomes the displaced one in the same atomic move, so it stays feasible *by
construction* — this is the move class default selectors structurally cannot produce.

### 6.5 The staged A/B ladder (mirror 5.0's VariantCatalog, on OUR problem)
Run each rung on the SAME 3 warm starts as the existing A/B (**v140 / v100 / v120**), each vs the prior
rung, gated by `TimefoldFeasibilityValidator 2 <25 versions>` staying **25/25 hard==0**. Metric =
`score_grid()` (fragile / shortfall). Build the config programmatically (like `VariantCatalog`), keep
each rung env-gated so the default path is untouched.

- **R0 baseline** — current bare config. (already have: frag 5→3 / 2→1 / 11→8.)
- **R1 phased + soft filter** — add CH(`FIRST_FIT_DECREASING`) + LA→SCHC phases + a `SoftAssignment`-style
  selection filter on change/swap. Tests "structure alone" before any custom move.
- **R2 + ruinRecreate** — add native `ruinRecreateMoveSelector` (descending ruin %, weighted up per the
  MIGRATION_LOG lesson). Cheapest high-leverage lever; no custom Java.
- **R3 + soft-plateau-repair move** — port the `SoftPlateauRepairMove` *pattern*: sort our entities by a
  Sunday-coverage opportunity score, emit gain-thresholded rotation reassigns.
- **R4 + soft ejection chain** — port `SoftEjectionChainMove`: 2-step `(entityA→rot, displaced→rot')`
  chains that preserve per-block categorical counts ⇒ feasibility-preserving. **Expected biggest lift.**
- **R5 late-phase retune** — copy the acceptor/forager retuning ideas (`retuneSoftLatePhases`).

Stop climbing when a rung stops paying. Whatever wins becomes the move config we then re-validate after
Topic A (movable TY) lands.

### 6.5b Threading & multi-start — CURRENT STATE (verified 2026-06-25)
We are **single-threaded, single-start** today. Verified by grep: no `moveThreadCount`, no
multi-threaded solving, no parallel/multi-start setup anywhere in `com.residency`. `solveFeasibility`
does `SolverFactory.create(config).buildSolver().solve(problem)` — one solver, one thread, one warm
start; `moveThreadCount` defaults to `NONE`. This is intentional for the R1–R5 A/B (move set = the only
variable). Two independent axes to test LATER, after a move set wins:
- **Multi-worker** = `config.withMoveThreadCount("AUTO")` — intra-solve move-eval parallelism; one line.
  Most useful once custom ejection/ruin moves enlarge the per-step neighborhood.
- **Multi-start** = N independent solvers from N starts, keep best (inter-solve). **Scheduler 5.0 already
  implements this**: `com.scheduler.solver.SolverService.solveMultiStart(N, …)` (N parallel
  `SolverManager`s) — a working reference to port. Maps onto our harvest pipeline as "top-K seeds → K
  parallel Timefold optimizes → keep best."
Keep both OFF during R1–R5; revisit as a separate axis.

### 6.5c R3/R4 custom moves BUILT + benchmark matrix (2026-06-25)
- **Built** (env-gated, default path untouched): `RotationReassignMove` (atomic multi-step change
  executor, 1.14.0 classic `AbstractMove` — note: 1.14.0 has NO preview move API, unlike 5.0).
  `SundaySwapMoveFactory` (R3): same-block rotation swap between two SAME-PGY mutually-eligible
  residents → keeps every per-block count invariant ⇒ feasibility-preserving by construction; biased to
  weak (vol/frag) weekends. `SundayEjectionChainMoveFactory` (R4): demand-driven — for a weak weekend,
  swap a non-source cell to a Sunday-source S (donor takes the old rotation) to MANUFACTURE a coverer.
- **Variants:** R3 = R2 + swap; R4 = R3 + ejection chain (both weight 200, STEP-cached, ≤4000 moves).
- **v140 finding (all tiered fragile-dominant, 300s): R2/R3/R4 ALL floor at frag3/vol5/heal17.** Custom
  moves are feasible + reach the floor but DON'T lift it ⇒ the fragile floor is SEED-determined, not
  search-determined (consistent with [[seed-determines-quality]]). R2 (no custom-move overhead) is the
  cheapest variant that reaches the floor.
- **Time-to-best capture added:** `solveFeasibilityTimed`/`TimedSolve` (BestSolutionChangedEvent
  listener) + `TimefoldMoveBenchRunner` (writes `move_bench.csv`, NO version/solve_runs commit). Running
  the full matrix: 4 variants (R0/R2/R3/R4) × 4 seeds (v100/120/140/150) × 2 reps × 300s tiered, to
  decide (a) which move set wins per-seed and (b) the production P3 budget from when time-to-best lands.

### 6.6 Concrete next actions
1. Refactor `solveFeasibility` to build a phased `SolverConfig` (factor a `buildSolverConfig(variant)`
   like 5.0's `VariantCatalog`), env-gated so default = today's bare config.
2. R1→R2 first (no new Java) to confirm structure+ruin help before writing move factories.
3. Write our `RotationReassignMove` (atomic step-list executor) + the two HIGH-value factories
   (`SoftPlateauRepair`, `SoftEjectionChain`) adapted to `rotationId`/per-block-count feasibility.
4. A/B each rung on v140/v100/v120; record as comparable versions/solve_runs; keep the hard==0 gate.
