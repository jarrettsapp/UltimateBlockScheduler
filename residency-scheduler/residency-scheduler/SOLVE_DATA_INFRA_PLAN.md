# Rigorous Data + Quality Infrastructure for Seed-Fed Real Solves — BUILD LOG

Implements `~/.claude/plans/okay-we-now-have-moonlit-dragon.md`. This file tracks what was built,
the Phase-4 post-I1 quality audit result, and what remains gated behind the seed lock.

> **Serialization status while this was authored:** a Phase-0 seed-generation collection was
> running (held `residency_scheduler.db`). Per the plan, only the ✅ SAFE set (source/doc authoring,
> read-only DB) was done. The ⛔ DB-writer steps (DDL migration, verification solves, CSV backfill,
> calibration/recalibration batches) are **deferred** until `.seed_pool.lock` is gone, no
> `HeadlessSolveRunner` java process is alive, and the JavaFX app is closed.

---

## 🐛 Phase 3 broke under seed-wiring — OPEN (see PHASE3_SEED_HANDOFF.md)

> **STATUS 2026-06-25: NOT fixed.** The "fix" below (fixVariablesToTheirHintedValue=ON) was later
> found to be a FALSE GREEN — it PINS the carried seed and fakes an instant Phase-3 OPTIMAL without
> optimizing. It has been REVERTED; Phase 3 defaults are now the honest UNKNOWN→fallback. The
> authoritative, current writeup (problem, the SIX configs tried, ground truth, next steps, run
> commands) is **`PHASE3_SEED_HANDOFF.md`** — read that, not the now-outdated section below.

### (historical, partially-wrong) original investigation notes

Investigating the probing fix's scope surfaced a deeper problem: **the seed pipeline silently
killed Phase 3's soft-objective optimization.** Because the prior phases now carry a FULL warm-start
assignment into Phase 3 (which hard-locks Tier-1/Tier-2 ≤ best), the default solver hit a bind:
- **probing ON + repairHint** → OR-Tools 9.9 native crash (`heuristics.fixed_search != nullptr`),
  aborting the process mid-Phase-3.
- **probing OFF + repairHint** → Phase 3 could not turn the carried hint into an incumbent → returned
  UNKNOWN even with a 600s budget (0 incumbents) → fell back to the **un-optimized Phase-2 result**.
  (Pre-seed-wiring, Phase 3 worked: old sweeps show it improving for 1200–2500s. The full carried
  seed is what changed.)

Ruled out by experiment: turning off the carried hint (still UNKNOWN); `fixVariablesToTheirHinted
Value=false` (still crashed with probing); `repairHint` off + probing on (no crash but still 0
incumbents).

