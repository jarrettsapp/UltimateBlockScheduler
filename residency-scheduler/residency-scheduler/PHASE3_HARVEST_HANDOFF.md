# HANDOFF: Phase-3 crash RESOLVED → pivot to mass-harvest. Remaining "still-to-do".

**Date:** 2026-06-25. **Read this fully before continuing.** It carries a finished investigation so a
fresh (cold-context) session can complete the remaining tasks without re-deriving anything. Companion
docs: `PHASE3_SEED_HANDOFF.md` (the ORIGINAL bug handoff — now superseded by this on the root cause),
`SCHEDULE_SEARCH_PLAN.md` (the locked search methodology), memory `phase3-seed-handoff.md` /
`schedule-search-plan.md` / `phase2-mass-harvest-idea.md`.

---

## 0. STATE OF THE TREE (so you don't break things)

- **No live solve, no `sweep.lock`, no `.seed_pool.lock`. DB integrity = ok.** Safe to compile/run.
- A stuck orphan solver (the sweep's last child) was pegging CPU to 100% and was KILLED. If CPU is
  high again, hunt for a JDK `java.exe` (NOT the VSCode redhat one, NOT the two idle Jun-19 ones) via
  `powershell "Get-Process java | Select Id,CPU,StartTime"` and `Stop-Process -Id <id> -Force`.
  **LESSON: killing a sweep/driver PID does NOT kill its in-flight child `java` solve — kill both.**
- **All changes are UNCOMMITTED** on branch `main` (~40 files in `git status`, most pre-existing from
  the moonlit-dragon build; this session added the edits in §2).
- DB backup taken before the schema change: `residency_scheduler.db.bak_preStatusMigration_*`.
- `solve_runs` has **3** rows (old verification rows; none are harvest data yet).

**DB-writer serialization still applies:** one writer at a time. Before any solve/migration confirm no
`HeadlessSolveRunner` java proc and no `sweep.lock`/`.seed_pool.lock`.

---

## 1. WHAT WAS RESOLVED (don't re-investigate)

**The bug:** seed warm-start made Phase 3 stop optimizing — it either crashed, stalled to UNKNOWN, or
faked OPTIMAL. **Root cause (CONFIRMED via full CP-SAT log + upstream issue):** OR-Tools bug
[google/or-tools#5025](https://github.com/google/or-tools/issues/5025). With `repair_hint=true` + a
multi-worker portfolio, a parallel hint-following subsolver (`fs_random` in our case; `shared_tree`
in theirs) dereferences a `fixed_search` heuristic that was never built, because the carried hint is
**partial** (9119 of ~24544 vars) and not-yet-feasible (`best:inf`) → native CHECK abort ~3s in.
**NOT fixed upstream as of 9.15 — an OR-Tools bump will not help.**

**Documented workarounds — ALL TESTED, all fail for our `fs_random` trigger** (don't repeat):
- disable `shared_tree` → doesn't help (our trigger is `fs_random`, not shared_tree).
- fewer workers (8) → still crashes.
- `repair_hint=false` → no crash but UNKNOWN / 0 incumbents (no optimization).
- raise `hint_conflict_limit` → still crashes.
- single worker (`PHASE3_WORKERS=1`) → no crash, BUT under full Tier-1=0/Tier-2=0 locks it returns
  UNKNOWN / 0 incumbents in 15 min (can't find a feasible Phase-3 start alone). The parallel
  first-solution workers are what FIND the start — so single-worker is not viable for quality.

**The one workaround that PREVENTS THE CRASH AND OPTIMIZES:** light **partial fix-to-hint** — pin a
small fraction of vars (`PHASE3_FIX_TO_HINT=on` + `PHASE3_HINT_FRACTION=f`). Pinning a few % builds the
`fixed_search` heuristic the crashing worker needs WITHOUT freezing the schedule (≈95% of vars stay
free → real FEASIBLE search, never a fake instant-OPTIMAL). Implemented (§2).

**BUT — the pin-fraction sweep proved partial-pin is UNRELIABLE (the decisive finding):**
whether Phase 3 finds a feasible incumbent is a **per-seed coin-flip at every fraction 1–10%**, not a
function of the fraction. Sweep results (10-worker, P3=900s, scored fragile/h→h):

| frac | runs | result |
|---|---|---|
| 1,2,4,5,6% | 1 each | UNKNOWN (too-free, no incumbent) |
| 3% | 3 | 1 GOOD (FEASIBLE, fragile **4**, obj 1270) + 2 UNKNOWN |
| 7% | 2 | 1 GOOD (FEASIBLE, fragile **7**, obj 1369) + 1 UNKNOWN |
| 10% (earlier, 1 run) | 1 | GOOD (FEASIBLE, fragile **8**, obj 1466) |

Two more hard facts from the logs that kill "fast harvest" ideas:
1. **UNKNOWN fallbacks burn the FULL ~920s budget** (no fast-skip): 919–949s every time.
2. **Engaging runs find their 1st incumbent LATE** (230–690s INTO Phase 3) — matches the
   [[plateau-convergence-finding]] "late cascade." So a SHORT P3 budget cuts off the winners.

**Conclusion that drove the pivot:** chasing a reliable single optimized schedule via pin is a
coin-flip that costs ~15 min/seed win-or-lose. BUT every run's quality is good either way — even the
Phase-2 **fallback** scored fragile 7–11 / h→h 0 (at/above v7 benchmark fragile 11). So:

**DECISION (user-confirmed): pivot to MASS-HARVEST now; return to Phase-3 optimization later.** Produce
many seeded schedules fast, record every one cleanly, keep the best; revisit targeted Phase-3
optimization on the most promising seeds afterward.

---

## 2. CODE ALREADY CHANGED THIS SESSION (compiles clean; uncommitted)

**`src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java`** (Phase-3 block ~line 955–1095):
- Phase-3 hint thinning: `PHASE3_HINT_FRACTION` (0<f≤1, default **1.0**) thins the carried hint;
  paired with `PHASE3_FIX_TO_HINT=on` pins only that fraction (~line 965–985).
- Phase-3 solver knobs + the multi-worker crash-avoidance: `PHASE3_PROBING` (default **on**),
  `PHASE3_REPAIR_HINT` (default **on**), `PHASE3_FIX_TO_HINT` (default **off**), `PHASE3_WORKERS`
  (default = config workers), `PHASE3_SHARED_TREE` (default 0, multi-worker only),
  `PHASE3_HINT_CONFLICT_LIMIT`, `PHASE3_KEEP_FJ`. FJ/shared-tree suppression is gated to
  `p3Workers > 1` (at 1 worker those cause `MODEL_INVALID` — already handled).
- `PHASE3_SOLVER_LOG=1` dumps OR-Tools' own log + effective params (diagnostic; default off).
- Helper `parseLongOr(key,default)` added near `envOr` (~line 2205).

**`src/main/java/com/residency/db/DatabaseManager.java`** (migrations array ~line 375):
- Added `run_status TEXT` + `final_objective REAL` to the `solve_runs` CREATE TABLE, plus idempotent
  `ALTER TABLE solve_runs ADD COLUMN …` migrations + `idx_solve_runs_status`. **Already applied to the
  live DB** (verified columns exist, integrity ok).

**`score_and_snapshot.py`** (`_write_solve_run`):
- Computes + writes the 3-state `run_status` and `final_objective`, and a CORRECT `feasible`:
  - `p3_status FEASIBLE` → `run_status='PHASE3_FEASIBLE'`, `final_objective`=last trajectory obj, feasible=1.
  - `p3_status OPTIMAL`  → `run_status='PHASE3_OPTIMAL'`, final_objective set, feasible=1.
  - `p3_status UNKNOWN/MODEL_INVALID/INFEASIBLE/none` → `run_status='PHASE2_FALLBACK'`,
    `final_objective=NULL` (NOT 0 — honestly absent), feasible=0.
- Removed a duplicate `_read_trajectory` call.
- **VERIFIED end-to-end** against real sweep logs: FEASIBLE log → PHASE3_FEASIBLE/1369.0/feasible=1;
  UNKNOWN log → PHASE2_FALLBACK/NULL/feasible=0. (This was the keystone the schedule-search-plan
  flagged ⚠️ UNVERIFIED — now resolved.)
- NOTE: the solve_runs write is still **gated behind `if not a.no_save`** (line ~198). Harvest runs
  must call `score_and_snapshot.py` WITHOUT `--no-save` (or refactor) so the row is written.

---

## 3. STILL TO DO (the remaining work — in order)

### (a) Decide + set the Phase-3 DEFAULT  ⟵ NEEDS A USER DECISION FIRST
Because no pin reliably engages Phase 3, a "default" run must pick a lane. **Open question for the
user (was about to be asked):** should a default run
  - **Option A:** attempt Phase 3 at a fixed pin (~7–10%) → ~⅓ chance it optimizes (fragile 4–8),
    else clean PHASE2_FALLBACK; OR
  - **Option B:** default to honest **Phase-2-only** (no Phase-3 attempt, never wastes ~900s) and make
    Phase-3 optimization an explicit opt-in for the later targeted phase.
For HARVEST specifically, Option B's spirit is right (don't burn 900s/seed). Set whatever is decided as
the env DEFAULTS in `CpSatSchedulerEngine.java` (or document the harvest invocation explicitly). Also
flip `PHASE3_HINT_FRACTION` default if a pin lane is chosen.

### (b) Build the mass-harvest tooling
Produce N seeded schedules and record each cleanly into `solve_runs`. Design notes:
- Each schedule = a seeded solve. For pure Phase-2 harvest, give P3 a TINY budget or skip it (the
  point is first-feasible-quality at scale; P0–P2 is near-instant with seeding, ~30–50s/run).
- Use `PHASE0_SEED_SELECT=roundrobin` so each run draws a distinct seed (fair pool coverage).
- DB-safety for parallelism is SOLVED: **`parallel_run.sh`** runs each solve against its OWN DB copy in
  `runs/<label>/` and `merge`s the winner back (integrity-checked). Reuse it, OR run sequentially.
  (⚠️ memory [[parallel-solver-runs]] warned 1–3 workers failed to seed in an OLD shakedown — re-verify
  parallel seeding works now that the seed pool is populated before relying on it.)
- After each run, call `score_and_snapshot.py` (WITHOUT `--no-save`, with `--solve-log` + `--traj-csv`
  + `--config-label` + `--data-epoch`) so the durable `solve_runs` row (with `run_status` +
  `final_objective` + fragile/healthy/h→h metrics) is written. Status filter:
  harvest pool = `WHERE run_status IN ('PHASE3_FEASIBLE','PHASE3_OPTIMAL','PHASE2_FALLBACK')`;
  ranking key = `final_objective` for real Phase-3, fragile/etc. readouts for fallbacks (per the plan,
  fallbacks are PARKED/not-ranked but RECORDED).
- Consider a small driver script (like `sweep_driver.py`/`p3_pin_sweep.sh` patterns) that loops M
  seeds, scores each, and a query to rank the harvest. Keep it CLEAN: one row per run, no overwrites.

### (c) Write the harvest section into `SCHEDULE_SEARCH_PLAN.md`
Document: how harvest produces+records runs (the §2/§3b mechanics), the intake `WHERE`/ranking, and the
**gated plan to RETURN to Phase-3 optimization** (e.g. take the top-K harvested seeds and run full-
budget partial-pin Phase 3 on just those — breadth-then-depth, per the user's "both" framing).

### (d) Clean up diagnostic scaffolding (after findings captured here)
Delete: `p3_matrix.sh`, `p3_pin_sweep.sh`, `p3_matrix_summary.txt`, `p3_pin_sweep_summary.txt`,
`p3_*_console.log`, the **22** `pinsw_*.log`/`pinsw_*.csv`, the session's `solve_*.log`/`traj_*.csv`
(36 files: solve_p3diag_A, solve_pfh*, solve_1w*, solve_2stage, solve_hconf, solve_10w_pin, traj_*).
Keep the historical `solve_cfgD*.log` (real benchmarks). The `PHASE3_SOLVER_LOG` code can stay (gated,
off by default) or be removed — low priority.

### (e) Update handoff doc + memory to RESOLVED
- Mark `PHASE3_SEED_HANDOFF.md` RESOLVED (point to this doc + the root cause).
- Update memory `phase3-seed-handoff.md` and `MEMORY.md`: drop the "OPEN BUG" framing → "RESOLVED:
  root cause #5025; partial-pin works but is seed-unreliable; pivoted to mass-harvest."
- Keep `phase2-mass-harvest-idea.md` but flip from PARKED → ACTIVE once harvest tooling lands.

---

## 4. KEY NUMBERS / BENCHMARKS (for sanity-checking harvest quality)
- v7 best (historical): fragile **11**, healthy 14, heavy→heavy **0**. cfgD FEASIBLE obj **1214**.
- Seeded Phase-2 fallbacks this session: fragile **7–11**, h→h **0** (consistently at/above v7).
- Seeded Phase-3 (when it engaged): fragile **4–8**, obj 1270–1466, always FEASIBLE (never instant OPTIMAL).
- A real Phase-3 success = FEASIBLE (not OPTIMAL), incumbents accumulating, obj decreasing over minutes.
- ALWAYS after a run: `PRAGMA integrity_check`; kill stray `HeadlessSolveRunner` (AND its child java).
