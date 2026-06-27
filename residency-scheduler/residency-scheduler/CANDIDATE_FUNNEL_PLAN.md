# Candidate-Funnel Framework — Pipeline Implementation Plan

**Created:** 2026-06-26. Derived from the Candidate-Funnel Framework guidance document and mapped
onto the current pipeline. See `SCHEDULE_SEARCH_PLAN.md` for the staged quality-search plan;
`TIMEFOLD_OPTIMIZATION_HANDOFF.md` for Phase-3 config; `PIPELINE_REPORT.md` for current results.

---

## 1. Framework structure vs. pipeline mapping

The framework defines a four-phase funnel:

```
Phase 0 → Phase 1 → Phase 2 → Phase 3 → Finalist Review
```

Our pipeline maps cleanly:

| Framework phase | Our implementation |
|---|---|
| Phase 0 — cold-start seed generator | CP-SAT cold-start (`HeadlessSolveRunner` P0); stops at first feasible; one unique seed per run |
| Phase 1 — first hard-constraint layer | CP-SAT P1 (`p1_status = OPTIMAL`); locks Tier-1 inpatient-split |
| Phase 2 — additional hard constraints | CP-SAT P2 (`p2_status = OPTIMAL`, `tier1_score = 0`); completes hard-valid schedule |
| Phase 3 — soft-constraint polishing | Timefold (`TimefoldOptimizeRunner`); 10 multi-start × 600 s; tiered fragile≫volunteer objective; hard==0 preserved |
| Finalist Review | human selection from the ranked Phase-3 output table in `PIPELINE_REPORT.md` |

**Candidate lineage notation (framework):**

```
s_i^(0) → s_i^(1) → s_i^(2) → s_i^(3)
```

Abbreviated in practice to `s_i^(0) → s_i^(2) → s_i^(3)` because P1 and P2 always run
sequentially and are evaluated together as a unit.

---

## 2. Score conventions

### Hard score

```
Q_hard(s) = 0   ← all hard violations resolved (required to pass Phase 2)
Q_hard(s) < 0   ← unresolved hard violations (schedule not usable)
```

In our implementation: `tier1_score = 0` AND `p1_status = p2_status = OPTIMAL` is the gate.
Every schedule that enters Phase 3 satisfies `Q_hard = 0`.

Phase 3 must preserve: `Q_hard(s_i^(3)) = 0`
Timefold's `TimefoldFeasibilityValidator` asserts this on every commit (25/25 versions verified).

### Soft score

```
Q_soft(s) ≤ 0   ← 0 is perfect; more negative is worse; closer to 0 is better
```

In our implementation the Timefold tiered objective is:

```
Q_soft = −(fragile × 1000) − (volunteer × 100) + Σ_healthy(10 + 3 × (coverers − 2))
```

Fragile ≫ volunteer by design. Descriptive readouts (fragile / healthy / volunteer / h→h) are
kept alongside the objective for human review but **never used as the ranking key** while a
Phase-3 objective exists.

**Phase 2 soft score** (`Q_soft,start(s_i^(2)`) = the descriptive metrics at the time the
Phase-2 schedule is committed (fragile / healthy / volunteer as recorded in `solve_run_metrics`).
This IS available — it is what the harvest ranking (`fragile ASC, h→h ASC, healthy DESC`) is
built from.

**Phase 3 improvement:**

```
Δ_i = Q_soft,final(s_i^(3)) − Q_soft,start(s_i^(2))
```

The DELTA column in `PIPELINE_REPORT.md` captures this per run. Current pipeline results
(40 harvests → 15 Phase-3 runs) show improvement on every seed: fragile Δ ranges from −4 to −11,
all 15 runs reached `fragile = 0`. This confirms the relationship between starting and final score
is case (2) from the framework — worse Phase-2 starters tend to show larger absolute improvement —
but even the best Phase-2 seeds improved.

---

## 3. Phase 0: seed generation

