---
name: evaluate-pipeline-data
description: Run and INTERPRET the full candidate-funnel evaluation of all pipeline data (Phase 0 → 1/2 → 2→3 → Phase 3 → Finalists). Wraps evaluate_pipeline_data.py — measures seed-pool diversity & saturation, Phase-2 eligibility spread, whether the Phase-2 starting soft score predicts the Phase-3 final (framework cases 1/2/3), Phase-3 time-to-best / defensible stop time, and builds the near-best ∧ diverse finalist set (target 30–50) — then states a defensible stopping claim. Read-only. Run AFTER the pipeline (run-full-pipeline). Triggers: "evaluate the pipeline data", "evaluate full pipeline data", "run the funnel evaluation", "how does the funnel stand", "are we done generating schedules", "finalist set status".
---

# Evaluate Full Pipeline Data — candidate-funnel evaluation

The companion to `run-full-pipeline`: you run the pipeline to *produce* candidates, then run this to
*evaluate* them against the Candidate-Funnel Framework and decide whether more computation is worth
it. Run all commands from:
`c:/Users/Jarrett/Desktop/Block Schedule app/residency-scheduler/residency-scheduler`

It is the data-evaluation half of `CANDIDATE_FUNNEL_PLAN.md`. The framework guidance lives in that
plan; this skill turns it into one measured, plain-language readout.

Two rules (same spirit as `solve-stats`):
1. **Cumulative.** It reads the whole accumulated funnel (all seeds, all harvests, all Phase-3
   outputs in the DB + `tf_time_to_best.csv`), not one run.
2. **Describe, then read.** Report what each number means and the implied next lever; the user
   makes the call.

Read-only: the script opens the DB strictly `mode=ro`, so it is safe to run while a solve, harvest,
or seed-gen holds the DB.

## How to run

```
python evaluate_pipeline_data.py                       # full human report (year 2 internal)
python evaluate_pipeline_data.py --json eval_report.json   # also write machine-readable metrics
```

Useful knobs (defaults are sensible — only change if asked):
- `--eps-f 100`  finalist score-gap from the best, in **tier3 (Timefold soft) units**. Loosen to
  widen the finalist pool.
- `--eps-d 20`   finalist minimum structural distance, in **occ-grid Hamming cells** (differing
  (resident,block) rotations, out of 286). Lower to admit more (more similar) finalists. Same basis
  as the Phase-0 diversity number, so the two ε_D are directly comparable.
- `--plateau-eps 0.02` / `--plateau-window 60`  relative-improvement threshold and no-improvement
  window for the trajectory plateau rule (only used if `solve_run_trajectory` is populated).
- `--sample-seeds 120`  cap on seeds used for the O(n²) Phase-0 pairwise diversity (0 = all).
- `--year 2`     internal schedule year (the pipeline uses year 2).

### Methodology notes (why the numbers are what they are)
- **One soft-cost source of truth:** Phase-3 final cost = `schedule_versions.tier3_score` (Timefold's
  own objective). Phase-2 *starts* have no tier3 yet, so they use a fragile/vol/healthy proxy that is
  verified rank-equivalent (Spearman 1.0) to tier3 — safe for ranking, not for absolute comparison.
- **One distance basis:** all structural distance (Phase-0 seeds AND finalists) is occ-grid Hamming
  over the 286 (resident,block) cells — a clean, fixed-denominator distance.
- **P2→P3 uses Spearman + the FINAL's variance**, not Pearson on improvement. Improvement = start −
  final is mechanically correlated with start when the final is near-constant; the honest signal is
  the final's CV. Low final CV ⇒ seed-independent floor ⇒ advance by diversity.
- **Phase-3 plateau prefers `solve_run_trajectory`** (a true no-improvement window). If that table is
  empty it says so and falls back to `tf_time_to_best.csv` — it never fabricates a plateau.
- **ε_D is checked against downstream value:** the Phase-0 section correlates seed structural distance
  with the difference in downstream Phase-2 fragile. If that correlation is ~0, structural diversity
  is NOT buying downstream quality variance (the framework's "unique but not meaningfully different").

## The five sections (what each measures + the lever it points to)

- **Phase 0 — seed-pool diversity** (framework Exp E). Occ-grid Hamming over
  `phase0_seed_assignments`: pool size, mean/min/max pairwise distance, marginal-diversity gain δ,
  and the engine's own δ from `phase0_seed_stats.nn_dist_at_insert` (reported on its own basis, not
  mixed). Crucially, it correlates seed distance with the difference in **downstream** Phase-2
  fragile: if that correlation is ~0, seeds are structurally diverse but not downstream-meaningful.
- **Phase 1/2 — eligibility & spread.** Every completed Phase-2 schedule has `Q_hard=0`, so this is
  eligibility, not rank. Reports count, distinct seeds, the fragile/healthy/volunteer ranges the
  ranking is built on, and P1+P2 runtime. This is the pool Phase-3 draws from.
- **Phase 2 → Phase 3 — predictivity** (framework Exp A, the key question). Matches each
  `tf-opt-from-vN` Phase-3 output to its Phase-2 starter; reports **Spearman(start, final)** and the
  **final cost SD/CV** and distinct final-fragile values. Classifies into framework case (1) start
  ranks final → rank by starting score; case (2)/(3) final is near-constant / no rank link →
  **de-emphasize starting score, weight diversity**.
