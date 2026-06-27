---
name: seed-pool-stats
description: Run and INTERPRET the statistical analysis of the Phase-0 seed-pool data we have collected — combine the cap-efficiency grid (Wilson/NNT/bootstrap), the Kaplan-Meier survival analysis, and the nearest-neighbor diversity-saturation monitor into ONE plain-language interpretation of what the numbers mean. Always uses CUMULATIVE data (the full pool + accumulated run history), never just the last collection run. Describes the statistics and what they imply; does NOT prescribe actions. Best run by Sonnet (or Opus). Triggers: "run the seed math", "analyze the seed pool stats", "interpret the seed-pool data", "what do the seed numbers say", "seed pool statistics".
---

# Seed-Pool Statistics — run + interpret (cumulative)

Run ALL commands from:
`c:/Users/Jarrett/Desktop/Block Schedule app/residency-scheduler/residency-scheduler`

Goal: produce ONE combined, plain-language interpretation of the seed-pool data from three analyses —
**cap efficiency**, **survival (KM)**, and **diversity saturation**. Two hard rules from the user:
1. **Cumulative only.** Base everything on accumulated data (the whole pool DB + the full run history),
   NOT the most recent collection run alone.
2. **Describe, don't prescribe.** Explain what the numbers mean and what they imply; do NOT recommend
   "keep collecting" / "change the cap" / etc. State the read; leave the decision to the user.

Read-only analysis. Never write to or solve against the live `residency_scheduler.db`. The three
scripts and the data they consume are described below; understand each before interpreting its output.

## The three analyses (what each measures + what it reads)
- **`analyze_cap_survival.py`** — Kaplan–Meier survival on time-to-feasibility. The RIGOROUS cap tool.
  Gives p(C)=1−S(C), cost(C)=E[min(T,C)]/p(C), the hazard trend ("is a still-running search doomed or
  just unlucky"), and auto-refuses caps past the data horizon. Reads a CSV of `run,phase0_status,phase0_secs`.
- **`analyze_collection_cap.py`** — the older grid tool (Wilson CI on the feasible rate, NNT=1/p,
  bootstrap cost CI, Wald power check). KM subsumes it; run it as a CROSS-CHECK and to get the
  explicit power/sample-size ("are we powered?") number, which KM doesn't print. Reads the same CSV.
- **`analyze_seed_saturation.py`** — nearest-neighbor Hamming-distance trend (`nn_dist_at_insert`)
  over insertion order. Tells you whether new seeds are landing CLOSER to existing ones as the pool
  grows (diversity saturation) vs. staying far apart. Reads the POOL DB directly (already cumulative).

## Data note (important — cumulative source of truth)
- The cumulative record lives in the DB table **`phase0_collection_runs`** (in the pool DB) — one
  durable row per collection solve (status, secs, cap_secs, worker_count), written by the engine and
  NEVER overwritten. This is the authoritative time-to-feasibility history the cap scripts need.
- `phase0_seed_results.csv` is OVERWRITTEN every collection run (last run only) — do NOT use it as the
  cap input. Export the cumulative history from `phase0_collection_runs` to a CSV the scripts read (Step 1).
- The pool DB also holds the seeds + `nn_dist_at_insert`; the saturation script reads that directly.

## Procedure

### 1. Export the cumulative timing history from the DB
Pull ALL collection runs from `phase0_collection_runs` (the durable, never-overwritten table) into a
CSV the cap scripts read. This is cumulative by construction — every run ever recorded.
```bash
cd "c:/Users/Jarrett/Desktop/Block Schedule app/residency-scheduler/residency-scheduler"
python - <<'PY'
import sqlite3, csv
c=sqlite3.connect("residency_scheduler.pool.db")
try:
    rows=c.execute("SELECT status, secs FROM phase0_collection_runs WHERE year=2 ORDER BY id").fetchall()
except sqlite3.OperationalError:
    rows=[]
with open("phase0_collection_history.csv","w",newline="") as f:
    w=csv.writer(f); w.writerow(["run","phase0_status","phase0_secs"])
    for i,(s,sec) in enumerate(rows,1): w.writerow([i,s,sec])
print(f"cumulative history: {len(rows)} runs exported to phase0_collection_history.csv")
PY
```
Note in your report how many total runs the history holds. If it's small (< ~30), say so — the cap
stats will be wide/underpowered; flag it, don't gloss it. If the table is missing or empty (0 runs),
STOP for the cap analysis and tell the user there's no cumulative timing data yet — but STILL run the
saturation analysis (Step 4), which reads the pool directly and doesn't need this CSV.

### 2. Determine the collection cap used (the censoring time)
The cap scripts need the cap the data was collected at (the right-censoring time). Infer it from the
capped runs' stop time:
```bash
python - <<'PY'
import csv
times=[float(r["phase0_secs"]) for r in csv.DictReader(open("phase0_collection_history.csv"))
       if r.get("phase0_secs") and r["phase0_status"] not in ("OPTIMAL","FEASIBLE")]
print("inferred censoring cap (s):", round(max(times)) if times else "no capped runs — use the cap the user states")
PY
```
Use that number as `<CAP>` below (round to the nearest of 150/180/300 that matches; if no capped runs
exist, ask the user what cap the collection used, or pass the largest observed feasible time).

### 3. Run the cap analyses on the CUMULATIVE history (KM primary, grid cross-check)
```bash
echo "================ KAPLAN–MEIER (primary cap analysis) ================"
python analyze_cap_survival.py phase0_collection_history.csv <CAP> 60 90 120 150 180
echo
echo "================ GRID (cross-check + power/sample-size) ============="
python analyze_collection_cap.py phase0_collection_history.csv <CAP> 60 90 120 150 180
```
The two should AGREE on p(C) at caps within the horizon (KM just handles censoring correctly and gives
the continuous cost curve). Caps above the horizon will be flagged UNIDENTIFIABLE by both — that's
correct, report it as "the data can't see past Xs," not as a finding.

### 4. Run the diversity-saturation monitor on a COPY of the pool DB
```bash
cp residency_scheduler.pool.db _stats_pool_copy.db
python analyze_seed_saturation.py _stats_pool_copy.db 2
rm -f _stats_pool_copy.db
```
If it reports many seeds skipped as NULL (banked before the monitor existed), say so — the trend is
only over post-monitor seeds. With few non-NULL points the trend is not yet meaningful; flag that.

### 5. Synthesize ONE combined interpretation (the deliverable)
Write a single plain-language report covering, in order:
- **Sample size / power:** how many cumulative runs; what the grid's power check says ("powered" vs
  "need ~N more"). Set the confidence frame for everything that follows.