### What is already answered

- Each run produces a **unique** seed. Over 150+ runs across all sessions, no duplicates observed.
  The unbounded `phase0_seed_assignments` table stores every seed (commit `b6d337e`); there is no
  inventory cap.
- Phase 0 is treated as a seed generator, not an optimization stage. It stops at first feasibility.
- `PHASE0_SEED_SELECT=roundrobin` ensures distinct seeds are drawn each harvest run (fair pool
  coverage).

### Framework metrics — current state

**1. Seed count:**

```
N_0 = |S_0|
```

Currently 129+ seeds in the pool (from the Stage-1 run in `PIPELINE_REPORT.md`). The pipeline
generates seeds on demand; the pool grows each pipeline run.

**2. Structural diversity:**

```
D_0(s_i^(0), s_j^(0))
```

**Not yet formally defined or measured.** The pool exists; distance between seeds has not been
computed. The framework notes the exact definition is implementation-specific. For our problem, a
natural candidate is Hamming distance over the (resident × block) rotation assignment matrix.

**3. Marginal diversity gain:**

```
δ_i = min_{j<i} D_0(s_i^(0), s_j^(0))
```

**Not yet computed.** Would require the distance function above.

**4. Downstream value:**

Partially answered: 40 harvest runs across 40 distinct seeds produced fragile scores ranging from
4 to 12. The seed pool covers a meaningful range. Whether additional seeds would expand this range
further is unknown.

### Phase 0 stopping rule (framework):

```
N_0 ≥ N_0,min
  AND mean(δ_recent) < ε_D
  AND ΔB_downstream < ε_B
```

**Not yet evaluated.** We continue generating because the downstream benefit (a wider harvest pool
from which to cherry-pick) has not plateaued observably. The current session target is 25 seeds
per pipeline run; the full pool is 129+.

### What the framework does not address for Phase 0

- The cold-gen INFEASIBLE issue (VA coverage floor, fixed 2026-06-26, documented in
  `PIPELINE_INFEASIBLE_ROOTCAUSE.md` and memory `wipe-broke-coldgen-va-coverage`). The framework
  assumes Phase 0 always produces a feasible seed — for us this required the VA/TY aux-credit fix.
- The per-seed storage architecture (`phase0_seed_assignments` table vs. legacy blob cap of 25)
  needed to support unbounded inventory.

---

## 4. Phases 1 and 2: hard-constraint usability stages

### What is already answered

- **P1/P2 are always run in sequence** and evaluated as a unit: `s_i^(0) → s_i^(2)`.
- **Reproducibility is high.** The 99-run ICC study (memory `seed-determines-quality`) showed
  ICC fragile 0.978 / healthy 0.992 / volunteer 0.931 — the seed predicts the Phase-2 output with
  high fidelity. Repeated Phase 1/2 runs from the same seed move only ~8% of cells.
- **Policy: one Phase 1/2 run per distinct seed.** Reruns add negligible information.
- **No known routine failures.** Every seed that completes P1+P2 at OPTIMAL produces a hard-valid
  schedule. Truncated runs (UNKNOWN status) are filtered out by the hygiene query (§7.2 of
  `SCHEDULE_SEARCH_PLAN.md`).
- **Runtime varies by seed** (P1 ~50 s, varies; P2 ~120 s budget). Seeds differ not in whether
  they succeed but in how long they take.

### Phase 1/2 eligibility criterion

```
Q_hard(s_i^(2)) = 0
```

Implemented as: `p0_status = p1_status = p2_status = OPTIMAL AND tier1_score = 0`.

### Key point: Phase 2 score = eligibility, not rank

All completed Phase-2 schedules share `Q_hard = 0`. They cannot be ranked by hard score.
Ranking is done on descriptive soft metrics: `fragile ASC, h→h ASC, healthy DESC`.

### Framework metrics tracked per seed

