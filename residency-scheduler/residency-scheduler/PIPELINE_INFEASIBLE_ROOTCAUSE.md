# Root-cause: post-wipe pipeline Phase-0 proves INFEASIBLE

**Date:** 2026-06-26. Written after a long investigation so it isn't re-derived. The DB was wiped to a
clean slate (seeds + P2 + versions) on 2026-06-26; the full pipeline then could not generate ANY seed
because cold Phase-0 proves INFEASIBLE in 2–4s (presolve proof, 0 branches). The user believes the
model IS feasible (got feasible seeds even after the GI inpatient/outpatient fix).

## What was RULED OUT (do not re-investigate)
All compared byte-for-byte / confirmed identical between the working seed-era backups
(`pool.pre-fresh-20260624-150629` = 37 seeds, `freshstart-20260626` = 168 seeds) and the live DB:
- **Code** — cold solve is INFEASIBLE even at commit `9d9df30` (the exact commit that built the seeds), rebuilt clean. Not a code regression.
- **All 11 problem tables** the engine reads (residents, rotations, rotation_config, rotation_requirements, rotation_pgy_caps, rotation_link_rules, rotation_sequence_rules, blocks, prerequisites, aux_filler_rotations) — identical hashes.
- **schedule_config** — identical (the only diff was the 2 cache-blob keys `phase0_feasible_pool_2/_fp_2`; deleting them did NOT change the result).
- **OR-Tools version** — runtime loads **9.9.3963** (confirmed by loading the actual jar via `CpSolver.class.getProtectionDomain()`). The 9.10.4067 bump (tried for #5025) is reverted; `cp.txt` has zero 9.10 refs. NOT the cause.
- **fj-portfolio** — IS applied (probing off, polarity false, workers=16) in both old success and now. NOT the cause.
- **global workload tightness alone** — relaxing `global_min_workload` 52→50 (1–2 blocks slack) still INFEASIBLE. So it is NOT just "minimums sum too high."
- Determinism: 5/5 cold attempts INFEASIBLE — not random portfolio noise.

## ROOT CAUSE (the engine's own stepwise diagnosis isolates it)
Conflict appears at **Step 8 "Max blocks per resident"**, and the engine's factor-isolation prints:
```
INFEASIBLE after adding: Res K + Outpatient TIC Cardiology [min=1 max=1 slots]
  [workload=Y coverage=Y pairs=Y]: INFEASIBLE
  [workload=Y coverage=N pairs=Y]: FEASIBLE   <-- dropping per-block COVERAGE makes it feasible
  Culprit Res K: total [min=15 max=16 wks] ... workload cap [26,26]
```
It is **workload × coverage together**, driven by VA:
- **VA (rotation 1): min_per_week=2 per block × 26 blocks = 52 resident-block-slots of MANDATORY coverage.**
- Supply: 11 PGY-1 residents × VA min 4 slots = 44; their VA *max* is also 4 (maxBlocksAllowed=8wk→4 slots), so they cannot supply much more without … nothing left, because
- every resident is pinned to **exactly 26 total slots** (`global_min_workload = global_max_workload = 52wk = 26 slots`, zero slack), and the OTHER 15 required rotations already consume ~25 of those 26 (per-resident min-slot sum = 25).
- **aux residents do NOT help VA**: `aux_filler_rotations` only pre-covers **BMC (rotation 16)**, not VA. The 6 aux (4 TY + 2 BMC) contribute nothing to the 52 VA-slot floor.

So the per-block VA coverage floor (52) + exactly-26-per-resident + all other rotation minimums is
**over-determined → genuinely INFEASIBLE** under the current data. `[coverage=N]→FEASIBLE` proves the
coverage floor is the binding half of the conflict.

## WHY IT WORKED BEFORE (hypothesis, not yet proven)
The seeds (Jun 24) were found when the VA coverage burden was absorbable — most likely **aux/TY residents
or VA's 2/4 flexibility covered VA blocks** so the 11 categorical residents weren't forced to supply all
52. Something narrowed that: aux no longer fills VA (only BMC in `aux_filler_rotations`), or VA min_per_week
moved 1→2, or TY residents stopped counting toward VA coverage. The seed-era *inputs* look identical in the
tables checked, so the remaining suspect is **how aux/TY coverage is injected at model-build time** (code path
that reads aux residents / TY filler), OR the seeds were always found via warm-start/decomp that tolerated
the tightness that a cold presolve now rejects.

## NEXT STEP options
1. Prove the aux/VA hypothesis: check whether TY/aux residents are supposed to count toward VA's 2/block
   floor and whether the model injects them. If VA should be partly aux-covered, the data (aux_filler or
   VA min_per_week) is wrong.
2. Confirm the real-world intent: should every resident be pinned to EXACTLY 26 (min==max==52)? A 1-block
   of slack (min 50) plus correct VA coverage may be the intended, feasible model.
3. If the model is meant to be this tight, cold presolve can't seed it — must seed via warm-start/decomp
   (the path that built the original 168), which means restoring/keeping a seed pool rather than cold-genning.

## Bugs found along the way (independent of the above)
- FIXED: `pipeline_driver.py` Stage-1 `run_seed_gen` was missing `PHASE0_FIX=cache PHASE0_CACHE_COLLECT=1
  PHASE0_PORTFOLIO=fj` — without `PHASE0_FIX=cache` the engine NEVER banks a seed (gate at
  CpSatSchedulerEngine.java `p0fix.equals("cache")`), so even a feasible solve wouldn't grow the pool.
- OPEN: Stage-1 loop treats INFEASIBLE as success (parses a stale `seed_id=` line, increments) → spins
  forever on an infeasible model instead of halting. Needs an infeasible-detection + Halt.
- SKILL: `run-full-pipeline/SKILL.md` updated so harvest/top-k default to seed count and p3-budget=600.

## Data safety
Live DB intact, integrity ok, restored to the real config (`global_min_workload=52`). Backups present:
`residency_scheduler.db.bak_freshstart_20260626-021243` (168-seed pool, the original),
`...pre-cachekey-delete-test`, `...pre-workload-test`. The 168 seeds can be re-imported to unblock via
warm-start if desired.
