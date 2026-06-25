# DEAD-END LOG: single-phase / unified-objective attempts to optimize the soft (Sunday-coverage) metric on a seeded model

**Date:** 2026-06-25. **Purpose:** record EVERY configuration tried this session so a future session
does NOT re-walk them. The short version: **OR-Tools bug #5025 blocks directed soft-objective
optimization on the seeded, Tier-constrained model in every configuration we can express.** This is
why we are evaluating Timefold (see `TIMEFOLD_HANDOFF.md`).

---

## 0. Context — what we were trying to do and why

Established earlier this session (see memories [[seed-determines-quality]], [[harvest-phase-budgets-and-pin]]):
- **The Phase-0 seed determines schedule quality.** 99-run study: ICC fragile **0.978**, healthy
  **0.992**, volunteer **0.931**. Repeating a seed gives the same schedule.
- **Phase 1/2 do NOT improve the soft metrics.** seed-vs-final (`compare_seed_vs_final.py`, 99 runs):
  only ~8% of cells move, mean Δquality ≈ 0. P1/P2 drive **Tier-1 and Tier-2 to 0** (their job) but
  those are orthogonal to fragile/healthy/volunteer.
- **The soft metric (fragile/healthy/volunteer) = the Phase-3 Sunday-coverage objective**
  (`buildSundayCoverageObjective`, weight 95). It is the ONLY place weekend-coverage quality is
  optimized — and Phase 3 is skipped by default because it crashes. So with P3 off, **the seed is the
  quality ceiling.**

Goal of these attempts: get a solver to **hold Tier-1=0 / Tier-2=0 (hard, non-negotiable) while
minimizing the soft objective** — i.e. actually optimize weekend coverage instead of leaving it to
seed luck. The user's idea: collapse the staged P0→P1→P2→P3 into ONE phase straight off the seed.

## 1. What Tier-1 / Tier-2 actually are (the non-negotiables)

Confirmed from `ObjectiveFunctionBuilder` + live config:
- **Tier-1 (clinical):** only the **inpatient→different-inpatient** transition rule is active (weight
  15). Post-call primary/secondary terms exist but are **dormant** (trigger rotation lists unset).
- **Tier-2 (coverage):** **undercoverage** (weight 125) + **overcoverage** (weight 60). Variance (γ)
  and PGY-imbalance (δ) are **off** (weight 0).
- Both hit **0** in every harvest run → genuinely satisfiable; the seed already respects them.
- Soft Phase-3 objective = `4+2 pattern (w15) + sunday_shortfall (w95) + categorical_soft_excess (w5)`.
  `max_consec_heavy_medium` is currently **soft+unset** (not active). 4+2 is a near-wash (user's read:
  redundant with the hard rules; fine to drop later).

## 2. Attempts (ALL on seed db9c7078, the best seed; SINGLE_PHASE builds one model with
   tier1==0 AND tier2==0 as HARD constraints + minimizes the soft objective)