| Framework metric | Implementation |
|---|---|
| Completion: `Q_hard = 0` | `tier1_score = 0` in `solve_runs` |
| Runtime / computational cost `C_{1/2}(s_i)` | `p1_runtime`, `p2_runtime` in `solve_runs` |
| Structural change from seed `D_{0→2}(s_i^(0), s_i^(2))` | tracked via `compare_seed_vs_final.py` (mean Δquality ~8% cell movement) |
| Repeated-run similarity `D(s_{i,a}^(2), s_{i,b}^(2))` | measured in 99-run ICC study; ICC 0.93–0.99 |
| Downstream Phase-3 performance | captured as delta in `PIPELINE_REPORT.md` |

### What the framework does not address for Phase 1/2

- The specific hard-constraint families (15 families in `RotationFeasibilityConstraintProvider`;
  the inpatient-split Tier-1 lock; the I1 GI-protection fix of 2026-06-23).
- The OPTIMAL-vs-UNKNOWN status distinction and why truncated Phase-2 schedules are excluded even
  though they pass `Q_hard = 0` at commit time (non-optimized P1/P2 = suboptimal starting point
  for Phase 3, not a framework concept).
- The BMC/TY aux-credit synthesis needed to make VA coverage feasible post-wipe.

---

## 5. Phase 2 → Phase 3 transition

### Current advancement strategy

The pipeline selects the top-K Phase-2 schedules (by `fragile ASC, h→h ASC, healthy DESC`) from
the harvest pool and advances all of them to Phase 3. The current pipeline run used K = 25
(top-25 by quality from 40 valid harvests); Stage-3 ran 15 of those.

### Advancement priority function (framework):

```
A_i = α·D_i + β·S_i + γ·P_i + η·C_i
```

where:
- `D_i` = diversity contribution
- `S_i` = starting soft score (Phase-2 metrics)
- `P_i` = estimated polishability
- `C_i` = computational cost term
- `α, β, γ, η` = project-defined weights

**Current implementation:** effectively `α = 0, β = 1, γ = 0, η = 0` — we rank purely on
starting soft metrics (Phase-2 fragile / h→h / healthy). Diversity is not yet explicitly computed;
polishability is not yet estimated independently of the starting score.

### What is and is not yet known about the Phase-2 → Phase-3 relationship

**Known:**
- `Q_soft,start(s_i^(2))` IS available (the harvest ranking metrics).
- All 15 Phase-3 runs improved on their Phase-2 start. The best Phase-2 seeds (frag 4–5) became
  `fragile = 0` post Phase-3. So does starting Phase-2 quality predict final Phase-3 quality? The
  current 15-run sample suggests yes — every run reached `fragile = 0` regardless of starting
  fragile (4 to 11), which means the relationship may be case (2) from the framework: the starting
  score predicts improvement magnitude but the final floor is the same. This needs more data to
  confirm.

**Open questions (from framework § Phase 2 to Phase 3 Questions):**
1. Does starting soft score predict final soft score, or just improvement magnitude?
2. Are some Phase-2 structures more polishable than others, independent of their starting score?
3. Does structural diversity among selected Phase-2 candidates translate into diverse Phase-3 outputs?

**Structural diversity** of the advancement set is not yet formally tracked. The 15 candidates
came from 15 distinct seeds; whether those seeds represent meaningfully diverse regions of the
solution space is unmeasured.

---

## 6. Phase 3: soft-constraint polishing

### Production config (locked 2026-06-26)

```
TimefoldOptimizeRunner <year> <srcVersion>
  → 10 parallel starts × 600 s
  → tiered fragile≫volunteer objective (TF_TIER default ON)
  → R0 move set (change + swap, lean default)
```

### Phase 3 score tracking

Best observed Phase-3 soft score after k runs:

```
B_k = max_{i ≤ k} Q_soft(s_i^(3))
```

Because scores encode fragile (×1000), volunteer (×100), and healthy (×3 per extra coverer), the
maximum (least negative) is the best overall result.

