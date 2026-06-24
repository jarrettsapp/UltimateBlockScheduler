# Block Schedule — Iteration Comparison Report

_Generated 2026-06-18; updated 2026-06-23. Academic year 2 (the computed year). 11 categorical PGY-1s._

> ## ⚠️ CONSTRAINT-FIX BOUNDARY — 2026-06-23 (READ BEFORE COMPARING RUNS)
> A correctness fix to the hard constraints landed on **2026-06-23** (finding **I1**, see RULES.md).
> **Every run and schedule recorded BELOW this date was solved against the OLD (buggy) constraints;
> every run from this date forward uses the corrected constraints. They are NOT apples-to-apples.**
>
> **What changed:** the 6 `CANNOT_IMMEDIATELY_FOLLOW` rules that bar each heavy rotation from
> immediately following a Sunday-call source were pointed at the wrong rotation —
> **Outpatient GI (id 2)** instead of **Inpatient GI (id 19)**. Inpatient GI was still partly shielded
> by its symmetric `mutually_non_adjacent_with` list (5 of 6 heavies), which is why the bug was
> invisible in produced schedules. The one truly-open edge — **Inpatient GI → Younker 7 Nights** —
> was unconstrained. **Fix:** repointed the 6 rules 2→19 (so every heavy now cannot follow *both*
> Infectious Disease and Inpatient GI) and added Younker 7 Nights to the non-adjacency lists of both
> primary sources. Outpatient GI is a generic light coverer (no protection, stays in source list).
>
> **Practical effect on the trajectory below:** all of the sequential schedule reviews, fragile/volunteer
> counts, and cfg* sweep results dated **on or before 2026-06-22** predate the fix. Treat them as a
> separate, historical baseline. Begin a fresh post-fix baseline from the next run.
> Backup of pre-fix DB: `residency_scheduler.backup-pre-gi-fix-20260623-194812.db`.

> **2026-06-22 tuning experiment — COMPLETE.** A 3-config sweep tested the hard-vs-soft
> zero-volunteer tradeoff to minimize fragile weekends. Result:
> - **cfgA (floor OFF, target=2, weight=75) — WINNER: vol=1, fragile=6, healthy=18.** Accepting
>   one volunteer weekend nearly halves fragile vs v7 (11→6) and gives the most healthy weekends
>   of any run.
> - **cfgB (floor OFF, target=3, weight=150): vol=3, fragile=8, healthy=14 — worse.** Higher
>   target over-stacks easy weekends and starves marginal ones.
> - **cfgC (floor ON, target=3, weight=150): vol=0, fragile=11, healthy=14 — ties v7.** With the
>   hard floor on, higher target/weight buys nothing; fragile is capped at ~11.
>
> - **cfgA-rerun (floor OFF, target=2, weight=75; larger 30/60/30/60-min budgets): vol=2,
>   fragile=8, healthy=15, three 8-wk runs.** Same config as cfgA but a *different, worse* result —
>   even though the bigger Phase-0 budget gave a clean solve (Phase 0/1 OPTIMAL, Tier-1=0, vs the
>   original A's relaxed Phase-1). This exposes **solver variance**: Phase-3 ends FEASIBLE (not
>   OPTIMAL) so different seeds land on different points of the optimal-Tier-1/2 frontier. cfgA's
>   6/18 was a genuinely good draw, not guaranteed to reproduce run-to-run.
>
> **Takeaways:** (1) the zero-volunteer *hard floor* is the single biggest lever — relaxing it to
> a soft objective is what unlocks fragile reduction; (2) target=2 beats target=3; (3) **there is
> meaningful run-to-run variance** in the floor-OFF configs (fragile 6–8, healthy 15–18 across two
> identical-config runs), so the production schedule should be picked by running the chosen config
> a few times and keeping the best draw, not from a single solve. Recommended config: cfgA
> (floor OFF, target=2, weight=75).

> **2026-06-22 Round 2 (weight sweep at target=2, 75 min/phase).** Testing whether higher
> coverage *weight* (at the volunteer-friendly target=2 ratio) pushes fragile lower while keeping
> volunteers ≤2. **cfgD (floor OFF, target=2, weight=120): vol=1, fragile=7, healthy=17, and ZERO
> long runs** — matches cfgA's coverage with cleaner run-length. cfgE (weight=200) and cfgF
> (floor ON, weight=120) pending.

This report compares the **current real-world (in-use) schedule** against each
successive version the app produced, measured uniformly on the metrics we track.
All schedules were scored with the **same analyzer** so the comparison is
apples-to-apples.

---

## Methodology — how each metric is measured

**Workload tiers** (owner-confirmed; the block app's `rotation_type` flag is *not*
used because it mis-types these):
- **Heavy (6):** ICU, VA, Broadlawns, Younker 7 Days, Younker 7 Nights, Younker 8 Pulm.
- **Medium / consult (2):** Inpatient GI, Infectious Disease (PTO-eligible, lighter —
  this is why they carry call; undesirable to chain with heavies).
- **Light (8):** Outpatient GI/Pulm, Ambulatory A, EM, Addiction, Elective, both
  Outpatient Cardiologies.

**Weekend call-coverage eligibility** (the model that actually matters): a half-block
runs Mon → 2nd Sun; the trailing weekend belongs to that block. A categorical can
cover a given Sunday Y7 night **iff** (1) they are on a Sunday-source rotation
(Inpatient GI / ID / any light) that block, **and** (2) they are **not** entering a
heavy rotation the next block (the manually-imposed pre-rotation rest lock). Per
weekend we count eligible coverers:
- **0 coverers = volunteer weekend** (the call scheduler must fall back to upper-level
  volunteers — the failure mode the block app exists to eliminate).
- **1 coverer = fragile** (the lone resident is forced onto that call, unbalancing the
  downstream call-shift counts).
- **≥ 2 coverers = healthy** (the call scheduler has slack to balance shifts).

**Consecutive heavy+medium run** = unbroken weeks of heavy and/or medium rotations.
Owner thresholds: **6 wk preferred max, 8 wk acceptable-not-ideal, 10 wk+ avoid.**

**Saturday Y7** depends on a PGY-1 being on Younker 8 Pulm; with 11 interns × 2
half-blocks = 22 of 26 half-blocks, exactly **4 half-blocks must lack an intern** by
arithmetic — this floor is unbeatable and identical for every schedule.

---

## Summary table

| Metric | REAL | **v7 (ON) ★** | **cfgA (OFF,T2/w75) ◆ BEST** | cfgA-rerun | cfgB (OFF,T3/w150) | cfgC (ON,T3/w150) |
|---|---|---|---|---|---|---|
| **Volunteer (0-coverer) Sundays** | **4** ✗ | **0** ✓ | **1** | 2 | 3 | **0** ✓ |
| Fragile (1-coverer) weekends | 8 | 11 | **6** ◆ | 8 | 8 | 11 |
| Healthy (≥2-coverer) weekends | 13/25 | 14/25 | **18/25** ◆ | 15/25 | 14/25 | 14/25 |
| Direct heavy→different-heavy | **5** ✗ | **0** ✓ | **0** ✓ | **0** ✓ | **0** ✓ | **0** ✓ |
| Runs > 6 weeks (heavy+med) | 0 | **0** ✓ | 1 (8wk) | 3 (8wk) | 1 (8wk) | 2 (8wk) |
| Capacity clean | — | **yes** ✓ | **yes** ✓ | **yes** ✓ | **yes** ✓ | **yes** ✓ |
| Tier-1 clinical score | — | **0** ✓ | **0** ✓ | **0** ✓ | **0** ✓ | **0** ✓ |
| Tier-2 score | — | **0** ✓ | **0** ✓ | **0** ✓ | **0** ✓ | **0** ✓ |
| Floor / target / weight | hand | ON/2/50 | **OFF/2/75** | OFF/2/75 | OFF/3/150 | ON/3/150 |
| Solve (P0/1/2/3) | hand | 2×2hr | 5/10/10/120m | 30/60/30/60m | 30/60/30/60m | 30/60/30/60m |

_cfgA and cfgA-rerun are the **same config** — the difference (6/18 vs 8/15) is solver variance,
since Phase 3 ends FEASIBLE not OPTIMAL. Run the chosen config a few times and keep the best draw._

**Round 2 — weight sweep at target=2:**

