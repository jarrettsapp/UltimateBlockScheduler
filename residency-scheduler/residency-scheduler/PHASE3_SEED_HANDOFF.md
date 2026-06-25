# HANDOFF: Phase 3 broke under seed-wiring — the one open problem

> **✅ RESOLVED (2026-06-25). Superseded by `PHASE3_HARVEST_HANDOFF.md` — read that first.**
> Root cause CONFIRMED = OR-Tools bug [#5025](https://github.com/google/or-tools/issues/5025)
> (`repair_hint=true` + multi-worker portfolio + a *partial*, not-yet-feasible carried hint →
> a parallel hint-following subsolver, `fs_random` for us, dereferences a `fixed_search`
> heuristic that was never built → native CHECK abort ~3s in). NOT fixed upstream as of 9.15.
> The light **partial fix-to-hint** workaround prevents the crash and optimizes, BUT a pin-fraction
> sweep proved it is **seed-unreliable** (per-seed coin-flip at every fraction 1–10%; non-engaging
> runs burn the full ~900s budget). **DECISION (user-confirmed): pivot to MASS-HARVEST** (default is
> now honest Phase-2-only via `PHASE3_SKIP`, opt back into Phase 3 with `PHASE3_SKIP=0`); return to
> targeted Phase-3 optimization on the top-K harvested seeds later. Full investigation + the
> remaining-work checklist live in `PHASE3_HARVEST_HANDOFF.md`. The text below is the ORIGINAL
> open-bug handoff, kept for history; its "open problem" framing is obsolete.

**Date:** 2026-06-25. **Read this fully before touching Phase 3 or running solves.** It exists so you
do NOT re-run experiments already done or re-derive findings already proven. The broader build
(the "moonlit-dragon" plan) is DONE and verified; this handoff is about the **single remaining
defect** it surfaced: the seed warm-start silently destroyed Phase 3's optimization.

Companion docs (read after this): `SOLVE_DATA_INFRA_PLAN.md` (full build log + verification),
memory `solve-data-infra-built.md`. The original plan: `~/.claude/plans/okay-we-now-have-moonlit-dragon.md`.

---

## 0. STATE OF THE TREE RIGHT NOW (so you don't break things)

- **No live solve, no `sweep.lock`, DB `integrity_check = ok`.** Safe to compile/run.
- **All changes are UNCOMMITTED** (working tree). Nothing is on a branch of its own. Current git
  branch = `main`. The trajectory-capture code IS on main.
- `target/classes` is compiled with the CURRENT source (Phase 3 defaults = HONEST setting, see §4).
- `residency_scheduler.db` has **3** `solve_runs` rows (early valid verification rows; the
  false-green cfgI6 rows were DELETED). Labeled backups exist:
  `residency_scheduler.db.bak_premigration_*` and `backups/residency_scheduler.*.sweep-start.db`.
- The item-6 A/B sweep is **STOPPED** (it was producing false-green data — see §3). `queue.jsonl`
  still holds the cfgI6A/cfgI6B lines; the pre-existing R6 lines are in `queue.jsonl.bak_preItem6`.
- Diagnostic env knobs exist on Phase 3 (see §4) — defaults are safe/honest.

**DB-writer serialization rule still applies:** only ONE writer to `residency_scheduler.db` at a
time. Before any solve/migration, confirm no `HeadlessSolveRunner` java proc and no `phase0_seed_pool.sh`
bash proc are alive (these respawn solves), and no `sweep.lock`. The user's seed-gen has been stopped.

---

## 1. THE PROBLEM (what we're actually trying to fix)

The solver runs 4 phases on the live year-2 schedule (real data, ~9119 occupancy vars):
- **Phase 0** = find ANY feasible assignment (hard; cold it took up to 900s, often failed).
- **Phase 1** = minimize Tier-1 (clinical) → reaches 0.
- **Phase 2** = minimize Tier-2 (coverage/variance/PGY) → reaches 0.
- **Phase 3** = minimize the SOFT objective (2+1 pattern + Sunday-coverage shortfall +
  categorical-soft excess) with **Tier-1 ≤ 0 AND Tier-2 ≤ 0 hard-locked**. This is where schedule
  QUALITY (fragile/volunteer weekends, pattern) actually gets optimized.

**The moonlit-dragon build added seed-wiring:** Phase 0 now replays a cached feasible "seed" from a
pool as a warm-start hint, collapsing Phase 0 from ~900s to ~3s. **This is verified and works.**

**The defect:** the seed-warm-started pipeline carries the assignment forward (P0→P1→P2→P3 via
`p0.hints()` → `p2.hints()`), and **Phase 3 stopped optimizing**. Depending on solver params it
either crashes, stalls to UNKNOWN, or fakes an OPTIMAL. The soft objective is no longer minimized —
we silently ship the un-optimized Phase-2 schedule. **That is the bug to fix.**

User's core concern (correct): *Phase 3 should essentially NEVER return OPTIMAL on this model* — the
competing constraints make it a long FEASIBLE-only search. Any instant Phase-3 OPTIMAL is a red flag.

---

## 2. GROUND TRUTH: what a HEALTHY Phase 3 looks like (from historical real-data logs)

Pre-seed-wiring runs on the SAME locks (Tier-1=0, Tier-2=0) — see `solve_cfgD.log`,
`solve_cfgD_run1.log` (real data, 2026-06-22):
- Phase 3 ran the **FULL budget** (e.g. 4991s, 2635s), committed **FEASIBLE** (never OPTIMAL),
  `obj=1214` / `obj=1092`, **millions of branches** (2.6M, 3.5M), and improved incrementally.
- Crucially: those runs ALSO carried the cold-Phase-0 hint into Phase 3 **and it worked fine.**

So: **a carried hint into Phase 3 is NOT inherently the problem.** Historically probing was ON, a
full hint was carried, and Phase 3 searched for 40+ min producing real improvement. THAT is the
behavior to restore.

---

## 3. EVERYTHING ALREADY TRIED ON THE SEEDED PIPELINE (do NOT repeat these)

All on real data, seeded Phase 0. Phase 3 has Tier-1≤0 + Tier-2≤0 locked. Results:

| # | Phase-3 config tried | Result | Meaning |
|---|---|---|---|
| A | probing **ON** + repairHint ON (the historical default) + carried hint | **CRASH**: `Check failed: heuristics.fixed_search != nullptr` (~20s in, native OR-Tools 9.9 abort) | The carried-hint + probing combo crashes in THIS build. |
| B | probing **OFF** + repairHint ON + carried hint | Phase 3 = **UNKNOWN, 0 incumbents** even at 600s budget → falls back to un-optimized Phase 2 | Without probing, Phase 3 can't even establish a feasible incumbent. |
| C | probing OFF + repairHint ON + **fixVariablesToTheirHintedValue=ON** + carried hint | Phase 3 "**OPTIMAL**" in ~0.9s, 1 incumbent, obj==best_bound | **FALSE GREEN.** fix-to-hint PINS all 9119 vars to the seed, so Phase 3 confirms the input and relabels Phase-2 quality as "optimal." NOT real optimization. This was my mistaken "fix" — reverted. |
| D | probing ON + repairHint **OFF** + carried hint | No crash, but **UNKNOWN, 0 incumbents** | repairHint is the crash trigger (with probing); without it Phase 3 won't adopt the hint as an incumbent. |
| E | probing OFF + carried hint **SKIPPED** (PHASE3_SKIP_HINT=1) | UNKNOWN, 0 incumbents | No hint + no probing → can't find a feasible start. |
| F | probing **ON** + carried hint **SKIPPED** + P3=900s (the "honest historical-equivalent" test) | Ran 6.5 min, **0 incumbents** (no crash, because no hint), killed | With both tiers locked, Phase 3 CANNOT find even one feasible point on its own in minutes. **The hint is what gives Phase 3 its only feasible start.** |

**Key deductions from the matrix:**
1. The crash (A/D) = **repairHint + probing + a carried hint**, together. Remove any one and no crash.
2. Phase 3 genuinely **needs the hint** to have a feasible incumbent (E, F prove it can't self-start).
3. Phase 3 genuinely **needs probing** to do anything useful (B: probing off → nothing).
4. fix-to-hint "works" only by pinning = fake (C).

So the bind is: **Phase 3 needs (probing ON) AND (the carried hint as a movable starting incumbent),
but probing+repairHint+hint crashes.** Historically that exact combo worked — so something in THIS
build's path differs.

---

## 4. CURRENT CODE STATE (what's wired, where, defaults)

File: `src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java` (package `com.residency`).

- **Phase 0 seed wiring** (~line 729, the `else if (p0fix.isEmpty() ...)` branch): default-path seed
  replay into `mc0`, sets `runSeedId`, uses `configureSolverPhase0` (probing off, stop-after-first)
  for the seeded validate. Env `PHASE0_NO_SEED=1` disables. **This part is GOOD — leave it.**
- **`configureSolver(config, sec)`** (~line 2150) now delegates to
  `configureSolver(config, sec, allowProbing=false)`. So **probing defaults OFF for Phase 1/2** (and
  any caller using the 2-arg form). NOTE: this was my blunt fix; it is fine for P1/P2 (they finish in
  seconds) but is part of why P3 needs care.
- **Phase 3 solver block** (~line 1000-1024): env-tunable, DEFAULTS ARE HONEST:
  - `PHASE3_PROBING` (default **off**), `PHASE3_REPAIR_HINT` (default **on**),
    `PHASE3_FIX_TO_HINT` (default **off**).
  - With defaults, Phase 3 currently returns UNKNOWN→falls back to Phase 2 (honest, not faked).
  - `PHASE3_SKIP_HINT=1` (just before the solver block, ~line 960) omits the carried hint.
  - Logs `Phase 3 solver: probing=X repairHint=Y fixToHint=Z`.
- `envOr(key, default)` helper added near `configureSolver`.

**`git diff src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java`** shows all of the above as
uncommitted. The data-layer / stats / Python / skill changes (the rest of the plan) are DONE and
verified — don't disturb them.

---

## 5. WHAT TO TRY NEXT (ranked — these were NOT yet tried)

The goal: Phase 3 with **probing ON** + a **carried hint used as a *movable* starting incumbent**
(not pinned), without the crash. The crash is the blocker. Promising untried angles:

1. **Find why probing+hint+repairHint crashes NOW but didn't historically.** The build added
   `configureSolverPhase0` to the seeded P0 path and may have changed worker/seed params that leak
   into the model. Diff the *effective* CP-SAT params of a historical-good run vs now. Suspect:
   `num_workers`, `stop_after_first_solution` leaking, or a presolve param. Try probing ON +
   repairHint ON + hint, but with `num_workers=1` or default workers, or
   `setSearchBranching(AUTOMATIC_SEARCH)` explicitly, to see if the `fixed_search` heuristic that the
   assertion references gets installed properly.
2. **Hint as a SOLUTION HINT that CP-SAT may violate (not repaired-then-fixed).** OR-Tools has
   `setHintConflictLimit(N)` and the hint can be a soft suggestion. The combo
   `repairHint=true` + `fixVariablesToTheirHintedValue=false` (the DEFAULT for fix) with probing ON
   was config A = crash. But try `repairHint=false` + probing ON + `useOptimizationHints` semantics,
   or feeding the prior solution via `model.addHint` only on a SUBSET of vars (not all 9119), so
   Phase 3 has a warm region but freedom to move (this is the likely real fix — a PARTIAL hint).
3. **Two-stage Phase 3:** first a short solve with probing ON + hint to ADOPT an incumbent (no
   optimize), then continue/resolve from that incumbent with probing for the real optimize. I.e.
   decouple "establish a feasible incumbent from the hint" from "optimize the soft objective."
4. **Upgrade/patch OR-Tools.** The crash is an OR-Tools **9.9.3963** native assertion
   (`heuristics.fixed_search != nullptr`). Check if a newer ortools-java release fixed this hint+probing
   bug — if so, the whole bind may evaporate (config A would just work). Version is in `cp.txt` /
   `pom.xml` (`com.google.ortools:ortools-java:9.9.3963`).

**Recommended first step:** angle #2 (partial hint) — give Phase 3 the hint on a *fraction* of vars
so it has a warm start AND search freedom AND doesn't trip the all-vars repair path. Quick to test.

---

## 6. HOW TO RUN A PHASE-3 EXPERIMENT (exact commands)

```bash
cd "c:/Users/Jarrett/Desktop/Block Schedule app/residency-scheduler/residency-scheduler"
mvn -q compile                      # after any edit
[ -f cp.txt ] || mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
CP="target/classes;$(cat cp.txt)"
export PHASE0_SEED_SELECT=roundrobin SOLVE_TRAJECTORY_CSV="traj_test.csv"; rm -f traj_test.csv
export PHASE3_PROBING=on PHASE3_REPAIR_HINT=on PHASE3_FIX_TO_HINT=off   # set per experiment
# HeadlessSolveRunner <year=2> <P0> <P1> <P2> <P3>  (seconds)
nohup java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 60 120 120 900 > solve_test.log 2>&1 &
```
**Read the result by:** `grep -E "Phase 3 solver|Phase 3 result|committing|Check failed" solve_test.log`
and `wc -l traj_test.csv` (incumbents = lines − 1). **A REAL Phase-3 success = FEASIBLE (not OPTIMAL),
many incumbents accumulating over minutes, obj DECREASING.** Instant OPTIMAL or 0 incumbents = still
broken. Compare the final 2+1 pattern block count in the score report against the pre-Phase-3 value
to confirm Phase 3 actually MOVED the schedule.

Always `PRAGMA integrity_check` after; kill stray `HeadlessSolveRunner` before the next run.

---

## 7. THE REST OF THE PLAN (DONE — for context, don't redo)

moonlit-dragon items 0–5: compile + `SolveStatsTest` (9/9) PASS; DDL migration (4 tables
`solve_runs`/`solve_run_metrics`/`solve_run_weekend`/`solve_run_trajectory`) applied + schema-verified;
seed-wiring verified (P0 900s→3s, Tier-1=0, `times_started++`, `recordOutcome` fires); data-layer +
3-way score parity PASS; budget calibration ran; CSV backfill = N/A (pre-I1). Stats stack authored +
read-only: `analyze_solve_quality.py` (ICC), `analyze_config_compare.py` (A/B bootstrap),
`analyze_budget_calibration.py`, `stats/SolveStats.java`+test, `.claude/skills/solve-stats`.
`score_and_snapshot.py` writes the solve_runs family; `sweep_driver.py` passes `unit=unit` +
`REQUIRED_BRANCHES` now includes `main`.

**Item 6 (w95 vs w120 re-weighting A/B) is BLOCKED on the Phase-3 fix** — a meaningful config
comparison is impossible while Phase 3 doesn't optimize (every run would just be the Phase-2
fallback). Finish §5 first, THEN run the item-6 sweep (`queue.jsonl` has cfgI6A/cfgI6B) and
`python analyze_config_compare.py cfgI6A cfgI6B residency_scheduler.db post_fix_seeded`.

**Post-I1 audit (Phase 4) is DONE:** Sunday-eligibility metric == objective (both
`onSource(b) AND NOT enteringHeavy(b+1)`); `WorkloadTiers` == the Python SRC/HEAVY/MEDIUM sets; I1
banned a transition, not eligibility. Pre-fix benchmarks are historical, not comparable.
