# Cross-Project Review — Block Schedule ↔ Call Schedule (Younker 7 weekend nights)

_Date: 2026-06-18_

This is an **output-vs-output** review (not an import/export pipeline review). The
two applications are maintained separately: the block schedule that drives call is
**built by hand inside the call app** (Scheduler 5.0). The question here is
conceptual — *do the rules that govern Younker 7 Saturday/Sunday night call fit
well with the kind of block schedule we produce, such that the call scheduler
never needs the volunteer fallback?*

**Sources:** the block app's computed Year-2 schedule (`residency_scheduler.db`);
the call app's live config and hand-built block schedule
(`Desktop/Scheduler 5.0/6.17.26.db`); the call solver
(`CallScheduleConstraintProvider`) and `BlockScheduleEngine.computeAutoAssignments`.

---

## How the two outputs connect (the auto-call mechanism)

A PGY-1 on a **call-generating rotation** for a half-block produces a HARD,
mandatory Y7 night assignment in the call solver. The call app defines call
eligibility through three auto-call rules (`rotation_auto_call_rules`), all PGY-1,
`applies_to = BOTH` (so both weekends of the half-block):

| Call rotation | Block-app equivalent | Generates |
|---|---|---|
| Inpatient GI | Inpatient GI | **Y7 Sunday** night (1st choice) |
| ID | **Infectious Disease** | **Y7 Sunday** night (backup) |
| Younker 8 (Pulm) | Younker 8 Pulmonology | **Y7 Saturday** night |

These auto-call assignments **override** the call solver's forbidden-weekend and
eligibility constraints (they are mandatory). Auto-call is **PGY-1 only** — a
senior on the same rotation does *not* generate the intern auto-call.

### Real-world coverage rules (owner-confirmed)
- **Saturday Y7 night** ← the Y8Pulm intern, *always*, except when the half-block
  leads into an inpatient rotation (e.g. ICU) that carries a forced rest weekend
  the Sat+Sun before it starts.
- **Sunday Y7 night** = priority chain: (1) Inpatient GI intern → (2) Infectious
  Disease intern → (3) any intern on outpatient/elective → (4) **volunteer**
  (upper-levels, last resort).
- **Goal:** the block schedule should make the volunteer system **unnecessary** —
  every weekend has a feasible intern for both Sat and Sun, no conflict.

---

## Weekend eligibility model (the correct way to score coverage)

**A half-block runs Monday → second Sunday; the trailing Sat+Sun belongs to that
rotation.** Coverage must therefore be scored at the *weekend* level with
rest-eligibility — **not** by whether a body is merely present in the block. A
categorical intern can cover a given back-end weekend **only if**:

1. they are **not on a heavy rotation** that half-block (on a heavy rotation = call-
   ineligible for its entire duration), **and**