### Phase 3 variability

**Partially answered.** The benchmark matrix (48 runs: 4 variants × 4 seeds × reps × 300 s)
showed multi-start (10 independent starts) collapses run-to-run variance vs. single-start: the
worst multi-start repeat roughly equals the best single-start repeat. This is an empirical
answer to the framework's Phase-3 repeatability question: multi-start makes one run per Phase-2
schedule sufficient by averaging over the stochastic variation internally.

**Not yet answered:** within-start variance across multiple full-budget (600 s) runs from the
same Phase-2 schedule. The 48-run matrix used 300 s and 4 seeds; a formal Phase-3 repeatability
study (framework §Experiment 3: 10–20 inputs × 5–10 repeats each, full budget) has not been run.

### Phase 3 time-to-best

The `TimefoldMoveBenchRunner` captures per-start time-to-best (`move_bench.csv`,
`move_bench_multistart.csv`). From benchmark data: median time-to-best ~240 s; late improvements
observed. The 600 s budget was set with this in mind.

**Formal stopping rule (framework):**

```
B_i(t) − B_i(t − T) < ε_T
```

**Not yet implemented.** Phase 3 runs by time limit (600 s fixed). A convergence-based early
stopping rule (plateau-triggered termination) is listed as future work in
`TIMEFOLD_OPTIMIZATION_HANDOFF.md` and `SCHEDULE_SEARCH_PLAN.md §Stage 2`.

### What the framework does not address for Phase 3

- The OR-Tools #5025 root cause (why CP-SAT Phase 3 was abandoned; Timefold was adopted as a
  replacement — not a framework concept).
- The TY-as-movable-filler problem (Topic A in `TIMEFOLD_OPTIMIZATION_HANDOFF.md`): pinning TY
  at their Phase-2 placement freezes part of the feasible search space and likely caps the
  achievable soft-score floor. This is an open model-quality issue, not addressed by the framework.
- Custom move sets (R1–R4) and why default change+swap is insufficient from a feasible start
  (ejection chains needed to make feasibility-preserving neighborhood moves).

---

## 7. Finalist set

### Framework definition

```
F = { s_i^(3) : Q_hard(s_i^(3)) = 0, Q_soft(s_i^(3)) ≥ B − ε_F, D_3(s_i^(3), F) ≥ ε_D }
```

where:
- `B = max_i Q_soft(s_i^(3))` — best observed Phase-3 soft score
- `ε_F` — maximum acceptable gap from best (score-gap threshold)
- `ε_D` — minimum diversity threshold among finalists
- Target: `|F| ≈ 30–50`

### Current state

The pipeline produced 15 Phase-3 finalists in the first full run. All 15 satisfy `Q_hard = 0`
(Timefold validator asserts this). All reached `fragile = 0`.

**Score-gap threshold `ε_F`:** not yet formally defined. The current output shows a tight cluster
(all `fragile = 0`; healthy ranges from 19 to 22; volunteer ranges from 3 to 6). The
distinguishing dimension among current finalists is `healthy` and `volunteer`, not `fragile`.

**Diversity threshold `ε_D`:** not yet formally defined. The 15 finalists came from 15 distinct
seeds; structural diversity of the Phase-3 outputs relative to each other has not been measured.

**Target finalist count:** the framework targets 30–50. The current pipeline produces ~15 per run
(gated on available harvest + Phase-3 budget). Scaling to 30–50 requires either more harvest runs
or more pipeline cycles.

### What the framework does not address for finalist review

- The specific scoring dimensions (fragile / healthy / volunteer / h→h) and their clinical
  meaning.
- The constraint-fix boundary (2026-06-23 I1 fix): finalists from before that date are not
  comparable to current results.
- The `PIPELINE_REPORT.md` human-review table and the `score_and_snapshot.py --no-save` inspection
  workflow.

---

## 8. Open empirical questions (prioritized)