- **Cap efficiency (from KM):** the feasible rate, the time-to-feasible distribution (median, max /
  horizon), the continuous cost-vs-cap curve's minimum and its CI, and the hazard read (are late-
  running searches getting hopeless, or still finishing?). State which caps are distinguishable and
  which overlap. Note any UNIDENTIFIABLE caps plainly.
- **Diversity / saturation:** the nearest-neighbor distance trend — flat/high = the pool is still
  gaining genuinely different seeds; downward = new seeds are crowding near existing ones. Give the
  numbers and the direction; note the duplicate rate if visible (~0 expected).
- **What the numbers mean together** — a plain reading of pool health and collection efficiency.

Then STOP. Do NOT recommend an action (no "keep collecting" / "lower the cap" / "pool is big enough").
Present the interpretation; the user decides. If the user explicitly asks "what should I do," you may
then advise — but the default report is description only.

## Interpretation cautions (so the read is honest)
- Small n → wide CIs. If cumulative history is thin, every cap number is uncertain — say so up front.
- KM "makes the cap rigorous, not a plot twist": if max feasible time is ~150s it will confirm a
  ~90–150s cap and show 300s wasteful — don't dress that up as a surprise.
- The cap analysis optimizes THROUGHPUT (seconds per feasible seed), NOT seed QUALITY. A lower cap that
  discards slow-to-find seeds could be dropping seeds that lead to better final schedules — that
  quality question (the ICC test) is separate and not yet answered. Note this caveat; don't let a
  throughput-optimal cap read as a quality verdict.
- Saturation distance has NO "too close" threshold set yet (deliberately deferred until the quality/ICC
  result). Report the trend; do not declare seeds redundant.

## Guardrails
- Read-only. Work on COPIES (the pool-DB copy in Step 4); never solve against or edit the live DB.
- Cumulative always — analyze `phase0_collection_history.csv` (Step 1) + the pool DB, never the bare last-run CSV.
- Describe, don't prescribe (unless the user explicitly asks for a recommendation).
- Background: SEED_POOL_STATS_IMPLEMENTATION_PLAN.md (what each script implements + why), [[run-timing-reporting]].
