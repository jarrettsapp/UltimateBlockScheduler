# HANDOFF: evaluate moving the Phase-3 role (soft-objective optimization) to Timefold Solver

**Date:** 2026-06-25. **For a NEW session.** This sets up the discussion + evaluation of taking our
**seed (and possibly the Phase-2 result) and handing it to [Timefold Solver](https://timefold.ai)** to
do what CP-SAT Phase 3 was supposed to do — optimize the soft objective — which OR-Tools cannot do on
our seeded model (proven exhaustively; see `SINGLE_PHASE_DEADEND.md`).

**This doc does NOT decide anything.** It gathers the facts the next session needs so we can evaluate
the proposition together. Read `SINGLE_PHASE_DEADEND.md` and memories [[seed-determines-quality]],
[[phase3-seed-handoff]], [[harvest-phase-budgets-and-pin]] first.

---

## 1. Why we're here (the one-paragraph problem)

Schedule quality is **set by the Phase-0 seed** (ICC ~0.99); Phase 1/2 don't move the soft metrics
(they only drive the hard Tier-1/Tier-2 to 0). The soft metric we actually care about
(fragile/healthy/volunteer = weekend Sunday-coverage) is only optimized in **Phase 3**, which is
**blocked by OR-Tools bug #5025**: the multi-worker hint-repair path that could optimize from the seed
*crashes*, and everything that avoids the crash can't find/search the Tier-locked model. We cannot make
CP-SAT "start from the seed's feasible schedule and only move to improve the soft objective."

**Timefold is a natural fit for exactly that shape:** it is a local-search / metaheuristic solver that
**starts from a given (or constructed) solution and improves it against weighted constraints** — i.e.
"take this feasible schedule and make the soft score better without breaking the hard rules." That is
precisely the Phase-3 role.

## 2. THE core question for the new session: the handoff

> *How do we hand our problem to Timefold?* Two sub-questions:
> 1. **What do we hand it as the starting solution** — the raw seed, or the committed Phase-2 schedule?
> 2. **How do we express our constraints + objective as Timefold constraints** (its ConstraintProvider
>    / score DSL), and how do we get the result back into our DB?

### 2a. What the starting solution looks like (the data is READY)
Every harvest run already saves a complete schedule we can hand off:
- **Grid shape:** 11 categorical residents × 26 blocks (1 block = 2 weeks). Each (resident, block)
  cell = one rotation (of 16). A complete schedule = 286 categorical cells (saved versions store ~346
  rows incl. aux).
- **Source A — the seed:** `schedule_config['phase0_feasible_pool_2']`, `␞`-separated pooled
  assignments, each `occ_r<res>_s<rot>_b<block>=1; start_...`. Decode via `occ_*=1`. (See
  `compare_seed_vs_final.py` for working decode + the seedId replication.)
- **Source B — the Phase-2 result (RECOMMENDED start):** `schedule_version_assignments(version_id,
  resident_id, rotation_id, block_number)`. This is the **feasible, Tier-1=0 / Tier-2=0** schedule —
  a better Timefold starting point than the raw seed because the hard constraints are already
  satisfied, so Timefold begins in the feasible region and only optimizes soft.
- `solve_runs.version_id` links a run → its saved schedule; `solve_runs.seed_id` (8-char prefix) → the
  originating seed.

### 2b. The constraints + objective to reproduce in Timefold
From `ObjectiveFunctionBuilder` + `ConstraintBuilder` + live config (verified 2026-06-25):

**HARD (must hold — Timefold hard constraints):**
- All base feasibility (rotation requirements, eligibility, lengths, per-block capacity caps,
  ICU/VA/BMC caps, etc.) — these live in `ConstraintBuilder` (the base model). This is the BULK of the
  porting effort.
- **Tier-1:** inpatient → *different*-inpatient transition at a block boundary = forbidden. (Post-call
  terms exist but are dormant — trigger lists unset.)
- **Tier-2:** per-rotation per-block **undercoverage** (< minPerBlock, after aux credit) and
  **overcoverage** (> maxPerBlock) = forbidden.

**SOFT (minimize — Timefold soft score; current CP-SAT weights):**
- **Sunday coverage shortfall** (weight **95**) — THE metric. Per weekend (block boundary b→b+1):
  count residents who are on a Sunday-source rotation at b AND not entering a heavy rotation at b+1;
  penalize shortfall below target=2. 0 coverers (volunteer) = full penalty; 1 (fragile) = partial; ≥2
  (healthy) = none. Source/heavy rotation id lists are in config (`sunday_source_rotation_ids`,
  `heavy_rotation_ids`).
- **4+2 pattern** (weight **15**) — near-redundant nudge; user is OK dropping it. Low priority to port.
- **Categorical soft-cap excess** (weight **5**) — minor.

**Quality readouts (how WE score, for validation):** `score_grid()` in `score_and_snapshot.py` is the
single source of truth — fragile/healthy/volunteer/heavy_heavy + hm stretch. Use it to verify a
Timefold result scores as expected before trusting it.

## 3. Why Timefold's model fits (and the likely friction points)
- ✅ **Starts from a solution** — exactly what we need (CP-SAT's blocker was that it won't). Timefold's
  Construction Heuristic + Local Search is built to improve an initialized solution.
- ✅ **Soft/hard score levels** map cleanly to our Tier(hard)/Sunday(soft) split.
- ⚠️ **Porting the hard constraints is the real work.** Our feasibility model is substantial
  (ConstraintBuilder). Timefold needs each as a constraint stream. Estimate this honestly before
  committing — it's the "significant investment" the user flagged.
- ⚠️ **Planning-variable design:** likely one planning entity per (resident, block) with the rotation
  as the planning variable, value range = eligible rotations for that resident/block. Capacity/coverage
  become constraints over groupings.
- ⚠️ **Java interop:** Timefold is Java/Spring-friendly; this project is already Java (good). Decide
  whether it's a separate module that reads/writes the same SQLite, or in-process.
- ⚠️ **Round-trip:** Timefold result → `schedule_version_assignments` (new version) → `score_grid()`
  validation → into the harvest/solve_runs record like any other schedule, so it's comparable.

## 4. What to bring into the new session
- This doc + `SINGLE_PHASE_DEADEND.md` (so we don't re-try CP-SAT workarounds).
- The decode/score tooling already written: `compare_seed_vs_final.py` (seed decode + seedId),
  `score_and_snapshot.py::score_grid()` (the canonical metrics).
- The current pool: 168 distinct seeds; best known schedule seed `db9c7078` (fragile 5 / healthy 16 /
  volunteer 4 / heavy→heavy 0) — a concrete target Timefold should be able to MATCH from its own seed
  and ideally BEAT by reducing fragile via the Sunday-coverage objective.
- Open decision for that session: hand Timefold the **seed** or the **Phase-2 schedule** as the start
  (§2a leans Phase-2: already hard-feasible).

## 5. Decisions deferred to the new session (do NOT pre-decide)
- Is the constraint-porting investment worth it vs. just mass-screening seeds for a lucky low-fragile
  one? (We have the tooling to estimate seed-quality distribution cheaply if we want that comparison.)
- Full port of all hard constraints, or a hybrid (Timefold only re-arranges within CP-SAT-fixed
  feasibility)?
- Separate Timefold module vs. integrated phase.