2. they are **not entering a heavy rotation** the next half-block (a manually-imposed
   pre-rotation lock — the weekend before a heavy Monday start is blanked so the intern
   doesn't burn a call shift and then miss day one).

Heavy set for both locks: **ICU, VA, Broadlawns, Younker 7 Days, Younker 7 Nights
(NM-IMMC), Younker 8 Pulmonology.** Note NM-IMMC (Y7 Nights) is Monday–Friday nights
only and does **not** cover Saturday/Sunday. Saturday is a rotation duty of the **Pulm
intern** (the one exception who works despite being "heavy"), except the Broadlawns-
resident-when-on-Pulm, who is excused.

> ⚠️ An earlier version of this document reported "0 volunteer weeks" for both
> schedules. That was an artifact of a naive *block-level presence* check and has been
> corrected below using the eligibility model above.

## Findings

### Sunday Y7 night — real schedule has 4 volunteer weekends; the app cuts it to 1
Scoring each back-end weekend with the eligibility model (categoricals only, for an
apples-to-apples comparison against the 11-resident real schedule):

| | Real (in-use) schedule | Block app (Year-2) |
|---|---|---|
| Covered by GI (1st choice) | 7 | 4 |
| Covered by ID (backup) | 4 | 9 |
| Covered by outpatient/elective fallback | 11 | 12 |
| **No eligible intern (→ volunteer)** | **4** (blk 1, 13, 15, 19) | **1** (blk 24) |

The four real-schedule failures are **total wipeouts**: at blocks 1, 13, 15, 19 **all
11 categoricals are simultaneously rest-locked** — every one is either on a heavy
rotation or entering one the following Monday. This is precisely the structural failure
the block app exists to eliminate.

### A soft Sunday-coverage objective drives volunteer weekends to zero
The app's first cut (no coverage objective) already cut volunteer weekends 4 → 1
(only block 24 remained). Adding a **soft Tier-3 Sunday-coverage objective** —
rewarding *surplus* eligible coverers per weekend (target ≥ 2, not merely ≥ 1) so the
downstream call scheduler can balance per-resident call-shift counts — drives it to
**zero** with no clinical or transition cost:

| Metric (eligibility-based) | Real (in-use) | App v1 (no obj) | **App v2 (obj)** |
|---|---|---|---|
| Volunteer (0-coverer) Sundays | 4 | 1 | **0** |
| Single-coverer weekends | — | 14 | **8** |
| Weekends with ≥ 2 coverers | — | 11 | **17 / 25** |
| Direct heavy→different-heavy | 5 | 0 | **0** |
| Heavy+medium runs > 6 weeks | 0 | 0 | **0** |
| Six-week runs | 1 | 4 | **3** |
| Saturday no-Pulm (floor) | 4 | 4 | 4 |

The objective lives in Phase 3, run with Tier-1 (clinical) and Tier-2 (coverage/
variance) locked ≤ best — so it can only break ties in the already-optimal frontier and
**cannot degrade clinical or coverage quality** (verified: the v2 solve holds Tier-1 = 0,
Tier-2 = 0). It is gated on config keys (`weight_sunday_coverage`, `sunday_coverage_target`,
`heavy_rotation_ids`, `sunday_source_rotation_ids`) and disables itself when the weight is
0. Tier source is the authoritative workload-tier list, **not** `rotation_type`.

### ✅ Saturday Y7 night — covered to the mathematical maximum (working as designed)
Saturday call depends on a **PGY-1 being on Younker 8 Pulmonology**. In a few
half-blocks only a **senior** is on Y8Pulm, so no intern auto-call is generated
and those Saturdays go to the upper-level/volunteer pool. **This is unavoidable by
arithmetic, not a scheduling defect** (owner-confirmed):

> 11 interns each complete 4 weeks (2 half-blocks) of Y8Pulm = **22** intern
> half-block-assignments, but the year has **26** half-blocks. So **exactly 4
> half-blocks must have no intern on Y8Pulm**, by counting alone.

The block app's Year-2 schedule hits this floor **optimally**:

| | Block app (Year-2) | Theoretical best |
|---|---|---|
| Intern half-blocks on Y8Pulm | **22 / 26** | 22 (max possible) |
| Half-blocks with no intern | 4 | 4 (forced) |
| Per-intern distribution | **2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2** | perfectly even |

All 11 interns do exactly their 2 half-blocks — coverage is maximized *and*
perfectly equitable. The call app's hand-built schedule lands the same way (8
weekend-weeks = 4 half-blocks). The 4 remaining half-blocks are precisely what the
**upper-level / volunteer system is designed to absorb**: per the program, interns
are first to fill Y8Pulm (keeping their shift workload equal), upper levels second,
and the residual is expected. No block-side change is needed or possible here.

---

## Summary: the app improves call coverage without sacrificing transition quality

| Call requirement | Real (in-use) | Block app (Year-2) |
|---|---|---|
| Sunday Y7 volunteer-fallback weekends | 4 (blk 1,13,15,19) | **1 (blk 24)** ✅ −75% |
| Saturday no-intern-on-Pulm half-blocks | 4 (arithmetic floor) | 4 (arithmetic floor) — tie |
| Equitable intern Y8Pulm load | 2 each | 2 each — tie |
| Direct heavy→different-heavy switches | 5 | **0** ✅ |
| Heavy+medium runs > 6 weeks | 0 | 0 — tie |
| Six-week heavy+medium runs | 1 | 4 (all within the 6-wk preferred max) |

