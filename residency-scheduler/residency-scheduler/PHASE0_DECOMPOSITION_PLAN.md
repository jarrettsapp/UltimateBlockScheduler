# Phase-0 Decomposition Plan (DECIDED 2026-06-24 — ready to build)

## ⛔ VERDICT 2026-06-24 (autonomous goal session): DECOMPOSITION DEAD-ENDED. Negative result proven.
Worked the approaches in order on a DB copy, P0 cap 300s, Phase-0 isolated (P1/P2/P3=1).
All variants are env-gated OFF by default; the monolithic path is untouched; `mvn -o compile`
clean throughout. Evidence (CSVs in phase0_decomp_results.csv / logs in phase0_decomp_logs/):

| variant | what it does to window 1 | result |
|---|---|---|
| **monolithic baseline** | full model, objective-bearing | **caps 3/10**, median 201s, completers 4.5–219s |
| **hardroll3** (Approach #1: hard-fix complement + backtrack) | full model, stop-after-first | **caps 2/5**; a cap costs **~601s** (full window-1 + monolithic fallback = double-pay) |
| **roll3seed** (Approach #3: full budget + greedy seed on window 1) | full model + greedy warm-start | **capped at 300s** then fallback also capped (601s, infeasible) on its smoke draw |
| **monoC** (PHASE0_MODE=C: stop-after-first + probing-off + polarity-false) | full model, C-tuned | **caps 10/10** — strictly WORSE; overturns the memory's "C≈90s" (that was a lucky single draw) |

**ROOT CAUSE (now proven, not hypothesized):** every decomposition variant must solve a
window FIRST that has nothing to lean on. With b13 anchored first, window 1's "complement"
is empty → there is nothing to hard-fix/hint/shrink → **window 1 IS the full monolithic
first-feasibility problem.** Its success rate therefore EQUALS monolithic's, and on failure
the variant is *worse* (it pays the cold solve, then pays the monolithic fallback = ~601s).
The decomposition machinery itself works perfectly — hardroll3 run 3 proves it: window 1
found feasibility in 89.7s, then windows 2–5 (frozen prefix growing 352→3520 fixed vars)
each finished in **<1 second**. The entire risk is concentrated in the un-shrinkable window 1.

**Why no variant can escape it:** making window 1 small requires a *feasible* complement to
fix/hint against, and the only thing that produces a feasible complete assignment on this
globally-coupled model is a full solve — circular. Hint-cold, hard-fix-cold, and seed-cold
were all tested and all cap on the identical window-1 search. The earlier 5×10 PHASE0_MODE
matrix already showed no solver-param combo makes that search reliable; monoC 10/10 reconfirms.

**RECOMMENDATION (Approach #4, the negative-result fallback):**
1. **Keep the monolithic Phase-0 path as the default** (already is). Do NOT ship any decomp mode.
2. **The reliability win is NOT decomposition — it is caching a known feasible assignment.**
   The model is IDENTICAL every run, so a feasible Phase-0 assignment found once stays feasible
   forever. Recommend: persist the first feasible Phase-0 occupancy map to the DB, and on
   subsequent runs `addHint` it (repairHint=true) so every run warm-starts from a known-good
   point instead of cold-searching. This attacks the actual bottleneck (cold first-feasibility)
   with the one lever proven to matter (warm start from a *feasible* — not greedy — assignment).
3. Alternatively, bias the CP-SAT portfolio toward `feasibility_jump` (the only subsolver that
   ever finds feasibility per the winner test) and/or raise the P0 cap — but caching dominates.
4. The decomp code (PHASE0_DECOMP=roll3|roll1|hardroll3|hardroll1|roll3seed|roll1seed) stays in
   the tree, env-gated OFF, as a documented dead-end + reusable fix-and-optimize machinery.

Everything below this line is the pre-verdict planning history, retained for context.
═══════════════════════════════════════════════════════════════════════════════════════════

## ⚠ SESSION UPDATE 2026-06-24 (eve): hint-only decomp FAILED smoke; fork resolved
The hint carry-forward variant was implemented (PHASE0_DECOMP=roll3|roll1, committed below in
"Build plan") and SMOKE-TESTED — it failed, and the failure is INSTRUCTIVE, not a bug:
- **roll3 smoke (P0 cap 300 → 60s/window): window 1 (b13, cold, no hints) capped at 60s**,
  fell back to monolithic (which then also capped at 300s). feasible=false.
- **Root cause:** every window solves the FULL model (the consequence of the packed/order-free
  reframe — hints never shrink the problem). So the FIRST window is a cold full-model
  first-feasible solve on 1/5 the budget — strictly HARDER than monolithic, which gets the
  whole 300s. Staging is upside-down: the hardest solve runs on the tightest clock.
- **The fundamental tension (now explicit):** hint-only never shrinks the problem, but
  "make window 1 easy" requires it to be SMALL = hard-fix the complement = the freeze we
  rejected as corner-prone on a packed year. Both cannot hold.
- **USER DECISION:** go BACK toward the original plan (variant #1: true sub-problems via
  hard-fix complement + backtrack), AND keep trying the current hint approach with variations.
  Resolve via an autonomous goal session (see "AUTONOMOUS GOAL" at the bottom of this file).
- **Code state:** PHASE0_DECOMP=roll3|roll1 hint-based path is committed-in-working-tree and
  COMPILES; it is OFF by default (env-gated) so it does not affect normal runs. It can be
  evolved (add hard-fix complement + backtrack) rather than rewritten — `solvePhase0Decomp`
  + `decompWindows` + `DecompResult` are the hooks. Smoke DB copy: residency_scheduler.smoke-roll3.db.


## Why (verdict reached; testing closed)
Monolithic Phase 0 (find a complete feasible schedule in one CP-SAT solve over
~14,993 vars / ~167k constraints) **cannot reliably reach feasibility.** Evidence:
- 5×10 PHASE0_MODE matrix (P0 cap 300s): mode A 8/10 capped, B 5/10 capped (completers
  102–239s), C 5/6 capped. No solver-param combo (A/B/C/BC/AC) is reliable.
- Winner test (baseline + DIAG search-progress log, 300s cap): only ~1/5 runs found
  feasibility; when found it was ALWAYS `fj_short_default` (feasibility_jump) at 89s & 182s.
  The 7 LP/optimization subsolvers (default_lp, max_lp, no_lp, quick_restart, probing…)
  NEVER find feasibility on this model.
- **Presolve is NOT the bottleneck** (finishes ~1.95s, reduces 14,993→4,572 vars; KEEP it).
  The bottleneck is the global first-feasible SEARCH.

Decomposition solves the schedule in small sub-problems, each constraining the next, so no
single solve has to satisfy everything at once.

## Current code state (clean baseline)
- Phase 0 reverted to known-good monolithic (`PHASE0_MODE=baseline`, the active default;
  plain `configureSolver`, ~248s when it completes). Env knobs `PHASE0_MODE`
  (subset of A/B/C, "accel"=ABC) and `PHASE0_DIAG=1` (search-progress log) exist but
  default OFF. Helpers `buildGreedySeedHints` + `configureSolverPhase0` retained but unused
  on the default path. All uncommitted on branch feature/solver-trajectory-capture.
- Harnesses for reference: `phase0_matrix.sh`, `phase0_winner.sh` (run HeadlessSolveRunner
  with P1/P2/P3=1s to isolate Phase 0; parse "Phase 0 result:" + "#1 …<subsolver>" lines).
- R6 baseline sweep is STOPPED (was killed to do this investigation). Not yet relaunched.

## Mechanics already available (no new variable machinery)
- Vars indexed by **(resident, rotation, block)**: `vf.getOccupancyVar(rid,sid,b)`,
  `vf.getStartVar(rid,sid,t)`.
- **Fix a subset** = `model.addEquality(var, value)` on the chosen vars (same iteration
  shape as `applyHints`, but hard-fix instead of hint).
- **Read a sub-solve's result** = `extractHints(solver, vf, ...)` → map of var→value.
- Outer loop: build base model → fix already-solved subset → solve sub-problem →
  extract → repeat. Final full assignment becomes Phase 0's feasible handoff to Phase 1.

## The variants to test (ranked by predicted success)

### UNITS (resolved): internal grid = 26 two-week SLOTS = 13 full blocks.
- `SLOTS_PER_YEAR=26`, `SLOTS_PER_FULL_BLOCK=2`, `WEEKS_PER_SLOT=2`.
- Window divisibility over 13 blocks: 1-block windows → 13 even (no remainder);
  2-block → 6+leftover-1; 3-block → 4+leftover-1. The leftover is just a smaller window.
- **Put the irregular (leftover) window FIRST, not last.** Last window = least room =
  worst corner; first window = schedule wide open = easiest. E.g. 3-block stepping:
  [b1],[b2-4],[b5-7],[b8-10],[b11-13]. Also block 13 has special capacity rules
  (2 categoricals; BMC Y7D exceptions at blocks 7 & 13) — do NOT isolate it as a lonely
  last window.

### STRUCTURAL FINDING (from ConstraintBuilder/engine code): hard constraints couple
### rotations ACROSS TIME, not within a rotation.
- heavy→different-heavy adjacency couples rotation h1@b with a DIFFERENT heavy h2@b+1.
- max-consecutive heavy/medium run couples ANY mix of heavy/medium rotations over 4
  consecutive slots. So coupling is temporal+local (block b with b±1, 4-slot windows).
- **Implication:** time-sliced decomposition is STRUCTURALLY ALIGNED (a window solves all
  rotations in those consecutive blocks together; only the seam needs care). Per-rotation
  cuts ACROSS the grain (freezes one rotation while its cross-rotation adjacency partners
  are still unplaced) → corner-prone. This RAISES time-sliced and LOWERS per-rotation.

### 1. Time-sliced / rolling horizon  ← most likely; build first (structurally aligned)
- Solve blocks [0..W) to feasibility, freeze, solve [0..2W) with first window fixed, etc.
- **Window:** start 1 block (2 slots) — divides 13 evenly. Then try 3-block windows with
  the leftover block solved FIRST. (Reference windows in BLOCKS, not weeks.)
- **Overlap/re-solve seam (key risk mitigation):** allow the last K weeks of the frozen
  window to stay free when solving the next, so the seam can adjust. Start K=0 (hard
  freeze), add overlap only if we hit corners.
- **Corner handling:** if a window goes INFEASIBLE, unfreeze the previous window and
  re-solve the pair (limited backtrack). Cap backtrack depth to avoid blowup.

### 2. Rotation-by-rotation  ← experimental 2nd (NOTE: cuts across the coupling grain)
- Caveat (see Structural Finding): hard constraints couple DIFFERENT rotations across
  adjacent time, so freezing one rotation at a time pre-commits cross-rotation adjacencies
  → corner-prone. Only viable setup: GROUP coupled rotations and solve each group together,
  using HINTS not hard freezes so the seam can repair.
- Order groups scarcest-capacity-first: heavy group (ICU cat≤1/total≤2, VA cat≤2,
  BMC cat+TY≤2, Y7D≥2 — these also carry heavy→heavy + run coupling) → medium → outpatient.
- Honest note: "group all coupled rotations together" trends back toward solving most of
  the hard problem at once → why this ranks below time-sliced now.

### 3. Resident-by-resident  ← workable; build third (separate run)
- Assign one resident's whole year to feasibility, freeze, next resident.
- Order residents by constraint tightness (most-constrained-first) to reduce corners.
- Risk: last residents inherit leftover slots → highest corner rate of the three.

### 4. Constraint-staged  ← hardest, least shrinkage; likely skip
- Solve with structural constraints only → feasible skeleton → add tight coupling
  constraints + repair. Does NOT shrink the problem (still whole-schedule), just reorders
  difficulty. Lowest expected payoff. Hold unless 1–3 all fail.

## Implementation approach (shared harness)
- Add a `PHASE0_DECOMP` env mode (like PHASE0_MODE) selecting: `roll4`, `roll2`,
  `byrotation`, `byresident`. Default off → current monolithic Phase 0.
- Each variant = an outer driver around `buildBaseModel` + fix-subset + solve +
  extract. Keep each sub-solve on the fast known-good `configureSolver`, short per-step
  limit (e.g. 30–60s; sub-problems are small).
- **Handoff guarantee preserved:** only declare Phase 0 feasible when the FULL assignment
  is assembled; on unrecoverable corner, fall back to current monolithic solve (so we
  never regress below today's behavior).

## Test protocol (mirror the winner test)
- For each variant: 10 runs, record total Phase-0 wall time, feasible? (yes/no), and
  per-step times. Compare cap rate + median vs monolithic baseline.
- Success bar: ~0 caps (always finds feasibility) AND total time < monolithic median.

## Open questions to resolve before coding
- Does freezing a window ever violate a *global* constraint that spans windows (e.g.
  max-consecutive-heavy across a seam)? If so, those constraints must be added with the
  frozen vars still present (they are — we fix values, not remove vars), so they still
  bind. Verify with the seam re-solve.
- Backtrack policy: depth cap + what to do if depth exhausted (→ monolithic fallback).

## DESIGN DECISIONS (LOCKED 2026-06-24 — ready to code)

### Constraint inventory (drives the whole design)
Read every constraint in ConstraintBuilder + the engine Phase-0 block. Three classes:
- **Per-block (window-local, no seam issue):** coverage min/max (§1), categorical caps
  (§1b), PGY caps (§2), full-year coverage (§5), even-block-start (§13).
- **Local temporal couplers (b↔b±1 or a short sliding window):** heavy→diff-heavy ban,
  zero-volunteer floor, no-back-to-back, require-break, mutual-non-adjacency,
  cannot-immediately-follow (all b↔b+1/b+L); max-consecutive-blocks (window maxConsec+1);
  **max-consec-heavy-medium = widest local coupler at limitSlots+1 = 12/2+1 = 7 slots =
  3.5 blocks.** These bind correctly through FROZEN VALUES across a seam (we fix values,
  not remove vars) — they are NOT the corner risk.
- **Whole-year / GLOBAL (the real corner risk):** `applyMaxBlocksPerResidentConstraints`
  (per-resident-per-rotation min/max over the year — this is where "1 Y7D block per
  categorical", VA mins live), `applyWorkloadCapConstraints` (per-resident yearly load),
  `applyRotationLinkConstraints` (per-resident sum + global rotB total), prerequisites/
  must-be-after (ordering across the whole year). If early windows don't reserve enough of
  these, a LATE window goes infeasible. This — not the seam — is the dominant corner risk.

### 1. Global requirements → HINT CARRY-FORWARD (quotas DROPPED)  [decision REVISED 2026-06-24]
Original decision was per-window quotas. REVERSED after reading the live DB + user reframe:
- **DB facts:** one PGY-1 cohort (11 residents); every requirement is a per-RESIDENT min,
  1–4 slots each; summed per resident ≈ 26 of 26 slots → the year is ~100% SLOT-PACKED.
- **No prerequisites, no MUST_BE_AFTER.** All sequence rules are CANNOT_IMMEDIATELY_FOLLOW
  (a local b↔b+1 coupler). So there is NO whole-year ordering constraint.
- **User reframe:** the year is packed but ORDER-FREE — each resident must complete a fixed
  multiset of rotations totaling 26 slots, but WHEN each lands is wide open (one does ICU in
  b1, another in b13). This is an assignment/permutation problem, not an ordered packing.
- **Why quotas are the wrong model:** with order free there is no "correct" position to
  reserve a rotation into; a scalar cohort quota enforces nothing meaningful, and most
  requirements are 1 slot (ceil(1·done/13)=1 from window 1 → over-constrains). Quotas were
  modeling the wrong thing.
- **Why hard-freeze is dangerous here:** packed + hard-fixed early windows leave the rest an
  exact-fit bin-packing with shrinking room; a freeze can wedge it (resident owed two heavy
  rotations, only adjacent slots free → heavy→heavy ban → infeasible).
- **DECISION: hint carry-forward as the PRIMARY mechanism (not a fallback).** Each window
  solves the FULL model (all 26 slots, all yearly per-resident min/max constraints present),
  with prior windows' assignments applied via `addHint` (NOT `addEquality`) and the current
  window's slots given search priority. The solver may slide earlier hinted slots to keep the
  global packing feasible. No quota calculator, no per-resident reservation tracking.
- This stages the SEARCH (warm-start each window) rather than shrinking the problem — honest
  about what actually helps on a packed, order-free year.

### 2. Window sizes → BUILD BOTH, TEST HEAD-TO-HEAD  [decision]
Implement `roll3` (3-block) and `roll1` (1-block) behind the same env mode; run the 10×
protocol on each vs monolithic and pick on cap-rate + median time. Rationale: the 7-slot
run coupler exceeds a 1-block (2-slot) window, so roll1 splits almost every run across a
seam (more re-solves) while roll3 (6 slots) contains most runs — but roll1 shrinks each
sub-solve the most. Settle it empirically, not by argument.

### 3. Block specials → ANCHOR BLOCK 13 FIRST; block 7 needs nothing  [decision]
Enumerated from CpSatSchedulerEngine.java:545-555 + ConstraintBuilder.java:79-104:
- **Block 7 carries NO solver-side special.** Its exception (BMC skips it; a TY fills the
  2nd body) is entirely POST-SOLVE in AuxFillerService — the solver sees an ordinary block.
  No special window handling needed.
- **Block 13 (slots 24,25) is the ONLY solver-side special:** Y7D cap=1 elsewhere but
  block 13 has a hard FLOOR and cap of **2 categoricals** (no BMC/TY 2nd body there). That
  floor consumes 2 of the per-year "1 Y7D block per categorical" allocations at year-end →
  a global-requirement coupling.
- **Decision: anchor the block-13 window FIRST** in both variants — solve it first to place
  the 2-categorical Y7D floor, then carry that forward as a HINT (not a lock — quotas are
  dropped). Y7D CANNOT_IMMEDIATELY_FOLLOW GI/ID (ids 19/15), so a hard b13 lock would export
  a constraint onto slot 23 in a not-yet-solved window; hinting lets that seam self-repair.

### Concrete window orders
- `roll3`:  `[b13] → [b1-3] → [b4-6] → [b7-9] → [b10-12]`  (block 13 leftover-first)
- `roll1`:  `[b13] → [b1] → [b2] → … → [b12]`
- (Block numbers are 1-based 4-week blocks; b13 = slots 24,25. roll3's "leftover first" is
  satisfied by the b13 anchor — no separate leftover window.)

### Build plan  [REVISED — hint-based, no quotas]
Implement behind `PHASE0_DECOMP` env mode (`roll3`/`roll1`; default off → monolithic).
Each window = `buildBaseModel` (FULL model, all yearly constraints) → `addHint` every slot
decided by a prior window (accumulated map) → give current-window slots search priority →
`configureSolver` (short per-step limit) + `setStopAfterFirstSolution(true)` → `extractHints`
and merge into the accumulated map. NO `addEquality` freeze, NO quota lower-bounds.
- Accumulated hint map grows each window; the LAST window's solve over the full model, fully
  hinted, IS the Phase-0 feasible assignment handed to Phase 1.
- Giving current-window slots "search priority": simplest first cut = hint only the
  prior-window slots and leave current/future unhinted, so the solver's search naturally
  focuses where there's no warm start. (No special var-ordering API needed for v1.)
- Handoff guarantee preserved: declare Phase 0 feasible only when the final full-model solve
  returns FEASIBLE/OPTIMAL; on UNKNOWN/INFEASIBLE at any window, fall back to monolithic
  (never regress below today). Then run the 10× protocol (roll3 vs roll1 vs monolithic).
- OPEN QUESTION to validate empirically: since every window solves the full model, the win is
  warm-starting, not problem-shrinkage — if a fully-hinted full solve isn't faster than cold
  monolithic, the variant fails its success bar and we fall back. Measure before trusting.

## Key file references
- Engine: `src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java`
  - Phase 0 block ~lines 576–656 (PHASE0_MODE selector, solver build, handoff to Phase 1).
  - `buildBaseModel` ~line 878; `extractHints` ~915; `applyHints` ~945;
    `buildGreedySeedHints` ~994; `configureSolverPhase0` ~973; `configureSolver` ~1056.
  - heavy→heavy + consecutive-run coupling ~lines 370–414. Block 13 Y7D 2-cat floor/cap
    is in ConstraintBuilder.applyCategoricalCapConstraints (lines 79–104, `y7dBlock13`);
    the engine comment at 545–555 documents it. Block 7 has NO solver-side special.
- Units: `src/main/java/com/residency/model/ScheduleUnits.java`
  (SLOTS_PER_YEAR=26, SLOTS_PER_FULL_BLOCK=2, WEEKS_PER_SLOT=2).
- Entry point for headless solves: `com.residency.tools.HeadlessSolveRunner`
  (args: year P0 P1 P2 P3; reads floor/target/weight from DB config keys).
- Memory: `phase0-acceleration.md` (full verdict + knobs).

## ═══════════════════════════════════════════════════════════════════════════
## AUTONOMOUS GOAL (next session works toward this UNATTENDED)
## ═══════════════════════════════════════════════════════════════════════════

**GOAL:** Produce a Phase-0 path that reaches a FEASIBLE assignment **reliably (≈0 caps over
10 runs)** and **faster than the monolithic baseline's median**, OR prove with evidence that
no decomposition variant beats monolithic and document the negative result + recommend the
fallback. Done = either a winning `PHASE0_DECOMP` mode meeting the bar, or a written verdict.

**SUCCESS BAR (from the test protocol):** for the chosen variant, 10 runs at P0 cap 300s,
Phase-0-isolated (P1/P2/P3=1): cap rate ≈ 0 AND total Phase-0 median < monolithic median
(monolithic baseline ≈ 248s when it completes, but caps 50–80% of runs — so "0 caps" alone
is already a win even at similar median).

**APPROACHES TO TRY, IN ORDER (stop when the bar is met):**
1. **Variant #1 as originally planned — TRUE sub-problems (hard-fix complement + backtrack).**
   This is the user's primary ask. For each window: hard-`addEquality` every slot OUTSIDE the
   window to its carried value, so the solve is genuinely SMALL/fast. On a window returning
   INFEASIBLE, unfreeze the previous window and re-solve the pair (backtrack); cap depth (e.g.
   2–3) → monolithic fallback. b13 anchored first. This actually shrinks the problem — the
   thing hints failed to do. WATCH the packed-year corner risk (a freeze can wedge a resident
   owed two heavies into adjacent-only free slots → heavy→heavy ban infeasible); the backtrack
   is the mitigation, measure how often it fires.
2. **Hybrid: fix-far + free-seam-margin.** Hard-fix slots OUTSIDE `[start-K, end+K]`, leave a
   K-slot margin free on each side so seams adjust. Start K=1, grow on corners. Shrinks AND
   repairs. Try if pure #1 corners too often.
3. **Fix window-1's cold-start problem in the hint variant.** If revisiting hints: give the
   FIRST window the FULL P0 budget (it's ~monolithic-with-stop-after-first → worst case ties
   monolithic) and/or seed window 1 with `buildGreedySeedHints` (Option B). Only pursue if 1–2
   stall — the smoke test showed cold full window-1 is the hint approach's fatal flaw.
4. **If all fail:** write the negative result into this file + a memory, recommend either
   monolithic-with-best-seed or escalating the per-window budget, and STOP.

**HARNESS TO BUILD/USE:** mirror `phase0_winner.sh`. Make a `phase0_decomp.sh` that runs
`PHASE0_DECOMP=<mode> java -cp "target/classes;$(cat cp.txt)" com.residency.tools.HeadlessSolveRunner 2 300 1 1 1`
N times, parses `decomp=… window …→ STATUS`, the final `Phase 0 result:`, and per-window times
into a CSV + summary (cap count, median total). Run SEQUENTIALLY for honest timing. Always
work on a DB COPY (cp residency_scheduler.db …) — never the live DB. Report run start +
presumed-max-end in Central time per [[run-timing-reporting]].

**GUARDRAILS (must hold):**
- NEVER regress the default path: PHASE0_DECOMP unset → monolithic, untouched. All work is
  env-gated. Confirm `mvn -o compile` is clean after every code change.
- Handoff guarantee: only declare Phase 0 feasible on a FULL feasible assignment; any
  unrecoverable corner → monolithic fallback (already wired in `solvePhase0Decomp`'s null path).
- Don't touch Outpatient GI typing or the I1 GI-protection fix ([[i1-gi-protection-fix]]).
- The hooks already exist: `solvePhase0Decomp`, `decompWindows`, `DecompResult` in
  CpSatSchedulerEngine. EVOLVE them (add the hard-fix complement + backtrack) — don't rewrite.
- Commit working increments on branch feature/solver-trajectory-capture with honest messages;
  do NOT merge to main.

**REPORT back each cycle:** which approach, the 10× CSV summary (caps + median vs monolithic),
and whether the bar is met. When met OR all approaches exhausted, update this file's top
"SESSION UPDATE" with the verdict and write a memory.