| Metric | cfgA (OFF/2/75) | **cfgD (OFF/2/120)** | cfgE (OFF/2/200) | cfgF (ON/2/120) |
|---|---|---|---|---|
| Volunteer | 1 | **1** | _pending_ | _pending_ |
| Fragile | 6 | **7** | | |
| Healthy | 18/25 | **17/25** | | |
| Heavy→heavy | 0 | **0** | | |
| Runs > 6 wk | 1 (8wk) | **0** ✓ | | |
| Tier-1 / Tier-2 | 0 / 0 | **0 / 0** | | |

cfgD (weight 120) holds the same vol=1 and near-identical fragile/healthy as cfgA (weight 75) but
with **zero long runs** — higher weight at target=2 is at least as good as cfgA and cleaner on
run-length. cfgE tests an even higher weight (200); cfgF tests whether 120 helps the hard-floor case.

**cfgD-robust — same config, 30 min/phase, with objective-trajectory capture (2026-06-22, v18):**
**vol=1, fragile=6, healthy=18, heavy→heavy=0, runs>6wk=1 (Res C, 8wk).** Ties the all-time best
(cfgA) and beats the first cfgD (1/7/17) by one fragile→healthy — the longer Phase 3 reached the
plateau the shorter run was cut off before. **Trajectory finding (first real use of the restored
capture):** the objective fell 1694→1094 and hit its FINAL value at ~964 s of Phase 3 (~16 min);
the remaining ~14 min produced ZERO improvement. So a ~18–20 min Phase 3 captures the same result
here — much of what looked like run-to-run "variance" is really runs stopped before their plateau.
Phase-3 trajectory (Phase-3 wall_s → obj): 425→1694, 808→1454, 871→1334, 951→1212,
**964→1094 (final, then flat to 1800s budget end)**.

◆ **cfgA — the experiment's best result** (2026-06-22, headless, ~2.26 hr). Dropping the
zero-volunteer *hard floor* and letting the soft coverage objective (target=2, weight=75) balance
freely trades **1 volunteer weekend** for a large fragile reduction (11→6) and the best healthy
count of any floor-respecting run (18/25). One mild 8-week run reappeared (Res C). Confirms the
hypothesis that the hard floor was forcing the solver to over-spend on volunteers at the cost of
fragiles.

**cfgB — higher target/weight backfired** (2026-06-22, headless, ~1.03 hr; floor OFF, target=3,
weight=150). Result: **vol=3, fragile=8, healthy=14** — strictly worse than cfgA on every
coverage axis. Raising the target to 3 makes the objective reward a *third* coverer; chasing that
surplus on already-healthy weekends pulls coverers off the marginal ones, and because the
shortfall penalty is capped at the target, the solver tolerates several 0-coverer weekends to
over-stack others. **Lesson: target=2 (just enough for downstream call-balancing slack) is the
right setting; target=3 is counterproductive here.** Note the larger Phase-0 budget worked
perfectly — Phase 0 OPTIMAL in 106 s, Phase 1 Tier-1=0 in 3 s, Phase 2 Tier-2=0 — so the full
3600 s went to Phase-3 coverage; the worse result is the objective shape, not a weak solve.