**VERIFIED FIX (now the production default in Phase 3's solver):** `probing OFF + repairHint ON +
fixVariablesToTheirHintedValue ON`. fix-to-hint takes the carried assignment as the literal starting
incumbent (no crashing repair-search to establish feasibility); Phase 3 then optimizes the soft
objective from there and — because the Tier-locked region is small — reaches and PROVES the optimum
fast (objective == best_bound). Result: Phase 3 returns OPTIMAL and **commits the real Phase-3
result** (not the Phase-2 fallback), no crash, DB integrity ok. Solve time is real (seen 10–59s,
varying by seed), confirming it searches rather than merely echoing the hint. Env overrides
`PHASE3_PROBING` / `PHASE3_REPAIR_HINT` / `PHASE3_FIX_TO_HINT` are retained for future tuning; the
defaults are the verified-good settings.

---

## Verification findings (2026-06-24, seed lock cleared — items 0–3 DONE)

- **Item 0 — compile + math test: PASS.** `mvn compile` clean (all 5 Java files); `SolveStatsTest`
  9/9 pass (ICC + bootstrap diff-CI + Wilson delegation).
- **Item 1 — migration: already applied + verified.** The 4 `solve_run*` tables already existed
  (a HeadlessSolveRunner had run `DatabaseManager` after the DDL edit); their schemas match the DDL
  column-for-column, all empty, `PRAGMA integrity_check = ok`. Labeled backup taken first
  (`residency_scheduler.db.bak_premigration_*`).
- **🐛 Item 2 — seed-wiring verify exposed + FIXED a real OR-Tools crash.** The default-path seed
  replay is the FIRST code path that carries a FULL pooled seed (9119 hinted vars) all the way into
  **Phase 1's** optimization (FIX=cache COLLECT runs only ever did Phase 0, so this was never
  exercised). With `repairHint=true` + CP-SAT **probing presolve ON**, Phase 1 tripped the native
  assertion `Check failed: heuristics.fixed_search != nullptr` and aborted the process. A cold
  control run (`PHASE0_NO_SEED=1`) did NOT crash — it found no feasible Phase-0 (single-worker cold),
  so Phase 1 got no hints — confirming the trigger is the full carried hint set, not the seed logic.
  **Fix:** `configureSolver` now sets `setCpModelProbingLevel(0)` (probing was already off in
  `configureSolverPhase0`, and the comment there already calls it "expensive / unhelpful for SAT").
  After the fix the seeded solve runs clean end-to-end: P0 OPTIMAL 5.4s (vs a 120s cold budget),
  P1 OPTIMAL Tier-1=0, P2 OPTIMAL Tier-2=0, P3 ran its full budget, committed, DB integrity ok,
  seed `times_started` incremented + `recordOutcome` fired (best_run_tier1/2 = 0).
- **Item 3 — data layer + 3-way score parity: PASS.** `score_and_snapshot.py` wrote
  `SOLVE_RUN_ID` with all 4 tables: solve_runs (phase secs/status, seed_id, git, workers,
  config_json round-trips, version_id joins schedule_versions), solve_run_metrics
  (vol/frag/heal/hh/runs6 + **saturday_coverage=19**), solve_run_weekend (25 rows),
  solve_run_trajectory (0 when no CSV). Three-way parity holds: solver-internal Tier-1/2 = 0 ==
  reporter-printed = stored. (A log-parse regex was tightened so Phase 1/2 `result:` lines with an
  inline score still yield p1_secs/p2_secs/tier2.)

- **Item 5 — budget calibration: ran; P0–P2 calibrated, P3 plateau not yet exercisable.**
  `analyze_budget_calibration.py` over the seeded runs confirms the headline win: **with a seed,
  P0 ≈ 3–5s, P1/P2 finish in seconds** (vs the old 900/300/300 budgets — wildly oversized now).
  BUT Phase 3 returned UNKNOWN with an EMPTY trajectory on every seeded run — Tier-1/Tier-2 lock to
  0 and the strong seed already sits at a good point, so Phase 3 finds no improving incumbent to
  record. So the P3 plateau is **not measurable from these runs** (the tool falls back to "P3 = the
  budget we gave it," which is not evidence). **P3 calibration is deferred to item 6 / future
  configs that actually exercise Phase 3** (where it has room to improve the soft objective). The
  empirical takeaway for now: P0/P1/P2 can be cut to tens of seconds; only P3 needs a real budget,
  and how much depends on the config.

---

## What was built (✅ SAFE — source/docs only, no DB writes)

### Phase 1 — Cached-seed → Phase-1 wiring (`CpSatSchedulerEngine.java`)
Added a **default-path** seed warm-start. Previously seed replay was gated entirely behind
`PHASE0_FIX=cache`; a normal solve cold-searched Phase 0. Now, when `PHASE0_FIX` is empty (and not
Option A/B), if the year's pool is non-empty the engine:
- loads one cached feasible assignment via `loadCachedFeasibleHints(year)` (honors
  `PHASE0_SEED_SELECT=roundrobin` for fair coverage),
- applies it as hints to the **Phase-0 model** (`mc0`) — Phase 1 inherits the warm start through the
  existing Phase-0→Phase-1 hint carry-forward (`p0.hints()`), so no separate Phase-1 hinting,
- sets `runSeedId` to the chosen seed, which makes the run attributable to its seed and
  **automatically closes the `recordOutcome` loop** (already gated on `runSeedId != null`).

Reversible: empty pool ⇒ cold Phase 0 unchanged. Opt out with `PHASE0_NO_SEED=1`.

Log line on success: `Phase 0: Phase-1 seeded from seed_id=… (N vars, default-path replay).`

### Phase 2 — Maximal per-run data layer (new tables, same DB)
**`DatabaseManager.java`** — four new tables added to the `migrations[]` array (idempotent
`CREATE TABLE IF NOT EXISTS`, errors swallowed; legacy tables untouched):
- `solve_runs` — one durable row per real solve: config snapshot (`config_json`), `seed_id` FK,
  per-phase secs+status, headline tier scores, `version_id` FK→`schedule_versions`, `git_commit`,
  `data_epoch` separation key (default `post_fix_seeded`), `backfilled` flag.
- `solve_run_metrics` — wide sub-component breakdown (Tier-1 trio; α/β/γ/δ; ε/ζ/η; reporting
  metrics; `saturday_coverage`).
- `solve_run_weekend` — full 25-element per-weekend coverer vector (`run_id, weekend_index, coverers`).
- `solve_run_trajectory` — full Phase-3 objective-vs-time (`run_id, elapsed_s, objective,
  best_bound, cpsat_wall_s`).

**`SolveRunDAO.java`** (new, mirrors `Phase0CollectionRunsDAO`) — `Row`/`Metrics`/`TrajectoryPoint`
builders + `insertRun` (returns generated id) / `insertMetrics` / `insertWeekendVector` /
`insertTrajectory` / `countForYear`.

**`SolutionScoreReporter.java`** — added `ScoreBreakdown` + `computeBreakdown(...)`: returns the
analytic sub-components structurally (Tier-1 trio + α/β/γ/δ/ε, all == the printed report and the
solver's internal Tier scores) so the data layer can persist them without re-deriving in Python.
`buildReport`'s printed output is **unchanged** (parity invariant preserved). Also added
`computeSaturdayY7Coverage` (Phase-4 new metric).

### Phase 4 — Quality audit (AUDIT COMPLETE; re-weighting deferred)
See "Post-I1 audit verdict" below. The new **Saturday Y7 coverage** metric is wired into the
structured breakdown and the `solve_run_metrics.saturday_coverage` column.

### Phase 5 — Stats (authored; meaningful only once post-fix rows exist)
- `SolveStats.java` + `SolveStatsTest.java` — bootstrap diff-CI + Wilson/power, mirroring
  `SeedStats`.
- `analyze_solve_quality.py` (ICC), `analyze_config_compare.py` (A/B bootstrap diff-CI),
  `analyze_budget_calibration.py` (plateau detection). All open the DB **read-only**.
- `solve-stats` skill — runs all three over cumulative `solve_runs` data, one plain-language
  interpretation.

---

## Post-I1 audit verdict (Phase 4) — eligibility is CONSISTENT, no metric change needed

**Question:** Did the I1 fix (2026-06-23: Inpatient GI→Y7 Nights now hard-banned) change who counts
as an eligible Sunday-Y7 coverer, requiring the metric to follow?

**Finding: No.** The eligibility model is identical between the objective and the metric, and the I1
fix does not move it:

- **Objective** (`ObjectiveFunctionBuilder.buildSundayCoverageObjective`): a resident is an eligible
  coverer at weekend boundary b→b+1 iff `onSource(b) AND NOT enteringHeavy(b+1)`, where the sets come
  from `WorkloadTiers` (`SUNDAY_SOURCE`, `HEAVY`).
- **Metric** (`score_and_snapshot.py`): `g[w] in SRC and g[w] not in HEAVY and g[w+1] not in HEAVY`.
  Since `SRC ∩ HEAVY = ∅`, the `g[w] not in HEAVY` clause is redundant; this reduces to
  `onSource(w) AND NOT enteringHeavy(w+1)` — **the same rule.**
- **Set parity:** `score_and_snapshot.py`'s `HEAVY`/`MEDIUM`/`SRC` are byte-for-byte the same names as
  `WorkloadTiers.HEAVY`/`MEDIUM`/`SUNDAY_SOURCE`. (Single source of truth on the Java side is
  `WorkloadTiers`; the Python sets must continue to mirror it — flagged in OPERATIONS.md.)
- **Why I1 doesn't move eligibility:** the fix bans the *transition* Inpatient GI → Y7 Nights (a hard
  constraint in `ConstraintBuilder`). Inpatient GI remains a valid Sunday *source*; a resident on
  Inpatient GI at block b who would enter a heavy rotation at b+1 was **already** excluded by the
  `enteringHeavy(b+1)` clause. Banning one transition edge removes assignments, not eligibility
  semantics. So the volunteer/fragile/healthy counts are measured correctly under the new rule.

**Consequence for benchmarks:** the *definitions* are stable, but the *numbers* are not comparable
across the fix boundary (the feasible region shrank). Pre-2026-06-22 benchmarks (v7 fragile=11,
cfgA fragile=6, …) are **historical (pre-I1)** and must be relabeled as such; the post-fix R6 round
is the new canonical baseline. The empirical re-weighting (does floor-OFF/target-2 still win
post-I1?) is **deferred** — it needs post-fix `solve_runs` rows + the Phase-5 A/B analysis, which
requires the seed lock to clear.

---

## ⛔ Deferred until the seed lock clears (DB writers / irreversible)

1. **DDL migration** — run the app/engine once so `DatabaseManager` creates the four new tables.
   Take a labeled DB backup first. `PRAGMA integrity_check` must be `ok`; legacy tables byte-identical.
2. **Seed-wiring verification solve** — one headless solve must log
   `Phase-1 seeded from seed_id=…`, P0≈0s, Tier-1=0, and increment `phase0_seed_stats.times_started`.
3. **Data-layer wiring + verification** — finish populating the four tables from
   `score_and_snapshot.py` / `sweep_driver.py`; confirm three-way score parity
   (`solve_run_metrics` == `SolutionScoreReporter` printed == solver internal Tier scores).
4. **CSV backfill** (optional) — post-fix R6 rows from `sweep_results.csv` as
   `data_epoch=post_fix_seeded, backfilled=1`.
5. **Budget calibration batch** — run `analyze_budget_calibration.py`, set `DEFAULT_BUDGET`.
6. **Empirical re-weighting** — re-confirm the winning config post-I1 via `analyze_config_compare.py`.

---

## Files

**Created:** `db/SolveRunDAO.java`, `stats/SolveStats.java`, `stats/SolveStatsTest.java` (test tree),
`analyze_solve_quality.py`, `analyze_config_compare.py`, `analyze_budget_calibration.py`,
`.claude/skills/solve-stats/SKILL.md`, this file.

**Modified (source only):** `db/DatabaseManager.java` (4 tables), `cpsat/SolutionScoreReporter.java`
(structured breakdown + Saturday metric), `cpsat/CpSatSchedulerEngine.java` (default-path seed
hinting), `score_and_snapshot.py` + `sweep_driver.py` (populate new tables), `run-solver-config`
skill.

**Untouched (separation guarantee):** `schedule_versions`, `schedule_version_assignments`,
`sweep_results.csv`, existing `SCHEDULE_ITERATION_REPORT`, pre-fix backups.
