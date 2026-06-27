---
name: solve-stats
description: Run and INTERPRET the statistical analysis of the REAL-SOLVE data we have collected (the solve_runs family) — combine the ICC analysis (does the starting seed predict final quality?), the config A/B bootstrap difference-CI comparison (is config B reliably better than A across solver noise?), and the empirical phase-budget calibration (size [P0,P1,P2,P3] from the trajectory plateau) into ONE plain-language interpretation. Always uses CUMULATIVE solve_runs data, never a single run. Describes what the numbers mean; does NOT prescribe actions. Best run by Sonnet (or Opus). Triggers: "run the solve math", "analyze the solve stats", "interpret the solve_runs data", "is config B better", "what's the ICC", "calibrate the budget".
---

# Real-Solve Statistics — run + interpret (cumulative)

The real-solve analogue of `seed-pool-stats`, one layer up. Run ALL commands from:
`c:/Users/Jarrett/Desktop/Block Schedule app/residency-scheduler/residency-scheduler`

Goal: ONE combined, plain-language interpretation of the real-solve data from three analyses —
**ICC** (seed→quality), **config A/B** (difference CIs), and **budget calibration** (plateau).
Two hard rules (same as the seed stats):
1. **Cumulative only.** Base everything on the accumulated `solve_runs` family, NOT a single solve.
2. **Describe, don't prescribe.** Explain what the numbers mean and what they imply; do NOT
   recommend "adopt config B" / "raise P3" / "exploit seeds." State the read; the user decides.

Read-only analysis. The three scripts open the DB strictly `mode=ro`; they can NEVER write the live
`residency_scheduler.db`, so this is safe to run even while a solve or seed-gen holds the DB.

## The three analyses (what each measures + what it reads)
- **`analyze_solve_quality.py`** — one-way ANOVA **intraclass correlation (ICC)**: does the starting
  `seed_id` predict the final Tier scores? Reads repeated `solve_runs` grouped by `seed_id`. This is
  the deferred empirical question that GATES seed exploit/prune. Low ICC ⇒ solver noise dominates ⇒
  exploiting/pruning seeds by past reward is not justified (keep round-robin). Needs ≥5 runs each on
  ≥10 seeds; it prints POWER (OK / UNDERPOWERED) so you never over-read thin data.
- **`analyze_config_compare.py CFG_A CFG_B`** — config-vs-config **bootstrap CI on the difference**
  (B−A) in each metric (Tier scores + volunteer/fragile/healthy/heavy→heavy/runs>6wk/Saturday),
  resampling whole runs. DECISION RULE (mirrors the cap analysis): a difference counts only if its
  CI is separated from 0; a CI straddling 0 is within solver noise. This is the engine of "don't
  make blind runs."
- **`analyze_budget_calibration.py`** — empirical **[P0,P1,P2,P3]** from `solve_runs` phase timings
  + the `solve_run_trajectory` Phase-3 plateau (last incumbent improvement). Sizes P3 from the
  plateau, not the branches.

## Data note (cumulative source of truth)
- The cumulative record lives in the **`solve_runs`** family (`solve_runs` + `solve_run_metrics` +
  `solve_run_weekend` + `solve_run_trajectory`) — one durable, never-overwritten row per real solve,
  tagged with `data_epoch` (default `post_fix_seeded`). Filter by epoch so pre-I1 (historical) and
  post-fix runs are never mixed; the default epoch is `post_fix_seeded`.
- The Java `SolveStats` class is the single copy of the ICC + bootstrap-diff math; these Python
  scripts mirror it exactly (and `SolveStatsTest` pins it), so the report and the unit test agree.

## Procedure

### 1. Confirm there is cumulative post-fix data
```bash
cd "c:/Users/Jarrett/Desktop/Block Schedule app/residency-scheduler/residency-scheduler"
python - <<'PY'
import sqlite3
c=sqlite3.connect("file:residency_scheduler.db?mode=ro", uri=True)
try:
    n=c.execute("SELECT COUNT(*) FROM solve_runs WHERE data_epoch='post_fix_seeded'").fetchone()[0]
    bys=c.execute("SELECT seed_id, COUNT(*) FROM solve_runs WHERE seed_id IS NOT NULL "
                  "AND data_epoch='post_fix_seeded' GROUP BY seed_id").fetchall()
    print(f"post_fix_seeded solve_runs: {n}; distinct seeds with runs: {len(bys)}")
except sqlite3.OperationalError as e:
    print("solve_runs not present yet:", e)
PY
```
If `solve_runs` is missing or near-empty, STOP and tell the user there's no post-fix real-solve data
yet — the data layer must be migrated and seed-fed solves run first (see SOLVE_DATA_INFRA_PLAN.md).

### 2. ICC — does the seed predict quality?
```bash
python analyze_solve_quality.py residency_scheduler.db post_fix_seeded
```
Report the per-tier ICC and the POWER flag. UNDERPOWERED ⇒ treat ICC as provisional.

### 3. Config A/B — for each pair the user names (or the round's top contenders)
```bash
python analyze_config_compare.py <CFG_A> <CFG_B> residency_scheduler.db post_fix_seeded
```
Report which metrics' difference CIs are separated from 0 and which straddle it (noise).

### 4. Budget calibration
```bash
python analyze_budget_calibration.py residency_scheduler.db post_fix_seeded
```
Report the observed per-phase spread and the recommended [P0,P1,P2,P3] with the plateau evidence.

### 5. Synthesize ONE combined interpretation (the deliverable)
A single plain-language report, in order:
- **Sample size / power:** how many post-fix runs, how many seeds × runs/seed; the ICC POWER flag.
  Set the confidence frame for everything else.
- **Seed → quality (ICC):** per-tier ICC and what it implies about whether seed identity carries
  signal (and therefore whether exploit/prune is even answerable yet). Note power.
- **Config A/B:** for each pair, which metric differences beat the noise floor (CI excludes 0) and by
  how much; which are noise. Be explicit that "lower is better" for every metric.
- **Budget:** the recommended [P0,P1,P2,P3] and the plateau spread; whether P3 looks over- or
  under-budgeted vs. the data.
- **What the numbers mean together** — a plain reading of solve quality + reproducibility + budget fit.

Then STOP. Do NOT recommend an action (no "adopt B" / "raise P3" / "start exploiting seeds"). Present
the read; the user decides. If they explicitly ask "what should I do," you may then advise.

## Interpretation cautions (so the read is honest)
- Small n → wide CIs. Thin post-fix data ⇒ every difference and the ICC are uncertain — say so up front.
- A difference CI that straddles 0 is NOT "no difference" — it's "not distinguishable from noise at
  this sample size." Say that, not "the configs are equal."
- ICC ≈ 0 means the seed doesn't predict THIS metric at THIS sample size; it doesn't prove seeds are
  interchangeable forever. Note power.
- The budget P3 plateau is only as good as the trajectories stored; with <3 trajectories it's
  provisional (the script flags this).
- Cross-epoch is forbidden: never compare a `post_fix_seeded` run to a pre-I1 (historical) one — the
  I1 fix moved the feasible region (see SOLVE_DATA_INFRA_PLAN.md, [[i1-gi-protection-fix]]).

## Guardrails
- Read-only (`mode=ro`). Never solve against or edit the live DB.
- Cumulative always — the whole `solve_runs` family for the chosen epoch, never a single run.
- Describe, don't prescribe (unless the user explicitly asks for a recommendation).
- Background: SOLVE_DATA_INFRA_PLAN.md (what each script implements + why), [[run-timing-reporting]],
  [[plateau-convergence-finding]], [[i1-gi-protection-fix]].
