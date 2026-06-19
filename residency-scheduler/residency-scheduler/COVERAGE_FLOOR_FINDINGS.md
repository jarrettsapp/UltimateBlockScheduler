# Coverage Floor — Findings & the Zero-Volunteer Hard Floor

_2026-06-19. Why the solver plateaued at 2 volunteer Sundays, what the true floor is, and
the reversible fix._

## The question

The capacity-correct schedules (e.g. the in-app run saved as version `v4-app-run70`)
consistently produced **2 volunteer weekends** — Sundays where no categorical intern is
eligible for Younker-7 call, forcing the upper-level volunteer fallback. Repeated long
solves (up to 45 min) never beat 2. Was 2 the mathematical floor, or were we solving wrong?

Before sinking 10 hours into an overnight run, we proved the answer.

## Method (verified before trusting)

We built a dedicated CP-SAT model (`CpSatSchedulerEngine.proveCoverageFloor`,
runnable via `tools/CoverageFloorRunner`) that:
- keeps **every hard constraint** of a real solve (heavy-load requirements, rotation
  shapes, capacity caps, link rule, even-start, VA breaks, Y7-Nights split, …) by reusing
  the same `buildBaseModel`, and
- replaces all soft objectives with a single one: **minimize volunteer weekends**.

A volunteer weekend = a back-end weekend with zero eligible coverers. A categorical covers
weekend *b* iff it is non-heavy at slot *b* **and** non-heavy at slot *b+1* (the
entering-heavy pre-rotation rest lock). Since every non-heavy rotation is a Sunday source,
this is exactly the program's eligibility rule.

**We did not trust the result blindly.** The proof's solution was extracted and
independently re-scored in Python, confirming: 0 volunteers, zero capacity violations, and
the full heavy load intact (14 heavy slots/resident, VA = 4 slots, each categorical exactly
one Y7-Days block). It is a real, valid schedule — not a model that dodged the requirements.

## Findings

### The floor is **zero** volunteer weekends — proven OPTIMAL (in ~2 seconds).
A fully-loaded, capacity-correct schedule with **no volunteer weekends at all** exists.
So 2 was never the floor.

### Why the full solver never found it
The solver optimizes **lexicographically**: it locks in the Phase-1 clinical/transition
optimum *first*, then optimizes coverage only among schedules that already have minimal
transitions. The zero-volunteer schedules require accepting **some** transition cost — so
once Phase 1 freezes transitions, coverage can never reach zero. **It was the objective
*ordering*, not a lack of search time.** More hours would not have helped — which is exactly
why we proved the floor instead of running overnight.

### The transition tradeoff (the real cost)
Minimizing volunteers *alone* yields a transition disaster (47 heavy→heavy switches, six
10+ week runs) — but only because that prover ignored transitions. When we also minimize
transitions (volunteers hard-locked to 0), they collapse back to near-baseline:

| Metric | run #70 (current) | 0-vol, transition-blind | 0-vol + min-transitions* |
|---|---|---|---|
| Volunteer Sundays | 2 | **0** | **0** |
| Fragile weekends | 10 | 3 | 13 |
| Healthy weekends | 13/25 | 22/25 | 12/25 |
| Heavy→different-heavy | 2 | 47 | **4** |
| Runs > 6 weeks | 1 | 16 | 2 |
| Runs > 8 weeks (avoid) | 0 | 6 | 1 |

\* This combined run was FEASIBLE (not proven optimal) with a deliberately crude transition
objective (it penalized only heavy→heavy and 8-week windows — not fragile weekends or long
runs). So it is *a* valid 0-volunteer schedule, not the best one. The real engine's proper
transition objectives (the 6/8/10-week thresholds, inpatient-split penalties) should do
materially better.

**Bottom line:** zero volunteer weekends is achievable *with* low heavy→heavy transitions.
The remaining roughness is an objective-tuning matter for the real engine, not a fundamental
conflict.

## The fix — a reversible hard floor

A config flag **`enforce_zero_volunteer_weekends`** (default **OFF**) adds a hard constraint
(`ConstraintBuilder.applyZeroVolunteerFloor`) requiring every weekend to have ≥1 eligible
coverer. When ON, the solver must hit zero volunteers, then its existing transition
objectives optimize within that.

- **Toggle in the app:** the auto-schedule screen has a checkbox
  *"Require 0 volunteer weekends (hard)"* next to the phase-limit presets. It is saved to
  config on solve. Uncheck to revert — one click, fully reversible.
- **Proven feasible**, so turning it on will not make the model infeasible for this data.

**Recommended use:** turn it on, run a **Long** or **Overnight** preset (so the engine has
time to optimize transitions under the floor), save the result as a named version, and
compare it against `v4-app-run70`. If the transition cost is acceptable, it becomes the new
production schedule; if not, untick and re-solve.

## Reproducing the proof
```
mvn exec:java -Dexec.mainClass=com.residency.tools.CoverageFloorRunner -Dexec.args="2 0"
#   args: <year> <timeLimitSeconds(0=unlimited)> [trans]
#   add "trans" to hard-lock volunteers to 0 and minimize transitions instead.
```
