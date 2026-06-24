# OPERATIONS — running the solver & sweeps (operator master)

_The single source of truth for **how to run the block-schedule solver**: the autonomous sweep, a
single config by hand, budgets, conventions, and troubleshooting. For **what a correct schedule
must satisfy** see [RULES.md](RULES.md); for **status, findings, and open work** see
[PROJECT.md](PROJECT.md)._

_Consolidated 2026-06-23 from SWEEP_RUNBOOK, HAIKU_SOLVER_RUNBOOK, WORKFLOW_AND_SKILLS_GUIDE, and
PARALLEL_RUNS_GUIDE._

> **Working directory for everything:** `residency-scheduler/residency-scheduler/`.

---

## Table of contents

1. [The mental model (read once)](#1-the-mental-model-read-once)
2. [The autonomous sweep — one command](#2-the-autonomous-sweep--one-command)
3. [Authoring the queue (`queue.jsonl`)](#3-authoring-the-queue-queuejsonl)
4. [Validate, then run for real](#4-validate-then-run-for-real)
5. [Checking progress](#5-checking-progress)
6. [What HALTs the sweep vs. what's skipped](#6-what-halts-the-sweep-vs-whats-skipped)
7. [Running ONE config by hand](#7-running-one-config-by-hand)
8. [Standard budget & worker count](#8-standard-budget--worker-count)
9. [Conventions (timing, naming, reporting)](#9-conventions-timing-naming-reporting)
10. [Hard rules (never break)](#10-hard-rules-never-break)
11. [Troubleshooting / gotchas](#11-troubleshooting--gotchas)
12. [Building blocks (files & skills)](#12-building-blocks-files--skills)
13. [Deprecated: parallel low-worker runs](#13-deprecated-parallel-low-worker-runs)

---

## 1. The mental model (read once)

You tune the solver by running **batches** of configs and comparing results. The loop is:

1. **DESIGN** a batch — decide which configs to test next (analytical; runs nothing).
2. **RUN** the batch — the autonomous sweep grinds through them unattended for hours/days.
3. **READ** the results — compare runs, pick winners, decide the next batch → back to step 1.

A **config** = floor on/off + Sunday-coverage target + weight + number of repeats + phase time
budgets. A **batch** = a queue of configs. The autonomous **sweep** runs a whole batch by itself.
There is also a way to run **ONE** config by hand (§7) when you don't want a whole batch.

| Step | You say | Tool | Runs anything? |
|---|---|---|---|
| 1. Plan | "design the next batch" | `design-solver-batch` skill | No |
| 2. Launch | "launch the sweep, confirm running, give ETA" | `launch_sweep.ps1` (§2) | Yes (hours/days) |
| 3. Wait (walk away) | "check the sweep" anytime | re-run `launch_sweep.ps1` / read status (§5) | No |
| 4. Review | "compare the latest batch" | report / `sweep_results.csv` | No |
| 5. One-off (optional) | "run config X, N times" | `run-solver-config` skill (§7) | Yes (one config) |

---

## 2. The autonomous sweep — one command

**To start (or resume) the sweep, run exactly this one line. Nothing else — no `cd`, no Bash, no
Python-vs-PowerShell decision, no editing how it launches.**

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Users\Jarrett\Desktop\Block Schedule app\residency-scheduler\residency-scheduler\launch_sweep.ps1"
```

`launch_sweep.ps1` does everything an operator used to do by hand, deterministically:
- moves to the project dir by absolute path (never guesses cwd);
- if a sweep is already running, prints **ALREADY RUNNING** and exits 0 (safe to re-run anytime);
- **refuses to launch if the JavaFX app is open** (it locks the DB → the solve dies at Phase 0).
  Prints "CLOSE the JavaFX app, then re-run" and exits 1;
- launches `sweep_driver.py --detach`, which re-spawns itself as a job-breakaway detached process
  that survives your shell/session closing (no Task Scheduler needed);
- the detached driver writes its own durable `sweep_driver.out.log` / `sweep_driver.err.log` so a
  death is diagnosable;
- runs the health checks and prints a verdict.

**Then do ONLY these small checks — do not improvise beyond them:**
1. Read the `SWEEP HEALTH CHECK` block the script printed. The two that matter: **`driver PID
   alive`** and **`java solve PID alive (doing work)`** must both be **OK**.
2. Verdict **RUNNING** (green) → you are done. Walk away.
3. Verdict **PROVISIONAL** (yellow: driver up, no java yet) → wait ~30s and run the SAME one line
   again; it will report ALREADY RUNNING and show the java PID.
4. Verdict **NOT RUNNING** (red) → the script already printed the log tail with the HALT reason
   (almost always: wrong git branch, missing `cp.txt`, or DB integrity). Fix that one thing, then
   run the same one line again. Do **not** hand-launch the driver another way.

> **Why `java solve PID alive` is a required check:** on 2026-06-23 the status file said RUNNING
> while the java solve had silently died at Phase 0 — a false green. A live child java PID (RAM
> climbing during Phase-0 seeding) is the real proof the sweep is doing work. Phase 0 prints nothing
> for 2–8 min while seeding, so a log stuck at `[0s]` **with** a live java PID is healthy; stuck at
> `[0s]` with **no** java PID is dead.

**Prerequisites the driver checks at preflight (it HALTs if any fail):**
- On branch **`feature/solver-trajectory-capture`** (trajectory capture lives only there).
- Compiled `target/classes/.../CpSatSchedulerEngine$TrajectoryCallback.class` exists
  (`mvn -q compile` on that branch if missing).
- `cp.txt` exists (`mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt`).
- Master DB `PRAGMA integrity_check` = ok.
- **JavaFX app CLOSED.** Two writers can corrupt the master DB. The single-writer `sweep.lock`
  blocks a second driver but cannot stop the JavaFX app.

---

## 3. Authoring the queue (`queue.jsonl`)

One JSON object per line. Blank lines and `#` lines are ignored (comment out / reorder freely).
Fields: `label`, `floor` (bool), `target` (int), `weight` (int), `runs` (repeats, for variance),
optional `budget` `[P0,P1,P2,P3]` seconds (default `[900,300,300,2400]`).

Labels use **`cfgR<round>-<NN>`** (sorts by batch then sequence; old single-letter `cfgA`–`cfgF` are
historical). The `design-solver-batch` skill produces this batch — its output lines map directly to
queue entries; just append them.

```
{"label":"cfgR3-01","floor":false,"target":2,"weight":75,"runs":3}
{"label":"cfgR3-02","floor":false,"target":2,"weight":120,"runs":3}
{"label":"cfgR3-05","floor":false,"target":2,"weight":120,"runs":1,"budget":[900,300,300,3600]}
```

Each line expands to `runs` units (`cfgR3-01-r1`, `-r2`, …), each separately snapshotted and
documented (variance is the point). Editing a config's floor/target/weight/budget makes it a **new**
unit (param hash changes) so it re-runs; already-DONE units with unchanged params are skipped on
restart. Re-run prevention is enforced by `sweep_state.json` regardless of queue contents.

**Auto-archiving (queue stays clean).** When every unit of a config line is resolved (DONE or
FAILED), the driver MOVES that line out of `queue.jsonl` into `queue.archive.jsonl` (with a results
comment) and rewrites `queue.jsonl`. So `queue.jsonl` always shows only what's still pending/running.
To add a new batch, just append its lines; the prior batch will already have archived itself.

---

## 4. Validate, then run for real

```bash
python sweep_driver.py --dry-run   # parse queue, flag malformed lines, print planned actions; no java, no DB writes
python sweep_driver.py --once      # run a single pending unit end-to-end, then exit (the gate before trusting long runs)
```

After `--once`, confirm: solve ran at 10 workers, `sweep_runs/traj_*.csv` has rows, a grid appeared
in `sweep_grids/`, a **summary row** (not an inline grid) was added to
`SCHEDULE_ITERATION_REPORT.md/.html` and the PDF re-rendered, `sweep_results.csv` / `SWEEP_STATUS.md`
look right, the unit is `DONE` in `sweep_state.json`, and master integrity is still ok.

Then run it for real via **`launch_sweep.ps1`** (§2 — the canonical launch). The bare
`python sweep_driver.py` foreground form works too, but always prefer the launcher for unattended
runs (it handles detachment, durable logging, the no-double-launch guard, and health checks). On a
clean stop (0), HALT (2), or refused start (3) `run_sweep.cmd` does not relaunch; only an unexpected
crash triggers a relaunch.

---

## 5. Checking progress (no notifications by design)

- **`SWEEP_STATUS.md`** — state (RUNNING/HALTED/DONE), current run + Central-time ETA (tightened off
  the real Phase-3 start), progress, per-unit table, and any HALT reason.
- **`sweep_results.csv`** — one row per finished unit (scores, plateau, version, status).
- **`SCHEDULE_ITERATION_REPORT.{md,html,pdf}`** — appended summary table; full grids linked from
  `sweep_grids/`.
- Per-run logs + trajectory CSVs in `sweep_runs/`. Rotating DB backups in `backups/`.

Re-running `launch_sweep.ps1` while a sweep is live is safe — it says ALREADY RUNNING and shows the
live java PID.

---

## 6. What HALTs the sweep vs. what's skipped

**Skip-and-continue (logged, sweep keeps going):** a single run ending Phase-3 UNKNOWN / Phase-2
fallback / Tier-1 ≠ 0 / no trajectory rows → recorded FAILED (snapshotted as `… PHASE2-FALLBACK`,
not presented as the config's result), advance to the next unit.

**HALT (sweep stops, reason in `SWEEP_STATUS.md`):**
- Master DB `integrity_check` ≠ ok (corruption) — STOP; restore from `backups/`.
- DB stays locked > ~60s — the JavaFX app is open; close it, the sweep resumes.
- `MAX_CONSECUTIVE_FAILURES` in a row (default 4) — queue/env likely broken.
- `MAX_TOTAL_WALL_CLOCK` exceeded (default 4 days) — runaway guard.
- A prerequisite (branch / artifact / cp.txt / integrity) fails at preflight.

**Stopping it:** Ctrl-C the driver. The lockfile is released on exit; a stale lock from a killed
process is auto-reclaimed on next start. Restarting resumes the queue from `sweep_state.json`.

**Tunables (top of `sweep_driver.py`):** `MAX_CONSECUTIVE_FAILURES`, `MAX_TOTAL_WALL_CLOCK_S`,
`BACKUP_EVERY_N_RUNS`, `KEEP_BACKUPS`, `DEFAULT_BUDGET`, `LOCK_RETRY_WINDOW_S`, wait cadence,
`COOLDOWN_BETWEEN_RUNS_S` (idles 300s between runs so CPU/fans recover during long sweeps).

---

## 7. Running ONE config by hand

Use the **`run-solver-config`** skill (the autonomous sweep is the same loop, automated). The skill
does the full per-run procedure: write config → launch headless → wait → verify Tier-1 = 0 → score +
snapshot → render grid → update `SCHEDULE_ITERATION_REPORT`. The manual equivalent of each step:

**STEP 1 — write the config** (replace FLOOR `true`/`false`, TARGET, WEIGHT; the tier lists are
mandatory or the coverage objective silently turns OFF):
```bash
python - <<'EOF'
import sqlite3
db=sqlite3.connect('residency_scheduler.db'); c=db.cursor()
def setk(k,v): c.execute("INSERT INTO schedule_config(config_key,config_value) VALUES(?,?) ON CONFLICT(config_key) DO UPDATE SET config_value=excluded.config_value",(k,str(v)))
setk('enforce_zero_volunteer_weekends','FLOOR')
setk('sunday_coverage_target','TARGET')
setk('weight_sunday_coverage','WEIGHT')
setk('heavy_rotation_ids','1,3,6,11,14,16')
setk('sunday_source_rotation_ids','19,15,2,4,8,9,13,17,18,21')
db.commit()
print('config written; integrity:', c.execute('PRAGMA integrity_check').fetchone()[0][:20])
EOF
```

**STEP 2 — launch headless** (year is always 2; budgets are P0 P1 P2 P3 seconds — use the standard
budget from §8; set `SOLVE_TRAJECTORY_CSV` to capture the Phase-3 curve; classpath needs
`target/classes` as a Windows path or Java can't find the main class):
```bash
[ -f cp.txt ] || mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
[ -d target/classes ] || mvn -q compile
WINCLASSES="$(cygpath -w "$(pwd)/target/classes")"
CP="$WINCLASSES;$(cat cp.txt)"
SOLVE_TRAJECTORY_CSV="$(pwd)/traj_cfgX_run1.csv" \
  nohup java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 900 300 300 2400 > solve_cfgX_run1.log 2>&1 &
echo "launched PID $!"
```
Immediately report START + presumed-max-end + check-time in Central time (§9), then **wait with ONE
ScheduleWakeup** (delay = sum of this run's phase budgets + 600s) — never tight-poll (§11).

**STEP 3 — score + snapshot** (after `=== RESULT ===` appears and the java proc is gone):
```bash
grep -n "status\|objective\|Phase 1 result\|Phase 3 result\|Tier-1 score:" solve_cfgX_run1.log | head
python score_and_snapshot.py --name "cfgX run1 (OFF/2/120)" --notes "floor OFF target 2 weight 120; std budget"
```
Read the printed `volunteer=.. fragile=.. healthy=.. heavy->heavy=.. runs>6wk=..` and the
`SAVED_VERSION_ID=NN`. Confirm Tier-1 = 0. **Validity check** on the `Phase 3 result:` line —
`FEASIBLE`/`OPTIMAL` = real result; `UNKNOWN` = Phase-2 fallback (no coverage optimization, `traj`
CSV has 0 rows): snapshot it but NAME it a fallback and do not present its scores as the config's
result.

**STEP 4 — render the grid:**
```bash
python gen_grid.py --version NN --title "cfgX run1 - OFF/2/120: volX/fragY/healZ - residents A-K" --md _g.md --html _g.html
```

**STEP 5 — update the report** (`SCHEDULE_ITERATION_REPORT.md/.html/.pdf`): append the grid before
the `_Abbreviations:` anchor (md) / `<div class="abbrkey">` anchor (html), then re-render the PDF via
Chrome (`--headless=new` + a `file:///` URI is the only working converter):
```bash
HTML_ABS="$(pwd)/SCHEDULE_ITERATION_REPORT.html"
"/c/Program Files/Google/Chrome/Application/chrome.exe" --headless=new --disable-gpu --no-sandbox \
  --no-pdf-header-footer --print-to-pdf="$(pwd)/SCHEDULE_ITERATION_REPORT.pdf" "file:///${HTML_ABS}"
sleep 4 && ls -la SCHEDULE_ITERATION_REPORT.pdf && rm -f _g.md _g.html
```

For a multi-run request ("run cfgX 5 times"), run the SAME config N times back-to-back (one at a
time), log `solve_<label>_runI.log` / `traj_<label>_runI.csv` / version `<label>-rI`, snapshot
**every** run (variance is the point), and end with a summary table of all N draws (vol/fragile/
healthy + plateau time each).

---

## 8. Standard budget & worker count

**Standard 10-worker budget: `2 900 300 300 2400`** (year 2; P0 = 900s/15min, P1 = 300s, P2 = 300s,
P3 = 2400s/40min). Worst case ~50min/run; usually ~45min (phases 0–2 exit early on OPTIMAL; only
Phase 3 uses its full budget).

- Keep **`cpsat_num_workers = 10`** — the reliable baseline. Low worker counts (1–3) **FAIL to even
  seed Phase 0**; that is why parallel low-worker runs are deprecated (§13). Run configs
  **SEQUENTIALLY** at 10 workers.
- Phase behavior @10w: Phases 1/2 finish in 2–5s once seeded; Phase 3 plateaued ~16min in the one
  fully-measured run (40min P3 is a safe cushion). The trajectory CSV shows where each run actually
  plateaued — note it so budgets can be tightened later.
- **Phase 0** was accelerated 2026-06-23 (see [PROJECT.md → Phase-0 acceleration](PROJECT.md#phase-0-acceleration-implemented-not-yet-tested));
  expected median ~60–90s once confirmed. Until test runs confirm the new baseline, keep P0 at 900s;
  consider dropping to 300s only after 3–5 measured runs.
- **Branch count is a USELESS quality proxy** — use the objective trajectory, not branches.

---

## 9. Conventions (timing, naming, reporting)

- **Report run START + presumed-max-end + check-time in Central time** (CDT summer = UTC-5, CST
  winter = UTC-6) immediately after every launch; presumed-max-end = start + sum of all phase
  budgets (100% of each phase). Schedule the completion check for 10 min after the presumed end. For
  LONG/PARALLEL runs do the two-stage tightening: a calibration check at `start + (P0+P1+P2) + 120s`,
  then read the actual Phase-3 start from the log (the `Phase 3 ▶ Pattern` line is prefixed with
  `[Ns]`; phase3_start = launch + N) and set accurate end = phase3_start + P3 budget.
- **Naming:** logs `solve_<label>_runI.log`; trajectories `traj_<label>_runI.csv`; versions
  `<label>-rI`. Config labels `cfgR<round>-<NN>` (§3).
- **Metrics — the only ones, do not invent others:** volunteer, fragile, healthy, heavy→heavy,
  runs>6wk (all from `score_and_snapshot.py`). Lower volunteer/fragile = better; higher healthy =
  better.
- **Operator model selection:** Sonnet preferred over Haiku (Haiku misread Phase-0 silence as a
  stuck DB and tight-polled). Always review the first full sequential session's output before
  trusting long unattended runs.

---

## 10. Hard rules (never break)

1. **Run solves ONE AT A TIME.** Never two solves against the master at once (they share one DB /
   LIVE table). Launch the next only after the current finishes and is fully documented.
2. **Never edit Java source for a run.** No schema changes. Only `mvn compile` / `build-classpath`
   if `target/classes` or `cp.txt` is missing. No git commits unless told.
3. **Always set the tier lists** in every config write: heavy = `1,3,6,11,14,16`, source =
   `19,15,2,4,8,9,13,17,18,21` (otherwise the coverage objective silently turns OFF).
4. **The JavaFX app must be CLOSED** for the whole run/sweep (it locks the master DB → false-green
   dead solves and corruption risk).
5. **If a solve errors with a locked database**, the app is open → close it.
6. **If `PRAGMA integrity_check` is anything but `ok`, STOP** and tell the user (DB corruption).
7. **If final Tier-1 score is not 0**, document it but flag it prominently.
8. **Document every run** — scores AND the grid — even repeats. Never skip.
9. **If you ever think a run is stuck, STOP and ask** — do not kill/restart the solver or touch the
   DB. The only real failure signs are: the java process is GONE before `=== RESULT ===`, OR
   `PRAGMA integrity_check` ≠ ok.

---

## 11. Troubleshooting / gotchas

- **A solve log frozen at `[0s]` is NORMAL if the java PID is alive and its RAM is climbing** —
  Phase 0 seeds silently for 2–8 min. The three startup lines (`Loading data` / `Running feasibility
  analysis` / `Phase 0 ▶ Finding feasible assignment`) then NOTHING is expected. The `Loading data`
  line does **not** mean it's stuck loading — the next two lines prove the DB loaded. Frozen at
  `[0s]` with **no** java PID = dead.
- **Never tight-poll.** Do NOT check every 30s, run `tail`/`ps` in a loop, or use `sleep`. You
  launched a ~45–65min run — sleep with ONE ScheduleWakeup (delay = sum of phase budgets + 600s),
  then check ONCE. This is the #1 thing to get right (wastes usage otherwise).
- **Always launch the sweep via `launch_sweep.ps1`**, never by hand-typing `python sweep_driver.py`
  for unattended runs. Hand launches die when the shell closes; the script's detached console
  prevents that. (Cause of repeated silent deaths on 2026-06-23 — alongside a since-fixed
  `pid_alive()` bug where `os.kill` raised `SystemError` on Windows and crashed the wait-loop.)
- **The desktop must stay on.** You reboot it manually, so the sweep isn't reboot-proofed by design
  (no Task Scheduler). If you reboot mid-sweep, just run `launch_sweep.ps1` again — it resumes from
  `sweep_state.json`.
- **Classpath gotcha:** `target/classes` must be a Windows path (`cygpath -w`) or Java can't find
  the main class when launched from a subdir. (Already handled in the scripts.)

---

## 12. Building blocks (files & skills)

| Item | What it is |
|---|---|
| `launch_sweep.ps1` | THE one-command sweep launcher (detached, durable log, no-double-launch, health checks). |
| `sweep_driver.py` | The autonomous driver: walks `queue.jsonl`, runs each unit end-to-end, survives crashes/reboots. |
| `queue.jsonl` / `queue.archive.jsonl` | The pending batch / the audit trail of completed lines. |
| `HeadlessSolveRunner` (Java) | Headless solve, commits to LIVE, honors `SOLVE_TRAJECTORY_CSV`. |
| `score_and_snapshot.py` | Score LIVE year-2 + save a `schedule_versions` row. |
| `gen_grid.py` | DB version → MD + tier-shaded HTML grid. |
| `CoverageFloorRunner` (Java) | Proves the volunteer-weekend floor (see [RULES.md §11](RULES.md#11-the-coverage-floor-is-zero--proof)). |
| `design-solver-batch` skill | Analytical config-designer: study prior runs → write the next `queue.jsonl`. Runs nothing. |
| `run-solver-config` skill | Run ONE config end-to-end and document it (§7). |
| `SCHEDULE_ITERATION_REPORT.{md,html,pdf}` | The comparison report with shaded grids. |

---

## 13. Deprecated: parallel low-worker runs

> **Do not use this for new work.** Kept for reference only. Shakedown (2026-06-22) showed 1–3
> workers **fail to seed Phase 0** and 3 workers couldn't produce a Phase-3 result in 45min, so the
> low-worker parallel throughput idea does not pay off for this FEASIBLE-bounded search. **Use
> sequential 10-worker runs (§8) instead.** The DB-isolation design below is sound and was proven
> safe; it just isn't worth it given the seeding failure.

The tool was `parallel_run.sh` (setup / launch / status / merge / clean). Isolation = one working
directory per run, each with its **own copy of the DB** (`runs/<label>/residency_scheduler.db`), so
concurrent solves never write the same file. The master DB is only READ during the parallel phase and
only WRITTEN during a **serial** merge-back (one slot at a time). Integrity-checked before every copy
and every merge; proven to keep the master `integrity_check=ok` throughout a 2-run concurrent test.
If ever revived, the hard rules were: never two solves in the same directory; merge serially; don't
run a root-level solve while slots are live; JavaFX app closed throughout; quarantine any slot whose
integrity check fails.
