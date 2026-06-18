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

## Findings

### ✅ Sunday Y7 night — fully coverable, no volunteer needed
Across all 26 half-blocks (52 weekend-weeks), **every** weekend has at least one
intern option for Sunday call. There is **no week** that falls through to the
volunteer system.

| Source (per half-block) | Block app (Year-2) | Call app (hand-built) |
|---|---|---|
| Inpatient GI present (1st choice) | 11 | 22 wk |
| ID only (backup) | 8 | 8 wk |
| Outpatient/elective fallback only | 7 | 22 wk |
| **No intern option (→ volunteer)** | **0** | **0** |

The Sunday priority chain is robust in both schedules. This objective is met.

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

## Summary: the block schedule already serves the call schedule well

| Call requirement | Status |
|---|---|
| Sunday Y7 night coverable every weekend (no volunteers) | ✅ Met — 0 volunteer weeks |
| Saturday Y7 night intern coverage | ✅ Maximized — 22/26 (the arithmetic ceiling), evenly distributed |
| No intern double-booked across Y7 nights | ✅ Structurally impossible (one rotation per block) |
| Equitable intern Y8Pulm load | ✅ Exactly 2 half-blocks each |

Both the Sunday chain and the Saturday auto-call work as intended. The only
"gaps" are the four mathematically-forced Y8Pulm half-blocks, which the call
schedule's upper-level/volunteer fallback exists to cover — this is the designed
behavior, not a deficiency.

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