- **Convergence proof — IS THE BEST PHASE-3 OBJECTIVE THE BEST POSSIBLE?** (the headline for the
  user's actual goal). Best-so-far analysis over all hard-valid Phase-3 outputs in chronological
  order: the best tier3, how many DISTINCT seeds independently reached the best TIER (within
  `--tier-band`, default 10 — slightly different tier3 values with the same fragile/vol/healthy
  profile are the same quality), the records curve, runs-since-last-improvement, a rule-of-three 95%
  upper bound on P(a new run beats the best), and the **ceiling-type verdict**:
  *OBJECTIVE-LIMITED* (best reached by ≥2 seeds AND ≥10 runs since improvement → more seeds/harvest
  will NOT help, only changing the objective/constraints will), *POSSIBLY SEED-LIMITED* (one lucky
  seed → generate more), or *NOT YET CONVERGED*. This is what proves the stopping claim.
- **Coverage depth — within the healthy=N ceiling, how robust are those weekends?** The healthy
  COUNT is a ceiling, but two schedules at the same count differ in DEPTH: a weekend with 4 coverers
  is far more robust than one at the bare minimum of 2 (a depth-2 weekend is "fragile-adjacent" — one
  lost coverer drops it to fragile). Reads per-weekend coverer counts from `solve_run_weekend` for the
  best corner (min fragile, max healthy): depth histogram, depth-2 (fragile-adjacent) count, mean/min
  depth, and a best-so-far convergence on depth-2 (has depth plateaued, or does adding runs keep
  reducing fragile-adjacency?). The current tiered objective already rewards depth (tier3 tracks
  depth-2 count at corr 1.0), so testing whether depth is improvable just needs MORE Phase-3 runs at
  the corner — no objective change to TEST. Raising `TF_RDEPTH`/`TF_RBASE` is the lever only if depth
  is confirmed under-rewarded rather than capacity-limited.
- **Phase 3 — plateau / time-to-best / multistart variance** (framework Exp B & C). Prefers a real
  no-improvement-window plateau from `solve_run_trajectory` (KM-style "95% converged by" stop time);
  falls back to `tf_time_to_best.csv` percentiles + budget guidance if the trajectory table is empty.
  Adds **within-input variance** (Exp B): across the 10 starts of each multistart run, how much the
  final soft cost varies and how much a single random start would lose vs the 10-start winner.
- **Finalist set** (framework Exp D / §9). Greedy near-best ∧ diverse selection over hard-valid
  Phase-3 versions: candidates within ε_F of the best tier3, then filtered so each is ≥ ε_D occ-grid
  cells from every accepted finalist. Target |F| ≈ 30–50. Also reports the **tradeoff spread** —
  range and distinct count of each soft sub-objective (volunteer/healthy/heavy_heavy/max-stretch/
  saturday/runs>6wk) across the finalists, so you can see whether they are genuinely different
  options or near-duplicates in objective space.
- **Verdict.** Assembles the above into a defensible stopping statement — the framework's
  "we evaluated until additional computation had low expected marginal value" claim, instantiated
  with the actual numbers.

## Data sources (so you can sanity-check a number)
- `phase0_seed_assignments` — every stored seed's start-cell signature (Phase-0 diversity).
- `solve_runs` + `solve_run_metrics`, rows with `run_status='PHASE2_FALLBACK'` — the Phase-2 harvest
  pool (eligibility, starting soft score).
- `schedule_versions` named `tf-opt-from-v<N>-…` with `tier1_score=0` — the Phase-3 outputs;
  `tier3_score` is the final soft cost; `schedule_version_assignments` gives the grid for diversity.
- `tf_time_to_best.csv` — per-start time-to-best AND per-start `soft_cost` for the Phase-3 budget
  analysis and the multistart within-input variance.
- `solve_run_trajectory` — objective-vs-time per run; the preferred (currently usually empty) source
  for a true no-improvement plateau stop rule.
- `phase0_seed_stats.nn_dist_at_insert` — the engine's own marginal-diversity δ at seed insertion.

## The fragile trade-off experiment (the one open lever)
The convergence proof shows the fragile-0 ceiling is OBJECTIVE-limited — more seeds/harvest won't
beat it. The only way lower is to change the objective. The user's hypothesis: *allowing one fragile
weekend might free up more than one healthy weekend.* `experiment_fragile_tradeoff.py` tests this by
re-running Phase-3 at a LOWERED fragile weight (`TF_WF`, prod=1000) so the solver reveals the trade
curve, then compares (fragile, healthy) against the fragile-0 ceiling:

```
python experiment_fragile_tradeoff.py --top 3 --wf-sweep 5,15,50,200 --budget 600   # map the curve
python experiment_fragile_tradeoff.py --versions 56,90,79 --wf 15 --budget 600       # specific starters
python experiment_fragile_tradeoff.py --top 3 --wf 15 --dry                          # preview commands
```

These runs use a DIFFERENT objective, so DO NOT fold them into the production convergence proof — read
the experiment's own printed trade-off table. Favorable trade ⇒ fragile=0 is not the best operating
point; ≤1 healthy gained per fragile allowed ⇒ fragile=0 stays best.

## How to interpret / report
This skill is Opus-only (the interpretation is too nuanced for Sonnet/Haiku). Produce a written
readout: for EACH section give *what the data is → what the numbers mean → the implied lever*, quote
the actual numbers, and put **recommendations at the very end**. Lead the whole thing with the
**convergence proof** — it directly answers the user's real goal ("is this the best possible Phase-3
objective, and would more seeds/harvest help?"). Then walk Phase 0 / 1-2 / P2→P3 / Phase 3 /
finalists. If a section is empty (no `tf_time_to_best.csv`, no `solve_run_trajectory`, no Phase-3
outputs), say so and name what to run. End with prioritized recommendations — and if the ceiling is
OBJECTIVE-LIMITED, the top recommendation is the fragile trade-off experiment, not more seeds.
