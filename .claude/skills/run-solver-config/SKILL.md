---
name: run-solver-config
description: Run a residency block-schedule solver configuration headless end-to-end — write the config, launch the CP-SAT solve, verify it, score it, snapshot it as a saved version, render the schedule grid, and update the iteration report (md/html/pdf). Use whenever the user wants to test/compare solver configurations (zero-volunteer floor on/off, sunday coverage target/weight, phase time budgets), run a config N times to gauge variance, or document a solve in SCHEDULE_ITERATION_REPORT. Triggers: "run config", "try floor off target 2 weight X", "run cfg... a few times", "test this configuration".
---

# Run a solver configuration (headless, end-to-end)

Project dir (all commands run here):
`c:/Users/Jarrett/Desktop/Block Schedule app/residency-scheduler/residency-scheduler`

**⚠ FIRST: be on git branch `feature/solver-trajectory-capture`** (the trajectory-capture code lives
only there; otherwise the SOLVE_TRAJECTORY_CSV files are empty). `git checkout
feature/solver-trajectory-capture`, confirm `git branch --show-current`, `grep -c TrajectoryCallback
src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java` (>=3), then `mvn -q compile`. If
checkout fails on uncommitted changes, STOP and ask the user.

This skill runs ONE config (optionally N times). Each run is fully documented — scores
AND the full schedule grid — even when running the same config repeatedly (variance matters;
never silently discard a run). Background: [[solver-tuning-experiment]], [[schedule-comparison-recipe]].

## Parameters to collect (ask only if ambiguous)
- **label** — scheme is `cfgR<round>-<NN>` (e.g. `cfgR3-01`); see [[design-solver-batch]] for why.
  Old single-letter labels (`cfgA`–`cfgF`) are historical. Plus a one-line description.
- **floor**: zero-volunteer hard floor ON/OFF (`enforce_zero_volunteer_weekends`).
- **target**: `sunday_coverage_target` (2 keeps the volunteer:fragile penalty ratio at 2:1, which
  holds volunteers low; 3 makes volunteers relatively cheaper → MORE volunteers. Prefer 2 unless
  the user wants to explore.).
- **weight**: `weight_sunday_coverage` (how hard coverage competes in Phase 3; cfgA used 75).
- **phase budgets** (seconds): STANDARD 10-worker default = `900 300 300 2400` (Phase 0=15min,
  1=5min, 2=5min, 3=40min). Based on measured behavior: phases 1/2 finish in 2-5s once seeded;
  Phase 0 seeds in ~2-8min @10w; Phase 3 plateaued ~16min in the one measured run, so 40min is a
  safe cushion. Phases exit EARLY on OPTIMAL; only Phase 3 uses its full budget. KEEP workers=10
  (low worker counts FAIL to seed — see [[parallel-solver-runs]] shakedown). Run configs SEQUENTIALLY.
- **runs**: how many times to run this exact config (default 1). For N runs: same config back-to-back,
  one at a time; snapshot AND document EVERY run (versions <label>-r1, <label>-r2…) — never discard a
  draw (variance is the point). Report a summary table of all N at the end.

## Procedure (per run)

### 1. Write config to the DB (this IS what runs — no UI/GUI override headless)
```bash
python - <<'EOF'
import sqlite3
db=sqlite3.connect('residency_scheduler.db'); c=db.cursor()
def setk(k,v): c.execute("INSERT INTO schedule_config(config_key,config_value) VALUES(?,?) ON CONFLICT(config_key) DO UPDATE SET config_value=excluded.config_value",(k,str(v)))
setk('enforce_zero_volunteer_weekends', 'true'|'false')   # FLOOR
setk('sunday_coverage_target', TARGET)
setk('weight_sunday_coverage', WEIGHT)
# tier lists MUST be non-empty or the objective is silently disabled:
setk('heavy_rotation_ids','1,3,6,11,14,16')
setk('sunday_source_rotation_ids','19,15,2,4,8,9,13,17,18,21')
db.commit(); print('written; integrity', c.execute('PRAGMA integrity_check').fetchone()[0][:20])
EOF
```

### 2. Ensure classpath, then launch headless (background)
```bash
# rebuild cp.txt if missing:
[ -f cp.txt ] || mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
[ -d target/classes ] || mvn -q compile
CP="target/classes;$(cat cp.txt)"
# Capture the Phase-3 trajectory so score_and_snapshot can persist it into solve_run_trajectory
# (and so analyze_budget_calibration can size P3 from the plateau). One CSV per run.
export SOLVE_TRAJECTORY_CSV="traj_<label>_runN.csv"
nohup java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 P0 P1 P2 P3 > solve_<label>_runN.log 2>&1 &
```
`HeadlessSolveRunner <year> <t0> <t1> <t2> <t3>` reads schedule_config, solves, commits to LIVE
(`assignments` table), and logs a solver_runs row. Year is 2.