These are the framework's empirical questions, filtered to what is genuinely unanswered for this
pipeline. Questions already answered are excluded.

### High priority

**Phase 2 → Phase 3 relationship:**
1. Does the Phase-2 starting soft score predict the Phase-3 final soft score, or only improvement
   magnitude? The 15-run sample suggests the final floor may be seed-independent (all reached
   `fragile = 0`), but the healthy/volunteer spread suggests the starting structure still
   differentiates. Needs more data.
2. Are some Phase-2 structures more polishable than others, independent of starting score? The
   seed-determines-quality finding (ICC 0.978 for fragile) suggests the Phase-2 structure matters,
   but this was measured pre-Phase-3.

**Phase 3 repeatability:**
3. How variable are full-budget (600 s) Phase-3 runs from the same Phase-2 schedule? The 300 s
   multi-start benchmark suggests low variance with 10 starts, but this has not been confirmed at
   production budget (600 s × 10 starts).

**Phase 3 stopping rule:**
4. What is the empirically-derived plateau window `T` and threshold `ε_T` for early stopping?
   The 600 s budget is based on "median time-to-best ~240 s plus margin," not a formal KM-with-CI
   analysis. The `analyze_budget_calibration.py` upgrade to KM+CI (planned in
   `SCHEDULE_SEARCH_PLAN.md §Stage 2`) would answer this.

### Medium priority

**Finalist diversity:**
5. Do the top 15–50 Phase-3 schedules represent meaningfully different soft-objective tradeoffs?
   Current output spans healthy 19–22 and volunteer 3–6 at `fragile = 0`; whether these represent
   clinically distinct options needs human review.
6. What score gap `ε_F` from the best observed should define "near-best"? In the objective space,
   a difference of 1 volunteer = 100 points; 1 healthy = 13 points; 1 fragile = 1000 points.
   `ε_F` should be set in objective units.
7. Is the final chosen schedule robust to small changes in scoring weights?

**Phase 0 diversity:**
8. Is the Phase-0 pool (129+ seeds) structurally diverse, or are many seeds near-duplicates in
   downstream behavior? The harvest output (fragile 4–12) suggests real spread, but structural
   distance `D_0` has not been computed.
9. Has the Phase-0 seed pool reached diminishing returns? The stopping rule
   (`mean(δ_recent) < ε_D AND ΔB_downstream < ε_B`) has not been evaluated.

---

## 9. Recommended experiments

These correspond directly to the framework's §12 roadmap, adapted to the current pipeline state.

### Experiment A — Phase-3 starting-score predictivity study (framework Experiment 2)

**Status:** partially answered but not formally.

For each Phase-3 run already recorded, plot:
- x-axis: Phase-2 starting fragile (from `solve_run_metrics` on the harvest row)
- y-axis: Phase-3 final fragile (from `solve_run_metrics` on the Phase-3 row)
- secondary: improvement Δ fragile

If the floor is seed-independent (all reach fragile 0 eventually), the advancement strategy can
de-emphasize `S_i` in `A_i` and weight `D_i` (diversity) more. If the floor varies, the starting
score matters and should be the primary selection criterion.

### Experiment B — Phase-3 repeatability study (framework Experiment 3)

Select 5–10 Phase-2 harvest schedules spanning the quality range (e.g., frag 4, frag 7, frag 11).
Run each through Phase-3 (10 multi-start × 600 s) 3–5 times. Record final soft scores and
schedule structure. Estimate within-input variance. If variance is low, one run per Phase-2
schedule is confirmed sufficient. If high, elite Phase-2 schedules should receive multiple runs.

### Experiment C — Phase-3 time-to-best KM study (framework Experiment 4)

Upgrade `analyze_budget_calibration.py` to compute Kaplan-Meier on time-to-plateau (define
event = run reached within ε% of its own final objective). Derive the lower-CI stop time at
which 95% of runs have plateaued. Use this as the defensible budget rather than the current
eyeballed 600 s. This is §Stage 2 of `SCHEDULE_SEARCH_PLAN.md`.