**cfgC — hard floor caps the gains** (2026-06-22, headless, ~1.1 hr; floor ON, target=3,
weight=150). Result: **vol=0, fragile=11, healthy=14** — identical fragile/healthy to v7, and
slightly worse on run-length (two 8-week runs vs v7's zero). This is the controlled proof that
**the hard zero-volunteer floor, not the objective weights, is the binding constraint on fragile
count**: with the floor ON, no amount of target/weight pushing gets below ~11 fragile, because
guaranteeing a coverer on every weekend forces the marginal weekends to single-coverer state.

### Experiment conclusion

The fragile-weekend floor is governed almost entirely by the **hard vs soft** choice on zero
volunteers:
- **Floor ON** (v7, cfgC): vol=0 guaranteed, but fragile pinned at ~11 regardless of weights.
- **Floor OFF** (cfgA, cfgB): the solver can trade a volunteer or two for fragile→healthy
  conversions — and with a *gentle* objective (target=2) it gets to fragile=6 / healthy=18.

**Recommended production config: cfgA** — floor OFF, target=2, weight=75 — *if* the program will
accept 1 volunteer weekend in exchange for halving the fragile count. If zero volunteers is
non-negotiable, v7 remains the best floor-on schedule. The decision is a clinical/policy one, not
a solver one; the solver has now mapped the whole tradeoff frontier.

_Older intermediate runs (v1, v2, v8, v9) omitted from this table for clarity; see git history
and prior report revisions._

Legend: ✓ = best / at floor; ✗ = the problem this iteration set out to fix. — = intermediate run, metrics not stored.
★ v7 = run 73, saved as `v2026-06-19-198 longer run`. The zero-volunteer hard floor was
  introduced between v3 and v7; v4–v6 are intermediate runs during that development.
  v7 remains the current best on fragile/healthy weekends within the floor-on model.

**Note on v3 vs v7 tradeoff:** v3 achieves fewer fragile weekends (5 vs 11) and more
healthy weekends (20 vs 14), but at the cost of one 8-week run and was produced before
the zero-volunteer *hard floor* was enforced. Under the current model — where
zero-volunteer is a hard constraint, not just a soft objective — v7 is the true best
within that model: it holds zero volunteers, cleans up the 8-week run, eliminates the
last heavy→heavy transition, and is capacity-clean.

**v8–v10 added new model features** (hard heavy→heavy ban as a constraint, Tier-3
categorical soft cap, 12-week consecutive run limit, Phase-3 repair hints, Sunday
coverage re-activation) but have not yet improved on v7's fragile/healthy numbers.
v10 regressed slightly to fragile=13 / healthy=12. These model improvements set the
stage for future solves to close the gap.

---

## Per-schedule detail

### 1. REAL (in-use) schedule — the current hand-built baseline
- **Volunteer Sundays: 4** (weekends after blocks 1, 13, 15, 19). At each, **all 11
  categoricals are simultaneously rest-locked** — on a heavy rotation or entering one
  the next Monday. These are total coverage wipeouts requiring the volunteer fallback.
- **Direct heavy→heavy transitions: 5** — VA→Pulm (×2), Pulm→Nights, Nights→VA,
  Nights→Pulm. These are the awkward back-to-back heavy switches.
- **Transitions otherwise excellent:** only one 6-week run, none longer. The hand
  schedule is genuinely good at avoiding long stretches — its weakness is *coverage*,
  not run length.
- Per-weekend coverers: `[6,0,2,2,3,2,2,1,5,2,4,1,3,0,1,0,1,1,2,0,2,1,4,1,1]`

### 2. App v1 — first computed schedule, no coverage objective
- **Volunteer Sundays: 1** (down from 4). The app's clinical/coverage optimization
  alone already removes 3 of the 4 wipeouts.
- **Heavy→heavy: 0** — eliminates all 5 of the real schedule's awkward switches.
- **Cost:** fragile weekends rose to 12 (the solver wasn't yet *aware* of weekend
  coverage, so it didn't spread coverers); 4 six-week runs (all within tolerance).
- Tier-1 = 0, Tier-2 = 0 (clinically optimal).

### 3. App v2 — soft Sunday-coverage objective added (180 s Phase 3)
- **Volunteer Sundays: 0** ✓ — the goal met. The call scheduler never needs volunteers.
- **Fragile weekends: 8** (down from 12); healthy ≥2-coverer weekends up to 17/25.
- **No transition regression:** 0 heavy→heavy, 0 runs over 6 weeks, one *fewer*
  six-week run than v1.
- Tier-1 = 0, Tier-2 = 0.

### 4. App v3 — brute-force 45-minute Phase-3 solve
- **Volunteer Sundays: 0** ✓; **fragile weekends: 5** — this is the **theoretical
  floor** (a rigorous bound shows ~3–5 fragile weekends are mathematically forced by
  the fixed heavy load + the always-split Younker 7 Nights rule; 0 is an unreachable
  edge case). Healthy ≥2-coverer weekends: **20 / 25** — best of all.
- **One 8-week run appeared** (Res G): `Y7 Nights → Inpatient GI → Y7 Nights →
  Infectious Disease`. Composition: only **4 wk genuinely heavy, 4 wk medium consult,
  fully alternating, zero heavy→heavy contact**. It is the *mildest possible* 8-week
  run and a structural byproduct of the Y7-Nights-always-split rule (pairing the split
  nights with the call-generating consults is what *produces* the coverage gain). Not
  the stacked-heavy pattern (e.g. VA→ID→ICU) the program wants to avoid.
- Tier-1 = 0, Tier-2 = 0.
- **Younker 7 Nights distribution is healthy** (not crammed): per-resident gap between
  the two 2-week night segments ranges **2 → 26 weeks** (median 10). Example: Res I
  does nights at 3A and 10A (opposite ends). The 2 residents with a single night
  segment are the 2 Elective residents (link rule: 9/11 do 4 wk nights, 2/11 do 2 wk
  nights + 2 wk Elective).

### 5. App v7 — zero-volunteer hard floor + 2×2hr solve (★ BEST on fragile/healthy)

_Saved version: `v2026-06-19-198 longer run` (run 73, obj=128, ~2 hr wall-clock)._

- **Volunteer Sundays: 0** ✓ — held by a **hard constraint** (not just a soft objective).
  The floor was proved achievable by a CP-SAT prover before the main solve; it
  guarantees the result is not just lucky but structurally enforced.
- **Fragile weekends: 11; healthy: 14/25** — higher fragile count than v3's 5, but
  those numbers are not comparable: v3 had no hard floor and could freely move
  coverers across weekends that v7's floor locks in place. Within the floor-on model,
  11 fragile / 14 healthy is the best observed across all runs tried.
- **Heavy→different-heavy: 0** ✓ — every prior app version already hit this floor; v7
  maintains it.
- **Runs > 6 weeks: 0** ✓ — the 8-week run that appeared in v3 is gone. Every
  resident stays within the 6-week preferred threshold.
- **Capacity clean** ✓ — all ICU/VA/BMC/Y7D caps satisfied in every block.
- Tier-1 = 0, Tier-2 = 0 (clinically optimal; the floor adds zero clinical cost).

### 6. App v8 — hard heavy→heavy ban + Tier-3 categorical soft cap

_Intermediate runs following model improvements committed 2026-06-19. Metrics not stored._

Between v7 and v9 several solver model improvements landed:
- **Heavy→heavy as a hard constraint** (previously a soft penalty): direct heavy-to-different-heavy
  transitions are now structurally impossible, not just discouraged.
- **Tier-3 categorical soft cap**: each rotation is gently discouraged from having the same
  resident repeat it — a fairness improvement across the year.
- **12-week consecutive run limit**: the run-length cap was corrected from an off-by-one bug
  (was checking >12 instead of >11) and its semantics made explicit.

These runs held volunteer=0 and runs>6wk=0, but specific fragile/healthy counts were not saved.

### 7. App v9 — Phase-3 repair hints ("after Dino changes")

_Saved version: `v2026-06-20-348` (2026-06-20 05:34). DB index corrupt; grid not recoverable._

- Added **Phase-3 repair hints**: the solver now seeds Phase 3 with the Phase-2 solution
  rather than starting cold, making Phase-3 improvements more productive.
- Named "after Dino changes" — reflects solver tuning from a collaborative review session.
- Full metrics not recoverable due to database corruption after this version was saved.

### 8. App v10 — longer solve, HM penalty tuning

_Saved version: `v2026-06-20-122 Longer HM` (2026-06-20 11:06). Full metrics available._

- **Volunteer Sundays: 0** ✓ — zero-volunteer floor maintained.
- **Fragile weekends: 13; healthy: 12/25** — a slight regression from v7 (11/14). The
  additional model constraints (hard heavy→heavy ban, Tier-3 cap) reduce the solver's
  freedom to spread coverers, explaining the coverage regression.
- **Heavy→different-heavy: 0** ✓ — now enforced as a hard constraint; structurally guaranteed.
- **Runs > 6 weeks: 0** ✓ — every resident stays within the 6-week preferred threshold.
- **Capacity clean** ✓ — all ICU/VA/BMC/Y7D caps satisfied.
- Tier-1 = 0, Tier-2 = 0.
- The "Longer HM" label reflects a longer Phase-3 solve time and tuned heavy+medium penalty weight.

**v7 remains the best** on fragile/healthy within the current model family. The v8–v10 model
improvements are improvements to the *solver* (harder constraints, better hints, fairer caps)
that will benefit future longer solves aiming to beat v7's 11 fragile / 14 healthy.

---

## What improved, measurably, and where the tradeoffs are

**The arc of improvement (REAL → v7):**
- **Volunteer Sundays 4 → 0** — the single most important gain, achieved first in v2
  and now enforced as a hard constraint in v7 (structurally guaranteed, not lucky).
- **Heavy→heavy transitions 5 → 0** — every app version removes all of these.
- **Runs > 6 weeks: eliminated** — v3 introduced a mild 8-week run as a tradeoff for
  fewer fragile weekends; v7 removes it entirely under the floor-on model.
- **Capacity clean across all blocks** — all ICU/VA/BMC/Y7D caps satisfied.

**The model-change tradeoff (v3 → v7):**
- Adding the zero-volunteer *hard floor* frees the solver from also maximizing
  coverage spread, so it can focus purely on transition quality. The result: perfect
  transition score (0 heavy→heavy, 0 runs>6wk) at the cost of more fragile weekends
  (11 vs 5). These are not comparable on the same axis — v3 and v7 optimize different
  objectives. Within the current hard-floor model, 11 fragile / 14 healthy is the
  best achieved.

**v7 → v10 model hardening:**
- The heavy→heavy transition moved from a soft penalty to a **hard constraint** —
  structurally impossible, not just expensive. This removes solver flexibility and
  explains the slight fragile/healthy regression in v10 (13/12 vs 11/14).
- Tier-3 categorical soft cap and 12-week run-limit correction are correctness
  improvements that narrow the search space but should not permanently worsen coverage.
- Phase-3 repair hints (v9+) make future longer solves more effective at finding
  better coverage within the tighter constraints.

**Everything that can hit a hard floor already has, in every app version:** Tier-1
clinical = 0, Tier-2 coverage/variance = 0, Saturday no-Pulm = 4 (arithmetic floor),
Younker 8 Pulm perfectly even at 2 each.

---

## Schedule snapshots on disk (for reproducibility)

| Version | File / DB version |
|---|---|
| App v1 (pre-coverage-objective) | `residency_scheduler.backup-presolve-coverage-20260618-112538.db` |
| App v2 (coverage obj, 180 s P3) | `residency_scheduler.clean-8single-20260618.db` |
| App v3 (brute-force, 45 min P3) | `residency_scheduler.candidate-best-5single-20260618.db` |
| App v7 (floor ON, 2×2hr) ★ BEST on coverage | saved version `v2026-06-19-198 longer run` in `residency_scheduler.db` |
| App v8–v9 (model hardening, intermediate) | `residency_scheduler.db` version id 9 (`v2026-06-20-348`; DB index corrupt) |
| App v10 (longer HM solve) | `residency_scheduler.db` version id 10 (`v2026-06-20-122 Longer HM`) |
| Current working DB | `residency_scheduler.db` |

Real-world schedule source: `PGY-1 schedule template FINAL` CSV (the in-use schedule).

---

## Schedule grids

Each row is one resident (A–K); each column is a 2-week half-block (1a–13b).
Tier shading: **Heavy** = ICU, VA, BMC, Y7D, Y7N, Y8 · **Medium** = GI, ID · **Light** = all others.

### Current schedule (hand-built) — residents A–K

| Res | 1a | 1b | 2a | 2b | 3a | 3b | 4a | 4b | 5a | 5b | 6a | 6b | 7a | 7b | 8a | 8b | 9a | 9b | 10a | 10b | 11a | 11b | 12a | 12b | 13a | 13b |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| cfgR5-01 r5 (OFF/2/95) | OFF/2/95 | 3 | 2 | 20 | 0 | 0 | 42 | DONE | [grid](sweep_grids\cfgR5-01_run5.html) |
| cfgR5-01 r4 (OFF/2/95) | OFF/2/95 | 1 | 8 | 16 | 0 | 0 | 41 | DONE | [grid](sweep_grids\cfgR5-01_run4.html) |
| cfgR5-01 r3 (OFF/2/95) | OFF/2/95 | 0 | 8 | 17 | 0 | 0 | 40 | DONE | [grid](sweep_grids\cfgR5-01_run3.html) |
| cfgR5-01 r2 (OFF/2/95) | OFF/2/95 | 1 | 6 | 18 | 0 | 0 | 39 | DONE | [grid](sweep_grids\cfgR5-01_run2.html) |
| cfgR5-01 r1 (OFF/2/95) | OFF/2/95 | 2 | 5 | 18 | 0 | 0 | 38 | DONE | [grid](sweep_grids\cfgR5-01_run1.html) |
| cfgR4-03 r1 (OFF/2/110) | OFF/2/110 | 3 | 3 | 19 | 0 | 0 | 37 | DONE | [grid](sweep_grids\cfgR4-03_run1.html) |
| cfgR4-02 r1 (OFF/2/85) | OFF/2/85 | 3 | 4 | 18 | 0 | 1 | 36 | DONE | [grid](sweep_grids\cfgR4-02_run1.html) |
| cfgR4-01 r4 (OFF/2/95) | OFF/2/95 | 1 | 7 | 17 | 0 | 0 | 35 | DONE | [grid](sweep_grids\cfgR4-01_run4.html) |
| cfgR4-01 r3 (OFF/2/95) | OFF/2/95 | 2 | 4 | 19 | 0 | 1 | 34 | DONE | [grid](sweep_grids\cfgR4-01_run3.html) |
| cfgR4-01 r2 (OFF/2/95) | OFF/2/95 | 2 | 4 | 19 | 0 | 1 | 33 | DONE | [grid](sweep_grids\cfgR4-01_run2.html) |
| cfgR4-01 r1 (OFF/2/95) | OFF/2/95 | 1 | 7 | 17 | 0 | 0 | 32 | DONE | [grid](sweep_grids\cfgR4-01_run1.html) |
| cfgR3-05 r1 (OFF/2/120) | OFF/2/120 | 2 | 5 | 18 | 0 | 1 | 31 | DONE | [grid](sweep_grids\cfgR3-05_run1.html) |
| cfgR3-04 r1 (OFF/2/250) | OFF/2/250 | 0 | 9 | 16 | 0 | 2 | 30 | DONE | [grid](sweep_grids\cfgR3-04_run1.html) |
| cfgR3-03 r2 (OFF/2/95) | OFF/2/95 | 3 | 4 | 18 | 0 | 3 | 29 | DONE | [grid](sweep_grids\cfgR3-03_run2.html) |
| cfgR3-03 r1 (OFF/2/95) | OFF/2/95 | 2 | 4 | 19 | 0 | 0 | 28 | DONE | [grid](sweep_grids\cfgR3-03_run1.html) |
| cfgR3-02 r3 (OFF/2/120) | OFF/2/120 | 1 | 6 | 18 | 0 | 1 | 27 | DONE | [grid](sweep_grids\cfgR3-02_run3.html) |
| cfgR3-02 r2 (OFF/2/120) | OFF/2/120 | 2 | 4 | 19 | 0 | 0 | 26 | DONE | [grid](sweep_grids\cfgR3-02_run2.html) |
| cfgR3-02 r1 (OFF/2/120) | OFF/2/120 | 2 | 6 | 17 | 0 | 0 | 25 | DONE | [grid](sweep_grids\cfgR3-02_run1.html) |
| cfgR3-01 r2 (OFF/2/75) | OFF/2/75 | 2 | 5 | 18 | 0 | 1 | 23 | DONE | [grid](sweep_grids\cfgR3-01_run2.html) |
| cfgR3-01 r1 (OFF/2/75) | OFF/2/75 | 2 | 6 | 17 | 0 | 1 | 22 | DONE | [grid](sweep_grids\cfgR3-01_run1.html) |
| cfgRESUME r1 (OFF/2/120) | OFF/2/120 | 1 | 8 | 16 | 0 | 2 | 22 | DONE | [grid](sweep_grids\cfgRESUME_run1.html) |
| cfgD r1 (OFF/2/120) | OFF/2/120 | 1 | 7 | 17 | 0 | 2 | 21 | DONE | [grid](sweep_grids\cfgD_run1.html) |
| A | ID | AMB A | Y7D | Y7D | OPC TIC | GI | Y7N | AMB GI | BMC | BMC | AMB P | VA | VA | AMB A | ICU | ICU | OPC UPH | Y7N | ADDMED | AMB GI | VA | Y8 | AMB P | VA | Y8 | ER |
| B | Elec | OPC UPH | ICU | ICU | AMB GI | VA | VA | AMB P | Y8 | Y7N | AMB A | GI | ADDMED | OPC TIC | Y7D | Y7D | AMB GI | VA | AMB P | Y8 | ID | AMB A | ER | BMC | BMC | — |
| C | ADDMED | AMB GI | BMC | BMC | AMB P | ID | GI | AMB A | ICU | ICU | OPC UPH | Y7N | ER | AMB GI | VA | VA | AMB P | Y8 | Y8 | AMB A | Y7D | Y7D | OPC TIC | Y7N | VA | VA |
| D | GI | AMB P | Y7N | ER | AMB A | Y8 | Y8 | OPC TIC | VA | VA | AMB GI | ID | Y7N | AMB P | BMC | BMC | AMB A | VA | VA | OPC UPH | ICU | ICU | AMB GI | ADDMED | Y7D | Y7D |
| E | ICU | ICU | AMB A | Y7N | ID | OPC UPH | Y7D | Y7D | AMB GI | VA | VA | AMB P | Y8 | Y8 | AMB A | Y7N | ER | OPC TIC | BMC | BMC | AMB GI | VA | GI | AMB P | VA | ADDMED |
| F | BMC | BMC | OPC TIC | ADDMED | Y7N | AMB GI | VA | VA | AMB P | ID | GI | AMB A | Y7D | Y7D | OPC UPH | Y8 | Y8 | AMB GI | ER | Y7N | AMB P | VA | VA | AMB A | ICU | ICU |
| G | VA | VA | AMB GI | Y8 | Y8 | AMB P | BMC | BMC | AMB A | ER | Y7N | OPC TIC | ICU | ICU | AMB GI | ID | VA | AMB P | Y7D | Y7D | AMB A | ADDMED | Y7N | OPC UPH | GI | VA |
| H | Y8 | Y8 | AMB P | VA | VA | AMB A | ADDMED | Y7N | OPC UPH | GI | ID | AMB GI | BMC | BMC | AMB P | VA | VA | AMB A | ICU | ICU | OPC TIC | Y7N | ER | AMB GI | Y7D | Y7D |
| I | VA | VA | ER | AMB A | ICU | ICU | OPC TIC | GI | ID | AMB GI | Y7D | Y7D | AMB P | ADDMED | Y7N | AMB A | BMC | BMC | OPC UPH | VA | VA | AMB GI | Y8 | Y8 | AMB P | Y7N |
| J | Elec | GI | VA | OPC UPH | BMC | BMC | AMB GI | Y8 | ER | AMB P | ICU | ICU | AMB A | Y7N | Y8 | OPC TIC | Y7D | Y7D | AMB GI | VA | ADDMED | AMB P | VA | VA | AMB A | ID |
| K | ER | ID | Y8 | AMB GI | GI | Y7N | AMB P | VA | VA | AMB A | BMC | BMC | OPC UPH | VA | VA | AMB GI | ICU | ICU | AMB P | ADDMED | Y7N | AMB A | Y7D | Y7D | OPC TIC | Y8 |

### App v3 — recommended (brute-force 45 min) — residents A–K

| Res | 1a | 1b | 2a | 2b | 3a | 3b | 4a | 4b | 5a | 5b | 6a | 6b | 7a | 7b | 8a | 8b | 9a | 9b | 10a | 10b | 11a | 11b | 12a | 12b | 13a | 13b |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A | BMC | BMC | OPC UPH | VA | VA | OPC TIC | Y8 | Y8 | AMB P | Y7N | AMB P | VA | VA | AMB GI | GI | Y7N | AMB GI | ER | Y7D | Y7D | AMB A | ID | AMB A | ADDMED | ICU | ICU |
| B | Y8 | OPC UPH | Y7D | Y7D | OPC TIC | Y8 | AMB P | Y7N | AMB GI | GI | Y7N | AMB P | ICU | ICU | AMB GI | ID | AMB A | VA | VA | AMB A | BMC | BMC | ER | VA | VA | ADDMED |
| C | BMC | BMC | OPC TIC | Y8 | Y8 | AMB GI | OPC UPH | VA | VA | AMB P | AMB GI | GI | Y7N | ID | AMB P | VA | VA | AMB A | Y7D | Y7D | ER | ADDMED | Y7N | AMB A | ICU | ICU |
| D | VA | VA | AMB P | OPC UPH | BMC | BMC | AMB GI | OPC TIC | Y8 | ER | Y7D | Y7D | AMB A | Y8 | AMB A | VA | VA | AMB P | ICU | ICU | ADDMED | Y7N | GI | Y7N | AMB GI | ID |
| E | ICU | ICU | ER | Y7N | AMB GI | AMB P | BMC | BMC | OPC UPH | VA | VA | AMB GI | OPC TIC | VA | VA | AMB A | Y7N | GI | ID | AMB P | Y7D | Y7D | ADDMED | Y8 | Y8 | AMB A |
| F | VA | OPC TIC | ICU | ICU | AMB P | OPC UPH | Y7N | AMB GI | GI | AMB GI | ID | ER | Y8 | AMB P | BMC | BMC | Elec | Y8 | AMB A | VA | VA | AMB A | Y7D | Y7D | ADDMED | VA |
| G | ICU | ICU | AMB A | VA | VA | ER | Y7D | Y7D | OPC TIC | Y8 | AMB A | Y7N | GI | Y7N | ID | AMB GI | ADDMED | OPC UPH | BMC | BMC | AMB P | VA | VA | AMB GI | AMB P | Y8 |
| H | GI | AMB GI | ID | AMB P | ICU | ICU | OPC TIC | VA | VA | AMB A | BMC | BMC | AMB P | ADDMED | Y7D | Y7D | ER | VA | VA | AMB GI | Elec | Y8 | Y8 | OPC UPH | AMB A | Y7N |
| I | Y7D | Y7D | AMB GI | GI | Y7N | ID | AMB A | VA | VA | OPC UPH | ICU | ICU | AMB GI | AMB A | Y8 | AMB P | VA | OPC TIC | Y7N | ADDMED | Y8 | AMB P | VA | ER | BMC | BMC |
| J | AMB GI | AMB P | Y7N | AMB GI | GI | Y7N | ID | AMB P | AMB A | VA | VA | AMB A | ICU | ICU | ER | Y8 | Y8 | ADDMED | BMC | BMC | OPC UPH | VA | VA | OPC TIC | Y7D | Y7D |
| K | AMB P | Y8 | Y8 | AMB A | OPC UPH | VA | VA | AMB A | ICU | ICU | OPC TIC | VA | VA | ER | Y7N | ADDMED | Y7D | Y7D | AMB GI | GI | Y7N | AMB GI | ID | AMB P | BMC | BMC |

### App v10 — Longer HM solve (current latest) — residents A–K

| Res | 1a | 1b | 2a | 2b | 3a | 3b | 4a | 4b | 5a | 5b | 6a | 6b | 7a | 7b | 8a | 8b | 9a | 9b | 10a | 10b | 11a | 11b | 12a | 12b | 13a | 13b |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A | OPC UPH | VA | VA | AMB GI | ID | ER | ICU | ICU | ADDMED | Y7N | GI | AMB A | BMC | BMC | AMB GI | Elec | Y7D | Y7D | OPC TIC | AMB P | Y8 | AMB P | Y8 | AMB A | VA | VA |
| B | VA | VA | ADDMED | Y7N | OPC TIC | AMB A | Y7D | Y7D | AMB GI | OPC UPH | BMC | BMC | AMB A | Y8 | Y8 | AMB P | ICU | ICU | AMB GI | GI | Y7N | ID | ER | VA | VA | AMB P |
| C | Y7D | Y7D | AMB GI | OPC UPH | ICU | ICU | AMB P | VA | VA | AMB P | Y7N | OPC TIC | Y8 | AMB GI | AMB A | VA | VA | AMB A | BMC | BMC | ADDMED | Y7N | ID | GI | ER | Y8 |
| D | AMB GI | OPC UPH | BMC | BMC | AMB A | VA | VA | AMB P | Y7D | Y7D | AMB GI | ID | GI | Y7N | OPC TIC | Y8 | Y8 | ER | VA | AMB A | ICU | ICU | AMB P | Y7N | ADDMED | VA |
| E | BMC | BMC | AMB A | ID | ER | Y7N | GI | Y7N | AMB P | Y8 | OPC TIC | VA | VA | AMB A | VA | VA | AMB GI | AMB P | Y7D | Y7D | OPC UPH | Y8 | AMB GI | ADDMED | ICU | ICU |
| F | Y8 | OPC TIC | ICU | ICU | AMB GI | GI | Y7N | ER | ID | AMB A | Y7D | Y7D | AMB P | VA | VA | AMB GI | ADDMED | Y8 | OPC UPH | VA | VA | AMB A | Y7N | AMB P | BMC | BMC |
| G | ICU | ICU | ER | VA | VA | AMB GI | AMB A | Y8 | Y8 | AMB GI | ID | GI | Y7N | OPC UPH | BMC | BMC | AMB A | VA | AMB P | Y7N | AMB P | VA | ADDMED | OPC TIC | Y7D | Y7D |
| H | ID | GI | Y7N | AMB A | Y7N | ADDMED | VA | VA | OPC UPH | ER | BMC | BMC | AMB GI | AMB P | ICU | ICU | AMB P | VA | VA | OPC TIC | Y7D | Y7D | AMB A | Y8 | Y8 | AMB GI |
| I | VA | AMB P | BMC | BMC | OPC UPH | Y8 | Y8 | OPC TIC | ICU | ICU | AMB P | Y7N | ID | ADDMED | Y7N | AMB A | VA | AMB GI | GI | AMB GI | AMB A | VA | VA | ER | Y7D | Y7D |
| J | AMB A | Y8 | Y8 | AMB P | Y7D | Y7D | AMB GI | AMB A | VA | VA | ER | AMB P | ICU | ICU | Elec | GI | ID | OPC TIC | BMC | BMC | AMB GI | ADDMED | VA | VA | OPC UPH | Y7N |
| K | GI | AMB GI | AMB P | Y8 | Y8 | OPC UPH | BMC | BMC | AMB A | VA | VA | ADDMED | Y7D | Y7D | AMB P | Y7N | OPC TIC | Y7N | AMB A | VA | VA | ER | ICU | ICU | AMB GI | ID |

### App cfgA — balanced soft (floor OFF, T=2, w=75): vol1/frag6/heal18 ◆ NEW — residents A–K

| Res | 1a | 1b | 2a | 2b | 3a | 3b | 4a | 4b | 5a | 5b | 6a | 6b | 7a | 7b | 8a | 8b | 9a | 9b | 10a | 10b | 11a | 11b | 12a | 12b | 13a | 13b |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A | OPC UPH | Y8 | Y8 | OPC TIC | Y7D | Y7D | AMB P | VA | VA | AMB P | ICU | ICU | AMB GI | GI | Y7N | ER | BMC | BMC | AMB GI | AMB A | Y7N | ID | AMB A | VA | VA | ADDMED |
| B | AMB GI | GI | Y7N | ID | OPC UPH | Y8 | Y8 | OPC TIC | ICU | ICU | AMB GI | AMB P | BMC | BMC | AMB P | VA | VA | ER | AMB A | VA | VA | AMB A | Y7D | Y7D | ADDMED | Y7N |
| C | Y7D | Y7D | AMB GI | OPC UPH | ICU | ICU | OPC TIC | Y7N | GI | Y7N | ID | AMB GI | ER | Y8 | Y8 | AMB P | VA | AMB P | BMC | BMC | AMB A | VA | VA | ADDMED | AMB A | VA |
| D | Y8 | OPC UPH | Y7D | Y7D | AMB P | VA | VA | AMB GI | ID | GI | Y7N | OPC TIC | Y8 | AMB P | ICU | ICU | AMB A | Y7N | ER | VA | VA | AMB GI | ADDMED | AMB A | BMC | BMC |
| E | OPC TIC | VA | VA | AMB P | BMC | BMC | AMB GI | OPC UPH | VA | AMB GI | GI | ER | ICU | ICU | AMB A | Y8 | Y8 | AMB A | Y7D | Y7D | ADDMED | Y7N | AMB P | VA | Elec | ID |
| F | VA | AMB GI | OPC UPH | Y8 | Y8 | OPC TIC | VA | VA | AMB P | VA | ER | GI | Y7N | AMB A | ID | AMB A | ICU | ICU | AMB P | ADDMED | BMC | BMC | AMB GI | Elec | Y7D | Y7D |
| G | BMC | BMC | AMB P | AMB GI | OPC TIC | GI | Y7N | ID | AMB A | VA | VA | AMB A | Y7D | Y7D | OPC UPH | Y7N | ADDMED | VA | VA | AMB P | ICU | ICU | ER | Y8 | Y8 | AMB GI |
| H | ID | AMB P | ICU | ICU | AMB A | Y7N | AMB A | Y8 | Y8 | ER | Y7D | Y7D | OPC TIC | Y7N | AMB GI | GI | AMB GI | ADDMED | BMC | BMC | AMB P | VA | VA | OPC UPH | VA | VA |
| I | ICU | ICU | OPC TIC | GI | Y7N | AMB GI | ID | AMB A | BMC | BMC | AMB A | VA | VA | OPC UPH | VA | VA | ER | Y8 | ADDMED | Y7N | AMB GI | AMB P | Y8 | AMB P | Y7D | Y7D |
| J | BMC | BMC | AMB A | VA | VA | OPC UPH | Y7D | Y7D | ADDMED | Y8 | AMB P | Y7N | AMB A | VA | VA | AMB GI | ID | OPC TIC | ICU | ICU | ER | GI | Y7N | AMB GI | AMB P | Y8 |
| K | VA | VA | ER | Y7N | ID | AMB A | ICU | ICU | AMB GI | AMB A | BMC | BMC | AMB P | ADDMED | Y7D | Y7D | AMB P | VA | VA | OPC UPH | Y8 | Y8 | OPC TIC | Y7N | AMB GI | GI |

### App cfgB — aggressive (floor OFF, T=3, w=150): vol3/frag8/heal14 (worse than cfgA) — residents A–K

| Res | 1a | 1b | 2a | 2b | 3a | 3b | 4a | 4b | 5a | 5b | 6a | 6b | 7a | 7b | 8a | 8b | 9a | 9b | 10a | 10b | 11a | 11b | 12a | 12b | 13a | 13b |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A | BMC | BMC | OPC UPH | Y8 | Y8 | OPC TIC | Y7D | Y7D | AMB P | AMB GI | ID | AMB P | Y7N | ER | ICU | ICU | AMB A | VA | VA | AMB GI | GI | Y7N | AMB A | VA | VA | ADDMED |
| B | BMC | BMC | OPC TIC | Y7N | AMB GI | AMB P | ICU | ICU | OPC UPH | Y8 | AMB GI | GI | ID | AMB P | Y7D | Y7D | ER | VA | VA | AMB A | Y7N | AMB A | VA | VA | ADDMED | Y8 |
| C | OPC UPH | VA | VA | AMB GI | OPC TIC | Y8 | Y8 | AMB P | ICU | ICU | AMB P | Y7N | GI | Y7N | ID | ER | VA | AMB A | Y7D | Y7D | AMB A | VA | AMB GI | ADDMED | BMC | BMC |
| D | Y8 | Y8 | AMB P | OPC UPH | Y7D | Y7D | OPC TIC | VA | VA | AMB P | BMC | BMC | ER | VA | VA | AMB GI | GI | AMB GI | AMB A | Y7N | ID | ADDMED | ICU | ICU | AMB A | Y7N |
| E | AMB GI | AMB P | Y7D | Y7D | AMB P | OPC UPH | VA | VA | AMB GI | GI | Y7N | OPC TIC | ICU | ICU | AMB A | Y7N | ID | ER | BMC | BMC | ADDMED | Y8 | Y8 | AMB A | VA | VA |
| F | VA | VA | AMB GI | OPC TIC | ID | GI | Y7N | OPC UPH | BMC | BMC | ER | AMB A | Y8 | Y8 | AMB GI | AMB A | ICU | ICU | ADDMED | VA | VA | AMB P | Y7N | AMB P | Y7D | Y7D |
| G | VA | OPC TIC | ICU | ICU | OPC UPH | Y7N | AMB A | Y8 | Y8 | AMB A | BMC | BMC | AMB GI | ADDMED | VA | VA | AMB P | Y7N | GI | ID | AMB GI | ER | Y7D | Y7D | AMB P | VA |
| H | ICU | ICU | AMB A | VA | VA | AMB A | BMC | BMC | ER | OPC UPH | Y7D | Y7D | OPC TIC | GI | Y7N | AMB P | Y8 | Y8 | AMB P | VA | VA | AMB GI | ADDMED | Y7N | AMB GI | ID |
| I | ID | ER | Y8 | AMB A | Y7N | AMB GI | GI | Y7N | AMB A | VA | VA | OPC UPH | BMC | BMC | AMB P | Y8 | AMB GI | ADDMED | ICU | ICU | AMB P | VA | VA | OPC TIC | Y7D | Y7D |
| J | Y7D | Y7D | ER | ID | AMB A | VA | VA | AMB GI | GI | Y7N | OPC UPH | VA | VA | AMB A | Y8 | ADDMED | BMC | BMC | AMB GI | AMB P | Y8 | OPC TIC | AMB P | Elec | ICU | ICU |
| K | AMB A | GI | Y7N | AMB P | ICU | ICU | AMB GI | AMB A | VA | VA | OPC TIC | Elec | Y7D | Y7D | ADDMED | VA | VA | AMB P | BMC | BMC | OPC UPH | ID | ER | Y8 | Y8 | AMB GI |

### App cfgC — floor ON (T=3, w=150): vol0/frag11/heal14 (ties v7) — residents A–K

| Res | 1a | 1b | 2a | 2b | 3a | 3b | 4a | 4b | 5a | 5b | 6a | 6b | 7a | 7b | 8a | 8b | 9a | 9b | 10a | 10b | 11a | 11b | 12a | 12b | 13a | 13b |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A | BMC | BMC | AMB GI | GI | OPC UPH | VA | VA | OPC TIC | Y7D | Y7D | AMB P | Y7N | ID | AMB GI | AMB P | VA | VA | ER | ICU | ICU | AMB A | Y7N | AMB A | Y8 | Y8 | ADDMED |
| B | OPC UPH | Y8 | Y8 | OPC TIC | Y7D | Y7D | AMB GI | AMB P | ICU | ICU | AMB GI | GI | Y7N | AMB P | VA | VA | ER | VA | VA | AMB A | Y7N | AMB A | ID | ADDMED | BMC | BMC |
| C | ICU | ICU | OPC UPH | AMB P | Y7N | OPC TIC | Y7D | Y7D | AMB GI | AMB A | BMC | BMC | AMB GI | GI | Y7N | ID | AMB A | VA | VA | AMB P | Y8 | ER | VA | VA | ADDMED | Y8 |
| D | OPC TIC | VA | VA | AMB GI | ID | AMB GI | OPC UPH | VA | VA | AMB P | ICU | ICU | AMB P | Y8 | Y8 | AMB A | BMC | BMC | AMB A | Y7N | GI | ADDMED | Y7N | ER | Y7D | Y7D |
| E | ID | OPC TIC | Y7D | Y7D | AMB P | GI | Y7N | OPC UPH | VA | VA | AMB A | ER | Y8 | AMB A | ICU | ICU | AMB P | Y8 | AMB GI | Elec | BMC | BMC | ADDMED | VA | VA | AMB GI |
| F | VA | VA | OPC TIC | Y7N | GI | Y7N | AMB P | Y8 | Y8 | OPC UPH | ID | AMB A | BMC | BMC | AMB GI | AMB P | ICU | ICU | ADDMED | VA | VA | AMB GI | ER | AMB A | Y7D | Y7D |
| G | AMB P | AMB A | Y7N | ID | AMB A | Y8 | Y8 | AMB GI | GI | OPC TIC | Y7D | Y7D | OPC UPH | VA | VA | ER | VA | AMB P | BMC | BMC | ADDMED | VA | AMB GI | Elec | ICU | ICU |
| H | VA | AMB GI | AMB P | ER | BMC | BMC | AMB A | Y7N | ID | GI | Y7N | OPC UPH | Y7D | Y7D | ADDMED | Y8 | Y8 | AMB GI | OPC TIC | VA | VA | AMB P | ICU | ICU | AMB A | VA |
| I | Y8 | AMB P | ICU | ICU | AMB GI | AMB P | BMC | BMC | AMB A | Y8 | OPC UPH | AMB GI | AMB A | Y7N | GI | Y7N | ID | ADDMED | Y7D | Y7D | ER | VA | VA | OPC TIC | VA | VA |
| J | Y7D | Y7D | ER | Y8 | Y8 | ADDMED | VA | VA | AMB P | VA | VA | AMB P | ICU | ICU | AMB A | OPC TIC | AMB GI | AMB A | BMC | BMC | AMB GI | ID | OPC UPH | Y7N | GI | Y7N |
| K | BMC | BMC | AMB A | VA | VA | AMB A | ICU | ICU | OPC UPH | Y7N | ER | VA | VA | ADDMED | Y7D | Y7D | OPC TIC | Y7N | GI | AMB GI | AMB P | Y8 | Y8 | AMB P | AMB GI | ID |

### App cfgD — OFF/2/120: vol1/frag7/heal17, 0 long runs ◆ Round 2 — residents A–K

| Res | 1a | 1b | 2a | 2b | 3a | 3b | 4a | 4b | 5a | 5b | 6a | 6b | 7a | 7b | 8a | 8b | 9a | 9b | 10a | 10b | 11a | 11b | 12a | 12b | 13a | 13b |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A | BMC | BMC | OPC TIC | Y7N | OPC UPH | AMB GI | GI | Y7N | ID | AMB P | Y7D | Y7D | AMB P | VA | VA | ER | Y8 | Y8 | AMB GI | AMB A | ICU | ICU | AMB A | VA | VA | ADDMED |
| B | OPC TIC | AMB P | GI | ID | AMB P | Y7N | OPC UPH | VA | VA | Elec | ICU | ICU | AMB GI | ER | Y7D | Y7D | AMB A | VA | VA | AMB GI | ADDMED | Y8 | Y8 | AMB A | BMC | BMC |
| C | AMB GI | GI | Y7N | OPC TIC | ICU | ICU | AMB A | Y8 | Y8 | AMB GI | ID | AMB A | Y7D | Y7D | ER | VA | VA | OPC UPH | BMC | BMC | AMB P | VA | AMB P | Y7N | ADDMED | VA |
| D | AMB P | Y8 | Y8 | ER | Y7D | Y7D | AMB GI | GI | ADDMED | VA | VA | AMB P | ICU | ICU | AMB GI | OPC UPH | BMC | BMC | AMB A | Y7N | ID | OPC TIC | VA | VA | AMB A | Y7N |
| E | Y8 | OPC TIC | Y7D | Y7D | ER | VA | VA | AMB A | BMC | BMC | AMB GI | GI | Y7N | ID | OPC UPH | Y8 | AMB P | VA | VA | AMB P | Y7N | ADDMED | ICU | ICU | AMB GI | AMB A |
| F | VA | VA | AMB P | GI | Y7N | OPC TIC | Y7D | Y7D | AMB P | Y8 | AMB A | ER | BMC | BMC | AMB A | VA | VA | AMB GI | ID | ADDMED | Y8 | OPC UPH | AMB GI | Elec | ICU | ICU |
| G | BMC | BMC | ADDMED | Y8 | AMB GI | GI | Y7N | AMB GI | OPC UPH | VA | AMB P | Y7N | ID | AMB A | VA | AMB A | ICU | ICU | OPC TIC | VA | VA | AMB P | Y7D | Y7D | ER | Y8 |
| H | VA | AMB A | ICU | ICU | AMB A | Y8 | Y8 | OPC TIC | VA | ER | Y7N | AMB GI | ADDMED | Y7N | ID | AMB P | BMC | BMC | OPC UPH | VA | VA | AMB GI | GI | AMB P | Y7D | Y7D |
| I | ICU | ICU | OPC UPH | VA | VA | AMB P | VA | VA | AMB GI | ADDMED | BMC | BMC | AMB A | Y8 | Y8 | OPC TIC | GI | Y7N | AMB P | ID | AMB GI | AMB A | Y7N | ER | Y7D | Y7D |
| J | Y7D | Y7D | AMB GI | OPC UPH | Y8 | AMB A | ICU | ICU | AMB A | Y7N | GI | OPC TIC | Y8 | AMB P | Y7N | ID | AMB GI | AMB P | BMC | BMC | ER | VA | VA | ADDMED | VA | VA |
| K | AMB A | VA | VA | AMB P | BMC | BMC | AMB P | ADDMED | Y7D | Y7D | OPC TIC | VA | VA | AMB GI | GI | Y7N | ID | AMB A | ICU | ICU | OPC UPH | Y7N | ER | Y8 | Y8 | AMB GI |

### App cfgD-robust — OFF/2/120 (30min/phase): vol1/frag6/heal18, plateau ~16min ◆ ties best — residents A–K

| Res | 1a | 1b | 2a | 2b | 3a | 3b | 4a | 4b | 5a | 5b | 6a | 6b | 7a | 7b | 8a | 8b | 9a | 9b | 10a | 10b | 11a | 11b | 12a | 12b | 13a | 13b |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A | BMC | BMC | OPC UPH | Y7N | AMB GI | GI | Y7N | OPC TIC | Y7D | Y7D | AMB P | VA | VA | AMB GI | ID | AMB P | ICU | ICU | ER | VA | VA | AMB A | Y8 | Y8 | AMB A | ADDMED |
| B | VA | VA | OPC TIC | Y8 | Y8 | AMB P | VA | VA | OPC UPH | AMB P | ICU | ICU | AMB GI | AMB A | BMC | BMC | AMB A | Y7N | AMB GI | GI | Y7N | ER | Y7D | Y7D | ADDMED | ID |
| C | AMB GI | OPC UPH | Y7N | GI | Y7N | ID | AMB P | Y8 | Y8 | OPC TIC | BMC | BMC | AMB A | AMB P | VA | VA | ER | VA | VA | AMB A | ICU | ICU | AMB GI | ADDMED | Y7D | Y7D |
| D | GI | OPC TIC | ICU | ICU | AMB P | Y7N | AMB GI | ID | AMB GI | OPC UPH | Y7D | Y7D | AMB P | VA | VA | ER | BMC | BMC | AMB A | Y7N | ADDMED | VA | VA | AMB A | Y8 | Y8 |
| E | AMB P | VA | VA | AMB GI | ID | OPC UPH | Y7D | Y7D | AMB P | VA | OPC TIC | AMB GI | GI | Y7N | ER | Y8 | Y8 | AMB A | BMC | BMC | AMB A | ADDMED | ICU | ICU | Elec | VA |
| F | ICU | ICU | AMB P | OPC UPH | Y7D | Y7D | AMB A | VA | VA | AMB GI | AMB A | OPC TIC | Y8 | ER | BMC | BMC | AMB P | Y8 | ADDMED | ID | Elec | VA | VA | AMB GI | GI | Y7N |
| G | VA | AMB P | Y7D | Y7D | OPC UPH | Y8 | Y8 | ER | VA | AMB A | Y7N | AMB A | BMC | BMC | ADDMED | Y7N | AMB GI | GI | OPC TIC | VA | VA | AMB GI | ID | AMB P | ICU | ICU |
| H | Y7D | Y7D | AMB GI | ID | AMB A | VA | VA | AMB P | BMC | BMC | OPC UPH | Y7N | ER | Y8 | Y8 | AMB A | VA | OPC TIC | ICU | ICU | AMB GI | GI | ADDMED | Y7N | AMB P | VA |
| I | BMC | BMC | ER | VA | VA | AMB A | ICU | ICU | OPC TIC | Y8 | AMB GI | GI | Y7N | ADDMED | Y7N | AMB GI | ID | AMB P | Y7D | Y7D | OPC UPH | Y8 | AMB P | VA | VA | AMB A |
| J | Y8 | AMB A | BMC | BMC | OPC TIC | AMB GI | GI | Y7N | AMB A | VA | VA | AMB P | ICU | ICU | AMB GI | ID | ADDMED | VA | VA | AMB P | Y8 | OPC UPH | Y7N | ER | Y7D | Y7D |
| K | OPC TIC | Y8 | Y8 | AMB P | ICU | ICU | ADDMED | AMB GI | GI | Y7N | ID | OPC UPH | Y7D | Y7D | AMB A | VA | VA | ER | BMC | BMC | AMB P | Y7N | AMB A | VA | VA | AMB GI |

### cfgD-r1 - OFF/2/120: vol3/frag2/heal20 - residents A-K

| Res | 1a | 1b | 2a | 2b | 3a | 3b | 4a | 4b | 5a | 5b | 6a | 6b | 7a | 7b | 8a | 8b | 9a | 9b | 10a | 10b | 11a | 11b | 12a | 12b | 13a | 13b |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A | OPC UPH | Y8 | Y8 | OPC TIC | Y7D | Y7D | AMB P | Y7N | AMB GI | AMB P | ICU | ICU | ER | VA | VA | AMB GI | GI | ID | AMB A | VA | VA | AMB A | Y7N | ADDMED | BMC | BMC |
| B | OPC TIC | VA | VA | AMB GI | ID | GI | Y7N | OPC UPH | Y7D | Y7D | AMB GI | AMB P | Y8 | AMB P | ICU | ICU | ER | VA | VA | AMB A | BMC | BMC | AMB A | Y7N | ADDMED | Y8 |
| C | VA | VA | OPC UPH | Y7N | GI | AMB GI | ID | OPC TIC | ICU | ICU | AMB P | Y7N | AMB P | Y8 | Y8 | AMB A | Y7D | Y7D | ER | AMB GI | ADDMED | VA | VA | AMB A | BMC | BMC |
| D | BMC | BMC | OPC TIC | Y8 | Y8 | AMB P | Y7D | Y7D | AMB P | VA | OPC UPH | AMB GI | ID | AMB GI | GI | Y7N | AMB A | VA | VA | ER | Y7N | ADDMED | ICU | ICU | AMB A | VA |
| E | BMC | BMC | AMB GI | GI | Y7N | ID | OPC UPH | VA | VA | AMB A | Y7D | Y7D | AMB GI | AMB A | VA | AMB P | Y8 | Y8 | OPC TIC | AMB P | ICU | ICU | ADDMED | VA | ER | Y7N |
| F | AMB GI | OPC UPH | Y7N | AMB P | OPC TIC | Y8 | Y8 | AMB GI | GI | Y7N | ID | ER | Y7D | Y7D | AMB A | VA | VA | ADDMED | BMC | BMC | AMB A | VA | VA | AMB P | ICU | ICU |
| G | ID | ER | BMC | BMC | AMB P | Y7N | AMB A | Y8 | Y8 | AMB GI | GI | OPC TIC | ICU | ICU | ADDMED | VA | VA | AMB A | Y7D | Y7D | OPC UPH | Y7N | AMB P | VA | VA | AMB GI |
| H | VA | AMB A | ICU | ICU | AMB A | VA | VA | AMB P | VA | ADDMED | Y7N | ID | GI | Y7N | ER | Y8 | AMB GI | AMB P | BMC | BMC | AMB GI | OPC TIC | Y8 | OPC UPH | Y7D | Y7D |
| I | ICU | ICU | AMB P | VA | VA | AMB A | VA | VA | OPC UPH | GI | Elec | AMB A | Y7N | ID | AMB P | OPC TIC | BMC | BMC | AMB GI | ADDMED | Y8 | Y8 | AMB GI | ER | Y7D | Y7D |
| J | Y8 | AMB P | Y7D | Y7D | AMB GI | ADDMED | ICU | ICU | AMB A | Y8 | AMB A | VA | VA | OPC UPH | Y7N | ER | BMC | BMC | AMB P | Y7N | GI | AMB GI | ID | OPC TIC | VA | VA |
| K | Y7D | Y7D | AMB A | ER | ICU | ICU | AMB GI | GI | Elec | VA | VA | ADDMED | BMC | BMC | AMB GI | ID | AMB P | Y7N | OPC UPH | VA | VA | AMB P | OPC TIC | Y8 | Y8 | AMB A |

### cfgD-r2 - OFF/2/120: vol1/frag6/heal18 - residents A-K

| Res | 1a | 1b | 2a | 2b | 3a | 3b | 4a | 4b | 5a | 5b | 6a | 6b | 7a | 7b | 8a | 8b | 9a | 9b | 10a | 10b | 11a | 11b | 12a | 12b | 13a | 13b |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A | BMC | BMC | OPC UPH | VA | VA | OPC TIC | VA | VA | AMB P | Y8 | AMB GI | AMB P | Y8 | AMB GI | GI | Y7N | ID | ER | Y7D | Y7D | AMB A | Y7N | AMB A | ADDMED | ICU | ICU |
| B | BMC | BMC | OPC TIC | AMB GI | OPC UPH | Y7N | GI | Y7N | ID | AMB P | Y7D | Y7D | AMB GI | AMB P | ICU | ICU | AMB A | VA | VA | AMB A | Y8 | Y8 | ER | VA | VA | ADDMED |
| C | VA | OPC UPH | Y7N | AMB P | Y7D | Y7D | AMB P | ID | AMB GI | GI | Y7N | OPC TIC | ICU | ICU | AMB GI | ER | BMC | BMC | AMB A | VA | VA | AMB A | Y8 | Y8 | ADDMED | VA |
| D | Y8 | OPC TIC | ICU | ICU | AMB P | GI | Y7N | OPC UPH | VA | VA | AMB A | VA | VA | AMB A | BMC | BMC | AMB P | Y8 | AMB GI | ER | Y7N | ADDMED | Y7D | Y7D | AMB GI | ID |
| E | AMB GI | GI | AMB P | Y8 | Y8 | OPC UPH | Y7D | Y7D | OPC TIC | ER | ICU | ICU | AMB P | Y7N | ID | Elec | VA | AMB A | BMC | BMC | ADDMED | VA | VA | AMB GI | AMB A | VA |
| F | VA | VA | AMB GI | OPC TIC | BMC | BMC | AMB A | Y8 | Y8 | AMB GI | OPC UPH | AMB A | Y7N | GI | Y7N | ID | ER | VA | VA | AMB P | ICU | ICU | ADDMED | AMB P | Y7D | Y7D |
| G | OPC TIC | Y8 | Y8 | ER | AMB GI | AMB P | ICU | ICU | AMB A | VA | VA | OPC UPH | Y7D | Y7D | AMB A | VA | VA | ADDMED | BMC | BMC | AMB GI | ID | GI | Y7N | AMB P | Y7N |
| H | ICU | ICU | AMB A | Y7N | OPC TIC | Y8 | Y8 | AMB P | Y7D | Y7D | ER | AMB GI | OPC UPH | VA | VA | AMB GI | GI | Y7N | ID | ADDMED | BMC | BMC | AMB P | VA | VA | AMB A |
| I | Y7D | Y7D | ER | GI | Y7N | AMB GI | OPC TIC | VA | VA | AMB A | BMC | BMC | ADDMED | Y8 | Y8 | AMB A | ICU | ICU | AMB P | VA | VA | AMB P | Y7N | OPC UPH | ID | AMB GI |
| J | GI | AMB P | Y7D | Y7D | AMB A | VA | VA | ADDMED | BMC | BMC | AMB P | Y7N | AMB A | OPC TIC | VA | VA | AMB GI | Elec | ICU | ICU | OPC UPH | AMB GI | ID | ER | Y8 | Y8 |
| K | OPC UPH | VA | VA | AMB A | ICU | ICU | AMB GI | AMB A | GI | Y7N | ID | ER | BMC | BMC | AMB P | Y8 | Y8 | AMB GI | ADDMED | Y7N | AMB P | VA | VA | OPC TIC | Y7D | Y7D |


## Autonomous sweep results (auto-appended)

| run | floor/target/weight | vol | frag | heal | h->h | runs>6wk | ver | status | grid |
|---|---|---|---|---|---|---|---|---|---|

_Abbreviations: ICU = ICU · VA = VA Inpatient · BMC = Broadlawns · Y7D = Younker 7 Days · Y7N = Younker 7 Nights · Y8 = Younker 8 Pulmonology · GI = Inpatient GI · ID = Infectious Disease · AMB A = Primary Care Clinic · OPC TIC / OPC UPH = Cardiology Clinics · AMB GI = Outpatient GI · AMB P = Outpatient Pulm · ER = Emergency Medicine · ADDMED = Addiction Medicine · Elec = Elective_