### 2b. Report start + presumed-end in Central time (REQUIRED — see [[run-timing-reporting]])
Right after launch, tell the user three lines in Central time (CDT summer=UTC-5, CST winter=UTC-6):
- START, PRESUMED MAX END = start + sum of ALL phase budgets (100% of each), CHECK = end + 10 min.
```bash
python - <<'EOF'
import datetime,os
budgets=[P0,P1,P2,P3]; total=sum(budgets); start=os.path.getmtime("solve_<label>.log"); off=5  # 5=CDT,6=CST
lbl='CDT' if off==5 else 'CST'
f=lambda e:(datetime.datetime.utcfromtimestamp(e)-datetime.timedelta(hours=off)).strftime('%I:%M %p '+lbl+' (%a %b %d)')
print("START:",f(start)); print("PRESUMED MAX END:",f(start+total)); print("CHECK:",f(start+total+600))
EOF
```
For SHORT/single runs: schedule the completion check 10 min after the presumed max end.

For LONG or PARALLEL runs, use the TWO-STAGE tightening (the sum-all-budgets end is a loose worst
case since P0-2 exit early; only Phase 3 runs full):
- Schedule a CALIBRATION check at `start + sum(P0,P1,P2) + 2min` (Phase 3 is guaranteed started by then).
- At calibration, read each slot's actual Phase-3 start from its log ("Phase 3 ▶ … (limit:…)" line's
  [Ns] timestamp). Accurate end = phase3_start + P3_budget. For parallel, report per-slot ETAs AND
  the batch max (latest slot). Then schedule the completion check for batch-max-end + 10 min.

### 3. Poll (at the calibrated end +10 min; for very long runs, optional hourly health checks)
Check `tail solve_<label>_runN.log` and whether the java proc is alive
(`ps -ef | grep HeadlessSolve | grep -v grep`). If running, report the phase and stop. Schedule
the next check with ScheduleWakeup. Done when log shows `Solver complete` / `=== RESULT ===`.

### 4. On completion — verify + score + snapshot
```bash
grep -n "status\|objective\|Phase 1 result\|Tier-1 score:" solve_<label>_runN.log | head
python score_and_snapshot.py --name "<label> runN (FLOOR/T/w)" --notes "<desc>; P0/1/2/3 budgets" \
  --config-label "<label>" --data-epoch post_fix_seeded \
  --solve-log solve_<label>_runN.log --traj-csv "$SOLVE_TRAJECTORY_CSV"
```
- VERIFY: final Tier-1 score = 0 (clinically clean). If Phase 1 was relaxed/UNKNOWN, note it but
  the printed Tier-1 in the log's Tier-1 section is authoritative.
- `score_and_snapshot.py` prints `volunteer/fragile/healthy/heavy->heavy/runs>6wk` + per-weekend
  list and saves a version, printing `SAVED_VERSION_ID=NN`.
- It ALSO writes the rich **`solve_runs`** family (one durable row + metrics + per-weekend vector +
  trajectory) and prints `SOLVE_RUN_ID=NN`, tagged `data_epoch=post_fix_seeded`. This is the
  cumulative data the **`solve-stats`** skill (ICC / config A/B / budget) reads. Set
  `SOLVE_TRAJECTORY_CSV` before the solve (step 2) so the trajectory is captured; if the
  `solve_runs` tables aren't migrated yet it skips them non-fatally (legacy save unaffected).
  See SOLVE_DATA_INFRA_PLAN.md.

### 5. Render the grid (md + tier-shaded html)
```bash
python gen_grid.py --version NN --title "<label> runN - FLOOR/T/w: volX/fragY/healZ - residents A-K" --md _g.md --html _g.html
```

### 6. Update SCHEDULE_ITERATION_REPORT.md / .html / .pdf
- MD: add/refresh the summary-table column for this run; append the grid from `_g.md` before the
  `_Abbreviations:` line.
- HTML: splice the `_g.html` block before `<div class="abbrkey">` (do this in Python to avoid hand
  transcription — read report, str.replace the anchor with grid+anchor, write back).
- PDF (Chrome — the ONLY reliable converter here; needs `--headless=new` AND a `file:///` URI):
```bash
HTML_ABS="$(pwd)/SCHEDULE_ITERATION_REPORT.html"
"/c/Program Files/Google/Chrome/Application/chrome.exe" --headless=new --disable-gpu --no-sandbox \
  --no-pdf-header-footer --print-to-pdf="$(pwd)/SCHEDULE_ITERATION_REPORT.pdf" "file:///${HTML_ABS}"
sleep 4   # chrome writes async
```
- Clean up `_g.md _g.html`.

### 7. Repeat for runs 2..N (same config). Then update memory [[solver-tuning-experiment]] with the
results table and report a concise summary to the user.

## Gotchas (learned the hard way)
- `schedule_versions.schedule_year` is NOT NULL — always pass year (score_and_snapshot handles it).
- Empty `heavy_rotation_ids`/`sunday_source_rotation_ids` → objective silently OFF. Always set them.
- LIVE `assignments` uses `block_id` (year×26); versions use `block_number` 1..26. The scripts map this.
- The JavaFX app locks the DB — it must be CLOSED before headless runs (ask user to close if a solve errors on a locked db).
- Phase 3 ends FEASIBLE not OPTIMAL → run-to-run variance is expected; that's why we run N times and document each.
- Objective penalty = max(0, target - coverers), capped at target (volunteer=target, fragile=target-1, healthy=0).