### Experiment D — Finalist diversity study (framework Experiment 5)

After accumulating 50+ Phase-3 results, evaluate:
- Pairwise structural distance `D_3(s_i^(3), s_j^(3))` among the top 50.
- Whether the top 50 represent distinct soft-objective tradeoffs (healthy-heavy vs.
  volunteer-heavy vs. balanced).
- Whether a greedy diversity filter (add a candidate only if it is ≥ ε_D from all current
  finalists) produces a 30–50 set that covers the tradeoff space.

Formal finalist set: define `ε_F` in objective units and `ε_D` as a structural distance threshold,
then apply the framework formula above.

### Experiment E — Phase-0 diversity study (framework Phase 0 Questions 1 and 4)

Compute `D_0(s_i^(0), s_j^(0))` for the seed pool (e.g., Hamming distance on rotation assignment
matrix). Plot marginal diversity gain `δ_i` over time. Determine whether the pool has reached
structural saturation (`mean(δ_recent) < ε_D`). Cross-reference with downstream harvest quality
distribution to check whether new seeds are expanding or repeating the quality range.

---

## 10. Compute allocation guidance (current)

| Phase | Current policy | Framework alignment |
|---|---|---|
| Phase 0 | Generate on demand; `roundrobin` pool coverage; pool is unbounded | Consistent — broad seed diversity is the primary lever |
| Phase 1/2 | One run per distinct seed; filter on OPTIMAL status | Consistent with "repeated runs low-value" finding |
| Phase 2 → Phase 3 | Top-K by `fragile ASC, h→h ASC, healthy DESC`; currently K = 25 | Consistent with hybrid approach; diversity weighting `α` not yet set |
| Phase 3 | 10 multi-start × 600 s per Phase-2 candidate; one run per candidate | Reasonable given multi-start collapses variance; Experiment B will confirm |
| Finalist set | All Phase-3 completions with `hard==0`; ranked by fragile/healthy | Partial — `ε_F` and `ε_D` thresholds not yet defined |

---

## 11. Defensible stopping claim (framework §13)

The general rule:

```
N ≥ N_min
  AND Δ_recent < ε
  AND P(meaningfully better candidate remains undiscovered) < α
```

**Current state:** the pipeline has not yet reached a formal stopping criterion. With 15 Phase-3
finalists all at `fragile = 0`, the fragile dimension is saturated. The remaining uncertainty
is in healthy / volunteer tradeoffs and in whether more seeds would produce a Phase-3 finalist
that exceeds the current healthy=22 ceiling.

The defensible claim at current state:

> We generated 40 valid Phase-2 schedules from 40 distinct cold-start seeds, advanced the
> top 25 to Phase-3 optimization (15 completed), and all 15 reached the hard-constraint floor
> (fragile = 0, hard = 0). Additional computation may further explore the healthy/volunteer
> tradeoff space; the fragile dimension appears saturated at the current pipeline scale.

A fully defensible stopping claim requires Experiments A–D above to be completed and to show
diminishing returns across all soft dimensions.

---

## 12. Findings (2026-06-27) — measured by `evaluate_pipeline_data.py`

The evaluation tooling is built (`evaluate_pipeline_data.py`, read-only). Run it after the pipeline;
it reports all sections below + a verdict. Results on the current data:

### Best corner and convergence
- **Best Phase-3 corner: fragile=0, volunteer=3, healthy=22 (tier3=65).**
- **OBJECTIVE-LIMITED:** reached independently by **3 distinct seeds**, **28 runs** since the last
  improvement → rule-of-three 95% UB on P(a new run beats it) = **≤0.107**. More seed generation /
  harvest will keep re-hitting this corner, NOT exceed it. (Convergence module.)