| # | Config (env) | Result | Why it fails |
|---|---|---|---|
| 1 | `SINGLE_PHASE=1`, multi-worker (10), repair_hint ON (default) | **CRASH** `Check failed: heuristics.fixed_search != nullptr` ~8s | #5025: a parallel `fs_random` first-solution worker derefs an unbuilt fixed_search on the carried hint. **Complete+feasible hint does NOT avoid it** (disproved my hypothesis that only partial hints trigger it). |
| 2 | `SINGLE_PHASE=1`, `PHASE3_WORKERS=1` | **UNKNOWN, 0 incumbents** (full budget burned) | 1 worker has no parallel first-solution search → can't find a feasible point for the Tier-locked model, even though one EXISTS (the seed). The hint is not adopted as an incumbent. |
| 3 | `SINGLE_PHASE=1 SINGLE_PHASE_2STAGE=1` — Stage A pins hint (1w, fixVarsToHint) → gets incumbent; Stage B re-hints from it, repair_hint **OFF**, multi-worker | Stage A OPTIMAL ✅; Stage B **UNKNOWN, 0 incumbents** | No crash (repair_hint off). But a CP-SAT *hint* is a suggestion, not a forced incumbent; Stage B re-searches the Tier-locked model from cold and can't find feasibility. **Key learning: you cannot make CP-SAT "start from this known solution" via hints.** |
| 4 | `SINGLE_PHASE=1 PHASE3_IGNORE_SUBSOLVERS=fs_random`, multi-worker, repair_hint ON | **MODEL_INVALID** ~9s | Portfolio validator rejects dropping fs_random (matches the original investigation's `ignoreSubsolvers` note). Can't surgically remove just the crashing worker. |
| 5 | `SINGLE_PHASE=1 PHASE3_FIX_TO_HINT=on PHASE3_HINT_FRACTION=0.90` (pin ~90%) | **instant false-OPTIMAL** (obj 1369 @ 9s, best_bound==obj, 1 traj pt) | Pinning freezes the schedule so hard the solver just re-confirms the seed and declares OPTIMAL without searching. Not optimization — the seed's schedule, scored. (HINT_FRACTION≥~0.9 rounds to keep-all via `keepEvery=round(1/f)`.) |

**This independently reproduces the SAME dead-end map the original staged-Phase-3 investigation
produced** (see PHASE3_HARVEST_HANDOFF.md / [[phase3-seed-handoff]]) — proving the wall is the
OR-Tools bug, NOT the staged design. The vise:
- **multi-worker** (the only thing that FINDS a feasible start for the Tier-locked model) → **crashes**.
- **anything that avoids the crash** (1 worker / repair off / ignore fs_random / heavy pin) → **can't
  find or can't search** (UNKNOWN, MODEL_INVALID, or frozen false-OPTIMAL).
- Partial fix-to-hint at a middle fraction CAN optimize but is the documented **seed-unreliable
  coin-flip** — not pursued further here (already mapped).

## 3. Verified non-fixes (don't retry — from THIS session + the prior investigation)
- ✗ complete+feasible hint (instead of partial) — still crashes (#1).
- ✗ repair_hint OFF + multi-worker + complete hint — UNKNOWN (#3 stage B).
- ✗ 1 worker — UNKNOWN (#2).
- ✗ two-stage pin-then-release — hint not honored as incumbent (#3).
- ✗ ignoreSubsolvers(fs_random) — MODEL_INVALID (#4).
- ✗ heavy partial-pin — instant false-OPTIMAL (#5).
- ✗ (prior) disable shared_tree; fewer workers (8); raise hint_conflict_limit; useFeasibilityJump=false
  / numViolationLs=0 — see PHASE3_HARVEST_HANDOFF.md §1.
- ✗ OR-Tools bump — #5025 NOT fixed upstream as of 9.15.

## 4. State of the code (all env-gated, default OFF — default solver path VERIFIED unchanged)
Kept (per user) for possible reuse; documented here as dead-ends so they aren't mistaken for working:
- `SINGLE_PHASE=1` — collapse to one phase, tier1==0/tier2==0 hard + soft objective. (engine ~L1013)
- `SINGLE_PHASE_2STAGE=1` — the pin→re-hint→optimize two-stage. (engine ~L1151)
- `PHASE3_IGNORE_SUBSOLVERS=a,b` — drop named subsolvers. (engine ~L1119)
- (existing) `PHASE3_FIX_TO_HINT`, `PHASE3_HINT_FRACTION`, `PHASE3_WORKERS`, `PHASE3_SKIP`.
**Confirmed:** with NO experimental env vars set, a normal run is Phase-2-only ("Phase 3 SKIPPED
(PHASE3_SKIP default on)") exactly as committed — every experimental branch is inert when unset.

## 5. The takeaway that motivates the next session
We cannot, with OR-Tools, make the solver "start from the seed's feasible schedule and only move to
improve the soft objective." The bug sits exactly on the one mechanism (multi-worker hint repair) that
would enable it. **A different solver that natively does local-search FROM a given starting solution
is the natural escape — hence the Timefold evaluation in `TIMEFOLD_HANDOFF.md`.**