The app's net effect: it buys a **3-weekend reduction in volunteer-dependent Sunday
call** and **eliminates all 5 awkward heavy→heavy transitions**, paying only three
additional six-week runs — none of which exceeds the owner's 6-week preferred maximum,
so the trade stays inside stated tolerance. The Saturday floor (4 forced no-intern
half-blocks; 11 interns × 2 = 22 of 26) is unbeatable by either schedule and is what
the upper-level/volunteer pool is designed to absorb.

---

## Notes (no action required)

- **Naming parity.** Block "Infectious Disease" = call "ID"; block "Younker 8
  Pulmonology" = call "Younker 8 (Pulm)". Since the schedule is hand-rebuilt in the
  call app, names only need to stay mentally aligned. The **rotation-type
  disagreement** (block types ID / Inpatient GI as OUTPATIENT, call as INPATIENT)
  does not affect call generation — on the block side, type only touches soft
  objectives.
- **Protect the Sunday chain.** Any future change to GI/ID placement should re-run
  this coverage check to keep Sunday at zero volunteer weeks.

---

## Transition quality (re-solved schedule, corrected objective)

After re-solving with the corrected objective (Tier-1 = 0, Tier-2 = 0, OPTIMAL),
transition quality was measured against the program's workload tiers and the
owner's consecutive-load thresholds.

**Workload tiers (owner-confirmed):**
- **Heavy (6):** ICU, VA, Broadlawns, Younker 7 Days, Younker 7 Nights, Younker 8 Pulm.
- **Medium / consult (2):** Inpatient GI, Infectious Disease — inpatient consult
  services, lighter workload (PTO allowed; this is *why* they carry call). Counted
  as light for max-consecutive-heavy, but chaining them with heavies is undesirable.
- **Light (8):** Outpatient GI/Pulm, Ambulatory A, EM, Addiction, Elective, both
  Outpatient Cardiologies.
- The block app's `rotation_type` flag is unreliable for this (types Inpatient GI /
  ID as OUTPATIENT, Y8Pulm as INPATIENT); the tier list above is authoritative.

**Consecutive heavy+medium thresholds (owner-confirmed), measured in real weeks:**
6 weeks = preferred maximum; 8 weeks = acceptable but not ideal; 10+ weeks = avoid
(e.g. 4wk VA → 2wk ID → 4wk ICU).

| Consecutive heavy+medium run | Count | Threshold |
|---|---|---|
| 2 weeks | 30 | fine |
| 4 weeks | 66 | fine |
| **6 weeks** | **4** | preferred maximum ✓ |
| 8 weeks | **0** | (acceptable tier — unused) |
| 10+ weeks | **0** | avoided ✓ |

- **True heavy → different-heavy transitions: 0.** Max consecutive *heavy*: 4 weeks.
- **No run exceeds the 6-week preferred maximum.** The 4 six-week runs each contain
  at most 2 weeks (Res E, H) or 4 weeks (Res I, J) of genuinely heavy time, with the
  consult services providing relief — none is the multi-heavy stack to avoid:
  - Res E: Inpatient GI → Y7 Nights → ID (only 2wk heavy)
  - Res H: ID → Inpatient GI → Y7 Nights (only 2wk heavy)
  - Res I / J: Y7 Nights → Inpatient GI → Y7 Nights (consult buffers between heavies)

**Conclusion:** the app's generated schedule already avoids the transition pain
points (long unbroken heavy/medium stretches) the program is trying to fix, while
holding call coverage at its ceiling. There is no transition problem in the app's
output to correct. A future comparison against the real, in-use schedule (not yet
loaded) would quantify how much the app improves on current practice.

---

## What was checked but is NOT a problem
- **Double-booking:** a resident is on one rotation per block (no-overlap), and
  GI/ID/Y8Pulm are mutually exclusive per block, so no intern is auto-forced onto
  two conflicting Y7 nights from one block.
- **Sunday volunteer reliance:** none.
- **Y8Pulm being unstaffed:** never — the service always has someone; the issue is
  specifically *intern vs. senior* occupancy in 4 half-blocks.