### P2→P3 predictivity
- Starting Phase-2 soft score does **not** predict the Phase-3 final (Spearman −0.16); the final is
  near-constant (only fragile ∈ {0,3}). The floor is seed-independent.
- Seed structural distance does **not** track downstream quality (Spearman −0.02): structurally
  diverse seeds, but diversity buys no downstream quality variance.

### Fragile trade-off experiment (25 runs, `experiment_fragile_tradeoff.py`)
- Lowered the fragile weight (TF_WF) across 5→1000 and ran an equal-weight (Wf=Wv=100) test.
- **Healthy NEVER exceeded 22 at any weight.** Cheapening fragile only converts volunteer weekends
  into (clinically worse) fragile ones; it does not create healthy weekends. Equal-weighting (the
  one regime that could have escaped) hit exactly 22, never 23.
- **Conclusion: healthy=22 is a CAPACITY ceiling, not a penalty artifact.** Keep the production
  objective; the fragile trade is unfavorable. Raising healthy past 22 would require changing the
  underlying coverage capacity (more eligible coverers / rotation structure), not solver tuning.
- Experiment runs are tagged `frag-exp-*` in the DB and EXCLUDED from the production convergence
  proof (verified: proof still reads 41 production runs).

### Coverage depth (the live open question)
- Two schedules at healthy=22 are NOT equally robust. A depth-2 weekend (exactly 2 coverers) is
  "fragile-adjacent" — one lost coverer drops it to fragile. The best schedule (v72) has **17 of 22
  healthy weekends at depth-2**, 5 at depth-3, mean depth 2.23, max 3.
- The tiered objective **already rewards depth** (corr(tier3, depth-2 count) = 1.0) — depth is the
  in-tier tiebreaker, and v72 is already the depth-winner.
- **But only 3 runs sit at the f0/h22 corner — depth is barely sampled.** Unknown whether the
  fragile-adjacent count (17) is a floor or just best-of-3. The depth module reports a best-so-far
  convergence on depth-2 so the next batch will reveal improvable-vs-capacity-limited.

---

## 13. NEXT SESSION — handoff plan (P3 move sets + longer runs)

The count ceiling (healthy=22) and the fragile trade are settled. The remaining lever for a better
*true optimum* is **Phase-3 search power and depth**, not seeds/harvest. Plan for next session:

1. **P3 move-set changes — mirror Scheduler 5.0 sequential move-set transitions.** The current R0
   move set (change + swap) may be under-powered for deepening coverage. Port the sequential
   move-set transition approach from the Scheduler 5.0 project (custom move factories already exist
   in `solver/move/`: `SundayEjectionChainMoveFactory`, `SundaySwapMoveFactory` — R1–R4 variants
   built but OFF, see TIMEFOLD_OPTIMIZATION_HANDOFF.md). Goal: feasibility-preserving neighborhood
   moves that can trade coverers between weekends to deepen depth-2 weekends.
2. **Longer P3 budget.** Current budget shows ~18% of starts still improving in the last 10% of
   1000s (time-to-best module) → budget is slightly short. Raise it so late improvements aren't cut.
3. **Longer runs to find the true optimum.** Populate the f0/h22 corner with many more Phase-3 runs
   at the production objective, then re-run `evaluate_pipeline_data.py` and read the **coverage-depth
   convergence**: if depth-2 count keeps dropping below 17 → depth is improvable (keep running); if
   it plateaus → depth is capacity-limited and the only lever is raising the depth reward
   (`TF_RDEPTH`/`TF_RBASE`, currently bounded small).
4. **Re-enable trajectory logging** (`SOLVE_TRAJECTORY_CSV`) so the Phase-3 plateau module can
   compute a real no-improvement stop time instead of falling back to time-to-best.

The evaluation loop is: run more Phase-3 → `python evaluate_pipeline_data.py` → read the convergence
+ coverage-depth verdicts → decide stop vs continue vs tune. The `/evaluate-pipeline-data` skill
(Opus-only) wraps this with full interpretation.
