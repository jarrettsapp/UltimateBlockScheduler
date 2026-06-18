# Rules Review — encoded constraints vs. real program rules

_Date: 2026-06-17 · Source: live `residency_scheduler.db` (Year-2 schedule), the
CP-SAT constraint code, and the Scheduler 4.0 auto-call source on the Desktop._

This document compares the rules **encoded** in the Block Schedule app against the
**real program rules** as described by the program owner, and against what the
committed schedule actually produced. It is the output of a rule-by-rule review
intended to surface conflicts, poorly-implemented constraints, dead config, and
**missing rules**.

The committed Year-2 schedule satisfies every encoded constraint (0 adjacency
violations, link rule exact, even-starts clean), so the issues below are about
constraints that are **under-enforced, inverted, inconsistent, or absent** — not
about the current schedule being broken.

---

## The overarching goal (context that was previously implicit)

The block schedule is not an end in itself: it feeds the **call scheduler**
(Scheduler 4.0/5.0). There, `AutoCallRule`s attach to **rotations** — a resident
on a given rotation generates mandatory weekend call, has forbidden weekends, and
must hit **minimum call credits**. So the real purpose of rearranging blocks is
to **arrange rotations such that a feasible, well-distributed call schedule is
always possible** ("always have someone available for call").

The Block Schedule app currently has **no representation of call** — none of the
call eligibility, credit, or coverage rules are modeled here. The block rules are
a proxy for call-feasibility. Worth deciding (see open questions) how much of that
downstream goal should be made explicit in the block model.

---

## 🔴 Bugs (encoded ≠ intended)

### B1. Per-resident minimum requirements are under-enforced
`ConstraintBuilder.applyMaxBlocksPerResidentConstraints` derives the minimum as
`ceil(minBlocks) * minLen`, where `minLen` is the rotation's **shortest allowed
segment**. This conflates "how much is required" with "segment length" and
under-enforces wherever a rotation allows a 2-week segment:

| Rotation | Intended min | Enforced min | In Year-2 schedule |
|----------|-------------|--------------|--------------------|
| VA | 4 slots (8 wk) | **2 slots** | 4 (solver had room) |
| Outpatient GI | 2 slots (4 wk) | **1 slot** | 2 |
| Ambulatory A | 2 slots | **1 slot** | 2 |
| Outpatient Pulmonology | 2 slots | **1 slot** | 2 |
| Broadlawns / ICU / Younker 7 Days | 2 slots | 2 ✓ (only because minLen=2) | 2 |

`min_blocks` is expressed in 4-week blocks (0.5 = one 2-week slot, 1 = two slots,
2 = four slots). The correct conversion is `round(min_blocks * 2)` **slots**,
independent of segment length.

**Why it matters:** 13 of 15 required rotations have **no coverage floor**
(`min_per_week = 0`), so this per-resident minimum is the *only* guarantee a
resident completes the rotation. Today's schedule meets the intent by luck of
available room; a tighter year could legally short a resident to half their
required time on VA, Outpatient GI, Ambulatory A, or Outpatient Pulmonology.

### B2. The 4+2 pattern objective — NOT a bug (retracted)
**Correction:** an earlier draft of this review claimed the 4+2 objective was
inverted (rewarding outpatient-heavy instead of inpatient-heavy). That was a
**misread of the code on my part.** Tracing it precisely:

- At cycle positions 0,1 (`b % 3 < 2`) the code penalizes **outpatient**
  occupancy → encourages inpatient in 2 of 3 slots.
- At position 2 it penalizes **inpatient** → encourages outpatient in 1 of 3.

That is **4 weeks inpatient + 2 weeks outpatient per cycle — exactly the stated
intent.** The objective points the right way. (Only the old code comment was
imprecise; the logic was correct. Comments have been clarified, no logic changed.)

The Year-2 schedule's ~14 inpatient / ~12 outpatient balance is consistent with
this — the objective was helping, not fighting.

**Real, lower-severity limitation (kept):** the cadence is anchored to the
absolute block index (`b % 3`), so every resident's preferred phase is aligned
rather than staggered. If staggering matters for call distribution, the cleaner
encoding is a per-resident **max-consecutive-inpatient** rule ("option B" below) —
which also directly captures "limit to ~4 weeks inpatient at a time." Deferred
unless the real output shows too many long inpatient runs.

