# Schedule Search Plan — methodically finding the best schedule

**Status:** ACTIVE via the **mass-harvest path** (§7). The Phase-3 optimization staged plan
(Stages 1–4) is now a **later, gated depth phase** — its Stage-0 prerequisite (a reliably-engaging
Phase 3) was proven *unattainable by any pin* (per-seed coin-flip; root cause OR-Tools #5025), so
we pivoted to harvest-first. See `PHASE3_HARVEST_HANDOFF.md` for the full investigation + decision.
**Authored:** 2026-06-25 (planning session); harvest section added 2026-06-25 (post-pivot).
All decisions below are user-confirmed.
**Related:** `PHASE3_HARVEST_HANDOFF.md`, `PHASE3_SEED_HANDOFF.md` (RESOLVED), `SOLVE_DATA_INFRA_PLAN.md`,
`PROJECT.md`; memories `schedule-search-plan`, `phase3-seed-handoff`, `phase2-mass-harvest-idea`,
`plateau-convergence-finding`, `phase0-acceleration` (KM playbook origin).

---

## 0. The goal

We can now mass-produce Phase-0 seeds (dozens–hundreds/day). The open problem is no longer
*generating* candidates — it's a **methodical way to produce and evaluate schedules, and to decide
which variables to target** when hunting for the best one. This doc is that method.

Guiding principle: **do not sweep a 6-dimensional grid** (thousands of runs). Peel variables off one
at a time in dependency order. **Each stage either kills a variable (proves it's noise) or fixes it
to a constant.** The search space shrinks at every step instead of exploding.

---

## 1. The variable space

### Levers (plausibly change schedule quality)
1. **Seed** — the Phase-0 feasible start fed to Phase 1. Open question: does it *predict* final
   quality? (ICC, Stage 1.)
2. **Phase-3 pin %** (fix-to-hint fraction) — owned by the other session. **This is a THROUGHPUT
   knob, not a quality lever** (see §3). Lead ≈ 10%.
3. **Phase-3 runtime (P3 budget)** — how long the soft objective optimizes. Real low plateau
   ~882–892 reached via a LATE cascade 1234–1861s; short budgets cut it off. (Stage 2.)
4. **Sunday-coverage weight** (w=75/95/120…) — may be largely irrelevant (w75 & w120 hit same floor
   pre-bug). (Stage 3.)
5. **Zero-volunteer floor (hard vs soft)** — changes feasibility, not just objective. (Stage 3.)
6. **P0/P1/P2 budgets** — feasibility/throughput; a starved P2 hands Phase 3 a worse start.
   Calibrated with the same trajectory tool as #3.

### Noise (average over, don't tune)
7. **Solver randomness** (10-worker fixed). The reason we repeat. "Are two configs really different?"
   = bootstrap diff-CI.
8. **Which draw plateaus in time** — much apparent "variance" is runs stopped pre-plateau. Fixed by
   adequate P3 budget (#3), not tuning.

---

## 2. Quality metric (LOCKED)

**Schedule quality = the Phase-3 objective value.** Single scalar, lower = better.

- **Not** a hand-built composite of separate metrics. Rationale: the objective already encodes the
  fragile/healthy/coverage weights the solver actually optimized; ranking on separately-extracted
  metrics could disagree with what the solver "sees."
- **No hard gates.** Nothing is disqualified at scoring time. A schedule with 1–2 heavy→heavy but
  phenomenal volunteer/fragile/healthy is worth considering. Everything is a trade-off *inside* the
  objective. (Distinct from the solver's Tier-1/Tier-2 = 0 locks, which shape feasibility, not eval.)
- **fragile / healthy / coverage / heavy→heavy** are kept as **descriptive readouts** next to the
  objective — never the ranking key.
- **Priority sanity check (not a separate formula):** confirm the objective's own weights already
  rank **fragile > healthy > coverage**. If they don't, that's a weight finding (lever #4), not a
  scoring change. We never maintain two competing definitions of "good."

---

## 3. Intake filter — which runs count (LOCKED)

**Record EVERY run, regardless of outcome.** Store **status + phase-of-origin + objective** on every
`solve_runs` row. Three statuses:

| Status | What it is | Treatment |
|---|---|---|
| **real FEASIBLE Phase-3** | genuine search, real objective | **enters ranking** |
| **UNKNOWN → Phase-2 fallback** | Phase 3 found nothing, fell back | **recorded + flagged PARKED**, excluded for now, revisit later (NOT discarded) |
| **instant OPTIMAL (frozen/high-pin)** | real number on a near-frozen schedule | **flagged but STILL USED as a normal Phase-3 run — CONTINGENT on count.** Rare → include. Common (pin drifted high) → revisit/exclude so they don't distort the data. |

**Why this is safe / the trust analysis:**
- OPTIMAL vs FEASIBLE is a *status*, not the score — both carry a real objective number.
- High-pin danger is NOT "score = 0"; it's a real, honestly-computed number on a *frozen* schedule
  (a false green). Frequency-monitoring the OPTIMAL flag handles it.
- The actual zero/missing risk is the *opposite* path: too-low pin → UNKNOWN, 0 incumbents →
  Phase-2 fallback → recorded objective may be a fallback/null.

**✅ RESOLVED (2026-06-25) — the objective column is trustworthy.** `score_and_snapshot.py`'s
`_write_solve_run` was verified end-to-end against real sweep logs: a FEASIBLE Phase-3 log →
`run_status='PHASE3_FEASIBLE'`, `final_objective`=last trajectory incumbent, `feasible=1`; an
UNKNOWN/no-Phase-3 log → `run_status='PHASE2_FALLBACK'`, `final_objective=NULL` (honestly absent,
**never `0`**), `feasible=0`. So a fallback can never masquerade as a great low score. The DB carries
`run_status` + `final_objective` columns (migrated + verified) for exactly this.

**Free bonus:** with status on every row, `COUNT(parked) / COUNT(all)` at a given pin **is** the
fallback rate — the yield metric the pin work optimizes against — with no extra build.

---

## 4. Pin % is a throughput knob (not a quality lever)

The other session owns the pin search. Its objective is to **minimize the fallback rate**: low
enough to give Phase 3 freedom, high enough to avoid Phase-2 fallback, not so high it freezes into
instant OPTIMAL. The same proportion-with-CI stats style we reuse everywhere measures it. This is
**Stage 0** and is the hard prerequisite for everything below — until the pin is honest, the very
*definition* of quality (the objective) is untrustworthy.

---

## 5. The staged plan

### Stage 0 — Unblock (prereq, other session)
Lock the pin band that reliably yields a *real* FEASIBLE Phase 3 (genuine objective descent, not
fallback, not frozen). **Gate:** a seeded run that runs Phase 3 to FEASIBLE with real improvement.
Also land the status/phase-of-origin recording (§3) and resolve the UNVERIFIED check.

### Stage 1 — Settle the seed (ICC)
With pin fixed, run the **same config across many different seeds**, capture final objective. Run
`solve-stats` → ICC.
- ICC low → **drop seed as a lever** (any feasible seed is fine; stop cherry-picking). Big collapse.
- ICC high → seed becomes a ranked input; screen cheaply, run Phase 3 only on the best.

### Stage 2 — Settle the runtime (Kaplan-Meier on time-to-plateau)
This is the "don't waste 10 min when we reliably stop improving" question, done rigorously.
- Phase 3 is a *value-over-time* problem, not Phase-0's *time-to-event* problem. Convert it: define
  the event = **"run reached within ε% of its OWN final objective."** Each trajectory → a
  **time-to-plateau**. (ε is well-defined because quality is a single scalar.)
- Run **Kaplan-Meier on those times with a confidence band** (reuse the Phase-0 KM playbook).
- **Stop time = the lower-CI time by which a high fraction (e.g. 95%) have plateaued.** Past it
  you're paying for the <5% still improving — quantified, not guessed. (plateau memory's eyeballed
  1234–1861s late cascade becomes a defensible number with error bars.)
- **Infra checkpoint:** verify whether `analyze_budget_calibration.py` does KM-with-CI or just
  mean/percentile; if the latter, upgrade it to time-to-plateau KM with bands, mirroring
  `seed-pool-stats`.
- Output: lock P3 (and P0/P1/P2) to the smallest budget that reliably reaches the plateau →
  runtime becomes a constant, maximizing throughput.

### Stage 3 — Remaining objective levers (small A/B)
Only now, with seed + budget + pin fixed: test **floor (hard/soft)** and **coverage weight** via
**bootstrap diff-CI** on the objective. If the CI confirms weight washes out (as the plateau finding
predicts), drop it too. Also do the fragile>healthy>coverage weight sanity check (§2).

### Stage 4 — Production protocol
Lock whatever survives 1–3 into a fixed recipe. Production move: **run the locked config N× over
[good seeds], keep the best plateaued draw** (objective-ranked, filtered to real FEASIBLE Phase-3).
Mass-harvest (`phase2-mass-harvest-idea`) is the hedge if Phase 3 stays fragile.

---

## 6. Tooling map

| Stage | Question | Tool |
|---|---|---|
| 0 | lowest stable pin / fallback rate | other session; `COUNT(parked)/COUNT(all)` |
| 1 | does seed predict quality? | `analyze_solve_quality.py` (ICC) / `solve-stats` |
| 2 | reliable stop time | `analyze_budget_calibration.py` (upgrade to KM+CI) + `SOLVE_TRAJECTORY_CSV` |
| 3 | is config B really better? | `analyze_config_compare.py` (bootstrap diff-CI) / `solve-stats` |
| 4 | run N× keep best | `run-solver-config` / `sweep_driver.py` |
| 7 | mass-harvest N seeded Phase-2 schedules | `harvest_driver.py` (§7) |

All three stats tools (ICC, A/B CI, budget calibration) already exist and are verified
(`solve-data-infra-built`).

---

## 7. Mass-harvest path (ACTIVE) — breadth before depth

**Why this exists.** The staged plan above assumed Stage 0 would deliver a Phase 3 that *reliably*
engages. It does not, and cannot via pinning: with the seed warm-start, whether Phase 3 finds a
feasible incumbent is a **per-seed coin-flip at every pin fraction 1–10%** (root cause: OR-Tools
[#5025](https://github.com/google/or-tools/issues/5025), not fixed upstream as of 9.15), and a
non-engaging run **burns the full ~900s P3 budget** win-or-lose. But every run's Phase-2 result
already scores **at/above the v7 benchmark** (fragile 7–11, h→h 0; v7 was fragile 11). So the
high-yield move is **breadth before depth**: mass-produce many seeded Phase-2 schedules cheaply,
record each cleanly, cherry-pick the best, and revisit targeted Phase-3 optimization on only the
top-K seeds later. (User framing: "do both" — harvest now, Phase-3 depth later.)

### 7.1 How a harvest produces + records runs
- **Default is Phase-2-only.** `CpSatSchedulerEngine` now skips the Phase-3 build/solve entirely
  unless `PHASE3_SKIP=0` (env, default on). A normal/UI/harvest run never wastes the P3 budget;
  Phase-3 optimization is an **explicit opt-in** for the depth phase (§7.4).
- **Driver:** `harvest_driver.py --count N [--batch-tag TAG] [--detach]`. Sequential (one DB writer
  at a time), reusing `sweep_driver.py`'s hardened primitives (launch / completion-driven wait /
  integrity gate / job-object-breakaway detach / lockfile). Its lock/state/output files
  (`harvest.lock`, `harvest_state.json`, `HARVEST_STATUS.md`, `harvest_results.csv`,
  `harvest_runs/`) are **distinct from the sweep's**, so the two never collide.
- **Each unit:** launches `HeadlessSolveRunner 2 300 120 120 5` with `PHASE0_SEED_SELECT=roundrobin`
  (distinct seed each run → fair pool coverage) and `PHASE3_SKIP=1`. Seeded P0–P2 is near-instant,
  so a run is ~30–90s. After it exits, calls `score_and_snapshot.py` **WITHOUT `--no-save`** and
  **WITH** `--config-label / --data-epoch / --solve-log / --traj-csv`, so one durable `solve_runs`
  row lands (plus `solve_run_metrics`).
- **Validity (harvest criterion):** a run counts as OK iff it committed a **feasible** schedule with
  **Tier-1 = 0** (the hard inpatient-split lock). We do **not** require any Phase-3 signal here
  (unlike the sweep, which gates on Phase-3≠UNKNOWN + trajectory rows).

### 7.2 Intake `WHERE` + ranking (harvest)
Harvest rows are recorded by `score_and_snapshot` as **`run_status='PHASE2_FALLBACK'`** — by design,
because a Phase-2-only run has no "Phase 3 result:" line. **Two cautions when querying:**
- `solve_runs.feasible = 0` on a harvest row means **"no Phase-3 incumbent," NOT "schedule
  infeasible."** The committed schedule is feasible (Tier-1 = 0, validated). Don't filter harvest on
  `feasible=1`.
- `final_objective` is **NULL** for harvest rows (no Phase-3 objective exists). **Never rank harvest
  by `final_objective`.** Rank by the descriptive metrics instead.

**⚠️ MUST filter out truncated phases (data hygiene, found 2026-06-25).** A harvest run whose Phase 1
or Phase 2 *timed out* (status UNKNOWN, not OPTIMAL) still commits a schedule and still gets a
`solve_runs` row WITH metrics — but that schedule rests on a **non-optimized** clinical/quality phase
and is NOT worth keeping (user's "suboptimal P1/P2 isn't worth pursuing"). Example: ramp15 run 003
timed out in P1 (`p1_status='UNKNOWN'`, `tier1_score=NULL`) yet scored frag 5 and would otherwise rank
3rd. The status columns to filter on are **already recorded** on every row (`p0/p1/p2_status`,
`tier1_score`) — no schema/code change needed; the filter just has to be in the query.

Pool + ranking (clean harvest only):
```sql
-- harvest pool (this batch): join metrics, EXCLUDE truncated phases, rank by quality
SELECT r.id, r.run_at, r.seed_id, m.fragile, m.heavy_heavy, m.healthy, m.volunteer, r.version_id
FROM solve_runs r JOIN solve_run_metrics m ON m.run_id = r.id
WHERE r.run_status = 'PHASE2_FALLBACK'           -- harvest rows
  AND r.config_label LIKE 'harvest-%'            -- this campaign
  AND r.p0_status = 'OPTIMAL'                    -- ⟵ hygiene: every phase proved OPTIMAL,
  AND r.p1_status = 'OPTIMAL'                    --    so the schedule isn't resting on a
  AND r.p2_status = 'OPTIMAL'                    --    truncated (timed-out) phase
  AND r.tier1_score = 0                          --    hard inpatient-split lock satisfied
ORDER BY m.fragile ASC, m.heavy_heavy ASC, m.healthy DESC;   -- best schedules first
```
Ranking key for harvest = **fragile ASC, then heavy_heavy ASC, then healthy DESC** (the descriptive
readouts of §2; the Phase-3 *objective* is unavailable until depth). This is consistent with §3's
rule that fallbacks are RECORDED but PARKED from the Phase-3-objective ranking — harvest simply makes
"parked" the whole point, and ranks the park by metrics. (`harvest_driver.py` also writes its own
`valid` flag to `harvest_results.csv` — `valid=False` ⇔ a truncated/Tier-1≠0 run — as a quick CSV-level
mirror of the SQL hygiene filter.)

### 7.3 Operating a harvest
```
python harvest_driver.py --count 1  --batch-tag smoke   # smoke test (one seeded run)
python harvest_driver.py --count 25 --detach            # unattended batch (survives the shell)
```
Watch `HARVEST_STATUS.md` / `harvest_results.csv` / `harvest_driver.out.log`. Safety is inherited
from the sweep's preflight (branch check, compiled trajectory artifact, `cp.txt`, DB integrity, and
a refusal to start if another `HeadlessSolveRunner` is already writing the DB). After a batch:
`PRAGMA integrity_check` and confirm no stray `HeadlessSolveRunner` java (kill it AND its child).

### 7.4 Gated return to Phase-3 optimization (the depth phase)
Once a harvest pool exists, take the **top-K seeds** (best fragile/h→h/healthy from §7.2) and run the
*existing* staged plan (Stages 1–4) on **only those seeds**, with `PHASE3_SKIP=0` and full-budget
partial-pin Phase 3. This is breadth-then-depth: harvest narrows the seed space cheaply; Phase-3
optimization then spends its expensive ~900s budget only where it's most likely to pay off. The
per-seed coin-flip is tolerable when applied to a curated short-list rather than the whole pool.