> Note: a clean 4+2 is not arithmetically achievable with 11 residents over 13
> blocks; the intent is a *tendency*, which a soft objective is the right tool for.

---

## 🟠 Inconsistencies (flagged; no changes made)

### I1. Inpatient GI vs Infectious Disease are not configured alike
They are assumed to be twins but differ:

| | Infectious Disease | Inpatient GI |
|--|--------------------|--------------|
| Type | OUTPATIENT | OUTPATIENT |
| Length / cap / non-adjacency / PGY req | 2wk, cap 1, non-adj w/ {BRD,ICU,VA,Y7D,Y8P}, PGY1 0.5/0.5 req | identical |
| Trigger in `CANNOT_IMMEDIATELY_FOLLOW` | **Yes** — 6 inpatient rotations can't immediately follow ID | **No** — not a trigger anywhere |
| `requires_consecutive` | not set | set = 1 (no-op on a 1-slot rotation) |

If they are meant to behave identically, **Inpatient GI is missing the 6
adjacency rules** ID has. Decision on the INPATIENT/OUTPATIENT type flip is
deferred (see open questions); note that `rotation_type` affects **only** two soft
objectives (inpatient-split penalty, the 4+2 pattern) — nothing hard or
feasibility-related — so it is fully reversible.

---

## 🟡 Dead / inert configuration (no effect today)

- **Post-call system OFF** (trigger/mandatory/discouraged lists empty) — confirmed
  intentional; transitions are handled by the 12 `CANNOT_IMMEDIATELY_FOLLOW` rules
  and the inpatient-split penalty.
- `weight_pgy_imbalance = 0`, `weight_variance = 0` — those objectives never fire
  (reasonable: all 11 categoricals are PGY-1).
- No `rotation_pgy_caps` rows — the per-PGY staffing-cap feature is unused.

---

## Missing rules (surfaced in review, not yet encoded)

1. **Continuous coverage incl. ancillary residents.** Some services must *always*
   have someone on them, satisfied jointly by categorical **and ancillary**
   residents — primarily **Younker 8 Pulmonology**, **Younker 7 Days**, and **VA**
   (≥1 resident at all times). The block model only enforces coverage floors on
   categoricals; ancillary contribution is pre-counted but the "always ≥1 across
   both pools" rule isn't explicitly guaranteed.
2. **Spread out inpatient blocks / avoid stacked heavy rotations.** Avoid chains
   like Nights → VA → Younker 7 Days → ICU (all inpatient, uneven workload). The
   adjacency rules catch specific GI/ID transitions but there is **no general
   "limit consecutive inpatient load across the year"** rule.
3. **Call-feasibility as the real objective** (see overarching goal). Not modeled
   in the block app at all.
4. **Variable block 1 / block 13 length** — 13 blocks over 52 weeks means the
   first half of block 1 and last half of block 13 are not exactly 14 days
   (one longer, one shorter, varying yearly). Currently all 26 slots are treated
   as uniform 2-week units; this is a modeling simplification, not necessarily a
   rule to encode.

Explicitly **not** rules (confirmed): no resident time off in this model
(vacation handled outside the scheduler); no prerequisite rotations (residents may
complete rotations in any order subject to other rules); no special block 1/13
content rules beyond the length variance above.

---

## Suggested fix priority

1. **B1** (under-enforced minimums) — ✅ FIXED: minimum now routes through
   `ScheduleUnits.blocksToSlots` in `ConstraintBuilder` (and the 5 diagnostic
   copies), with regression tests.
2. **B2** (4+2) — retracted, not a bug; comments clarified only. The block-index
   phase-alignment limitation is deferred (see "option B" max-consecutive-inpatient).
3. **I1 / missing-rule #1** — decide ID/Inpatient GI alignment and whether to add
   explicit continuous-coverage guarantees spanning ancillary residents.
4. Decide how much of the **call-feasibility goal** to represent here vs. leave to
   the downstream call scheduler.

> **Option B (max-consecutive-inpatient).** A soft penalty on any run of more than
> 2 consecutive inpatient slots (4 weeks) on *any combination* of inpatient
> rotations — distinct from the existing per-rotation `maxConsecutiveBlocks`, which
> can't see VA→Y7Days→ICU as one long inpatient stretch. Held as a backup to the
> 4+2 objective; add only if the real output shows too many long inpatient runs.
