# RULES — Residency Block Schedule (domain master)

_The authoritative, plain-English rule set for a valid block schedule, plus the proofs and
reviews that establish and bound those rules. This is the single source of truth for **what a
correct schedule must satisfy**. For **how to run the solver**, see [OPERATIONS.md](OPERATIONS.md);
for **project status, open work, and known bugs**, see [PROJECT.md](PROJECT.md)._

_Consolidated 2026-06-23 from SCHEDULING_RULES, RULES_REVIEW, CROSS_PROJECT_REVIEW, and
COVERAGE_FLOOR_FINDINGS. Last domain update of the underlying rules: 2026-06-18._

> **How to read the enforcement tags.** Each rule says what must be true and where it is guaranteed:
> - 🟥 **Solver-hard** — the optimizer cannot produce a schedule that violates it.
> - 🟦 **Soft objective** — the optimizer is *pushed* toward it but may trade it off; check by hand.
> - 🟨 **Operational / manual** — **not modeled in the solver at all**; satisfied by external
>   staffing (BMC, TY residents) or a human. **These are the rules most likely to be silently
>   violated — audit them first.**

---

## Table of contents

1. [Terminology (read this first)](#1-terminology-read-this-first)
2. [Per-block rotation capacities (hard limits)](#2-per-block-rotation-capacities-hard-limits)
3. [Younker 7 Days coverage (the most nuanced rule)](#3-younker-7-days-coverage-the-most-nuanced-rule)
4. [Younker 7 Nights (call-driving rotation)](#4-younker-7-nights-call-driving-rotation)
5. [The Elective / Younker 7 Nights link rule](#5-the-elective--younker-7-nights-link-rule)
6. [Younker 7 weekend night call coverage (the program's core purpose)](#6-younker-7-weekend-night-call-coverage-the-programs-core-purpose)
7. [Workload tiers & transition quality](#7-workload-tiers--transition-quality)
8. [Rotation shapes & structure](#8-rotation-shapes--structure)
9. [Per-resident requirements](#9-per-resident-requirements)
10. [What the solver does NOT model (audit by hand)](#10-what-the-solver-does-not-model-audit-by-hand)
11. [The coverage floor is ZERO — proof](#11-the-coverage-floor-is-zero--proof)
12. [Block ↔ call schedule (cross-project)](#12-block--call-schedule-cross-project)
13. [Encoded-vs-real rule review (known gaps)](#13-encoded-vs-real-rule-review-known-gaps)
14. [Quick hand-audit checklist](#14-quick-hand-audit-checklist)

---

## 1. Terminology (read this first)

- The program's **block** = **4 weeks**. A **half-block** = **2 weeks**.
- The academic year = **52 weeks = 13 blocks = 26 half-blocks**.
- Internally the app/code calls each 2-week half-block a "block" (a 26-unit grid, **"slots"** in
  this document). So **1 program block = 2 slots**. Slot index = position 0–25; slot label = e.g.
  `1A` (slot 0), `1B` (slot 1), … `13B` (slot 25).
- **Block 7** = slots 12–13 (labels 7A, 7B). **Block 13** = slots 24–25 (13A, 13B).
- The schedule covers the **11 categorical PGY-1 interns**. Other residents (**TY** =
  transitional-year; **BMC** = Broadlawns group) also appear on some rotations and count toward
  coverage, but the solver does **not** schedule them — their placement is external/variable.

> The dual meaning of "block" (4wk domain vs. 2wk code) is the root of historical unit bugs. The
> conversion hub is `model/ScheduleUnits.java`. A planned full rename to block/half-block is
> documented in [PROJECT.md → Terminology cleanup](PROJECT.md#terminology-cleanup-not-started).

---

## 2. Per-block rotation capacities (hard limits)

How many people may be on a rotation in any given block. "Categorical" = the 11 interns; "total" =
categoricals + TY + BMC together. **These are real bed/slot limits — exceeding them is a true
violation even if a schedule otherwise looks fine.**

| Rotation | Rule | Enforcement |
|---|---|---|
| **ICU** | ≤ **1 categorical** per block; ≤ **2 total** (a TY may add a 2nd body) | 🟥 Solver-hard |
| **VA** | ≤ **2 categoricals** per block (never 3) | 🟥 Solver-hard |
| **Broadlawns (BMC rotation)** | ≤ **2 total** (categorical + TY) per block | 🟥 Solver-hard (total cap) |
| **Younker 7 Days** | **exactly 2 total** every block, never more; categoricals ≤ 1 per block **except block 13 = exactly 2**; **never 2 BMC together** (see §3) | 🟥 Solver-hard + 🟨 |
| Younker 8 Pulmonology | ≥ 1 every block (continuous coverage); evenly distributed | 🟥 / 🟦 |
| All other rotations | per-rotation `max_per_week` from config | 🟥 Solver-hard |

The categorical-only caps (ICU ≤1, VA ≤2) are enforced by a dedicated mechanism
(`categorical_max_per_block`) separate from the aux-aware total cap; see
[PROJECT.md → Capacity fix](PROJECT.md#capacity-fix-done) for the implementation history.

**Audit tip:** count bodies per column (block) for ICU, VA, BMC, Y7 Days. ICU/VA/BMC are quick:
never more than the number above. Y7 Days is special — see §3.

---

## 3. Younker 7 Days coverage (the most nuanced rule)

**Y7 Days must have exactly 2 people every block — never 1, never more than 2.** The two bodies are
composed from three sources, and the composition varies by block:

| Block(s) | Body 1 | Body 2 |
|---|---|---|
| Most blocks (a categorical is placed) | **1 categorical** | **1 BMC** |
| Blocks where no categorical is placed (e.g. 3, 6, 12) | **1 BMC** | **1 TY** |
| **Block 7** (slots 12–13) | **1 categorical** | **1 TY** (BMC is absent this block) |
| **Block 13** (slots 24–25) | **1 categorical** | **1 categorical** (no BMC, no TY) |

Rules that produce this:
- Each categorical does **exactly one** 4-week Y7 Days block during the year — this is an
  **absolute requirement; a categorical must never lose their Y7 Days block.** 🟥
- **BMC supplies exactly ONE body** on every block **except block 7 and block 13** (where it is
  absent). **Never two BMC on Y7 Days at once.** 🟨 (written into the schedule by the app's filler
  so it is **visible**, not assumed)
- **TY supplies the 2nd body** on any block where a categorical is not placed (and at block 7). TY
  placement is variable/external; the app writes a TY placeholder so the schedule visibly shows the
  full team of 2. 🟨
- **Block 13 = 2 categoricals** (solver-hard). 🟥
- **Total is capped at 2** — never 3. 🟥

**Audit tip:** every Y7 Days block should show exactly 2 names. Valid combinations: categorical+BMC,
BMC+TY, categorical+TY (block 7 only), or categorical+categorical (block 13 only). **Invalid:** any
block with 1 or 3 bodies, or 2 BMC together, or a categorical without their one required Y7 Days
block somewhere in the year.

---

## 4. Younker 7 Nights (call-driving rotation)

- **Always two separate 2-week segments** — Y7 Nights is **never** a single 4-week run. The two
  segments are split across the year. 🟥
- A resident's two night segments may be close (2 weeks apart) or far (up to opposite ends of the
  year); both are acceptable. 🟦 (well-distributed, not crammed)
- Y7 Nights cannot start in the first program block (earliest start enforced). 🟥
- A break is required between the two segments. 🟥

---

## 5. The Elective / Younker 7 Nights link rule

- Each categorical does **2 slots total** of (Younker 7 Nights + Elective) combined. 🟥
- Only **2 Elective slots exist program-wide** per year. 🟥
- Concretely: **9 of 11** residents do 4 weeks of Y7 Nights; **2 of 11** do 2 weeks of Y7 Nights +
  2 weeks of Elective. 🟥

**Audit tip:** exactly 2 residents should have an Elective block; everyone else has the full 4 weeks
of nights.

---

## 6. Younker 7 weekend night call coverage (the program's core purpose)

The block schedule exists so the downstream **call schedule** can cover Younker 7 weekend nights
without resorting to volunteers. A weekend belongs to the rotation a resident is on (a block runs
Mon → 2nd Sunday). See §12 for how this connects to the actual call app.

- **Saturday Y7 night** is covered by the intern on **Younker 8 Pulmonology**. With 11 interns × 2
  half-blocks each = 22 of 26 half-blocks, exactly **4 half-blocks have no intern on Y8 Pulm** —
  these are covered by upper-levels and are **mathematically unavoidable**, not a defect. 🟨 operational
- **Sunday Y7 night** priority chain: (1) Inpatient GI intern → (2) Infectious Disease intern →
  (3) any intern on an outpatient/elective rotation → (4) volunteer (last resort). 🟨 operational
- **Eligibility rule (critical for auditing):** an intern can cover a given weekend only if they are
  **not on a heavy rotation that block** *and* **not entering a heavy rotation the next block** (a
  rested-weekend rule — they can't take call the weekend before a heavy Monday start). 🟦 soft
  objective (the solver is pushed to keep ≥2 eligible coverers per weekend; **0 means a volunteer is
  forced**).

**Audit tip:** for each weekend, list interns who are (on GI/ID/light now) AND (not entering a heavy
rotation next block). Zero such interns = a volunteer weekend = the thing the schedule is meant to
prevent.

---

## 7. Workload tiers & transition quality

Used for judging how punishing a resident's run of rotations is. **The app's internal
`rotation_type` flag is unreliable for this — use this list.** (`rotation_type` affects only two
soft objectives: the inpatient-split penalty and the 2-inpatient/1-outpatient pattern — nothing
hard, so it is fully reversible.)

- **Heavy (6):** ICU, VA, Broadlawns, Younker 7 Days, Younker 7 Nights, Younker 8 Pulm.
- **Medium / consult (2):** Inpatient GI, Infectious Disease (lighter; PTO-eligible — this is why
  they carry call).
- **Light (8):** Outpatient GI, Outpatient Pulm, Ambulatory A, Emergency Medicine, Addiction
  Medicine, Elective, Outpatient TIC Cardiology, Outpatient UPH Cardiology.

**Transition rules (measured in consecutive weeks of heavy + medium):** 🟦 soft
- **6 weeks** = preferred maximum.
- **8 weeks** = acceptable but not ideal.
- **10+ weeks** = avoid (e.g. 4wk VA → 2wk ID → 4wk ICU).
- Avoid jumping directly from one heavy rotation into a **different** heavy rotation.
- The bad pattern to avoid is *stacked heavies* (VA→ID→ICU). A run that alternates heavy with
  medium/light (e.g. Y7 Nights → GI → Y7 Nights → ID) is far gentler even if long.

> The authoritative tier list lives in code at `model/WorkloadTiers.java` (which cites this section).

---

## 8. Rotation shapes & structure

- **4-week full blocks, even-start, consecutive:** ICU, Broadlawns, Younker 7 Days. 🟥
- **Flexible 2-or-4 weeks:** VA, Younker 8 Pulmonology, Ambulatory A. 🟥
- **2-week half-blocks only:** everything else. 🟥
- VA requires a break between its segments. 🟥
- Each resident is scheduled **every block** (52/52 weeks); there is no vacation/flex in the model —
  intentional. 🟥
- Rotations are not always exactly 4 weeks because 13 blocks span 52 weeks; blocks 1 and 13 carry
  edge-case nuances (e.g. Y7 Days block 13 staffing in §3).

---

## 9. Per-resident requirements

- Each categorical completes their required rotations for the year (minimums per rotation). 🟥
  *(Historically under-enforced for VA / Outpatient GI / Ambulatory A / Outpatient Pulm — see §13
  finding B1, now FIXED. Verify a re-solve respects them.)*
- No prerequisite-rotation ordering is required. 🟥 (none configured)
- Inpatient blocks are spaced out where possible (only specific GI/ID adjacency rules are explicitly
  encoded). 🟦

---

## 10. What the solver does NOT model (audit by hand)

- **The call schedule itself** — no call eligibility/credit/coverage is computed here; the block
  rules are a *proxy* for call-feasibility (§6, §12).
- **TY resident placement** — variable year-to-year, external.
- **BMC staffing** beyond the Y7 Days filler — external.
- **Time off / PTO** — handled in the downstream call system, not here.

---

## 11. The coverage floor is ZERO — proof

_2026-06-19. Why the solver plateaued at 2 volunteer Sundays, what the true floor is, and the
reversible fix._

**The question.** Capacity-correct schedules consistently produced **2 volunteer weekends** —
Sundays with no eligible categorical for Y7 call, forcing the upper-level volunteer fallback.
Repeated long solves (up to 45 min) never beat 2. Was 2 the mathematical floor, or were we solving
wrong? We proved the answer before sinking 10 hours into an overnight run.

**Method (verified, not trusted blindly).** A dedicated CP-SAT model
(`CpSatSchedulerEngine.proveCoverageFloor`, runnable via `tools/CoverageFloorRunner`) keeps **every
hard constraint** of a real solve (reusing `buildBaseModel`) and replaces all soft objectives with a
single one: **minimize volunteer weekends**. The proof's solution was extracted and independently
re-scored in Python: 0 volunteers, zero capacity violations, full heavy load intact (14 heavy
slots/resident, VA = 4 slots, each categorical exactly one Y7-Days block). A real, valid schedule.

**Finding: the floor is ZERO — proven OPTIMAL in ~2 seconds.** A fully-loaded, capacity-correct
schedule with no volunteer weekends exists. So 2 was never the floor.

**Why the full solver never found it.** The solver optimizes **lexicographically**: it locks the
Phase-1 clinical/transition optimum *first*, then optimizes coverage only among schedules that
already have minimal transitions. Zero-volunteer schedules require accepting *some* transition cost —
so once Phase 1 freezes transitions, coverage can never reach zero. **It was the objective
*ordering*, not a lack of search time.** More hours would not have helped.

**The transition tradeoff.** Minimizing volunteers *alone* yields a transition disaster (47
heavy→heavy switches, six 10+ week runs) — but only because that prover ignored transitions. When
transitions are *also* minimized (volunteers hard-locked to 0), they collapse back to near-baseline:

| Metric | run #70 (then-current) | 0-vol, transition-blind | 0-vol + min-transitions* |
|---|---|---|---|
| Volunteer Sundays | 2 | **0** | **0** |
| Fragile weekends | 10 | 3 | 13 |
| Healthy weekends | 13/25 | 22/25 | 12/25 |
| Heavy→different-heavy | 2 | 47 | **4** |
| Runs > 6 weeks | 1 | 16 | 2 |
| Runs > 8 weeks (avoid) | 0 | 6 | 1 |

\* FEASIBLE (not proven optimal) with a deliberately crude transition objective; *a* valid
0-volunteer schedule, not the best one. The real engine's proper objectives should do better.

**Bottom line:** zero volunteer weekends is achievable *with* low heavy→heavy transitions. The
remaining roughness is objective-tuning, not a fundamental conflict.

**The fix — a reversible hard floor.** Config flag **`enforce_zero_volunteer_weekends`** (default
**OFF**) adds a hard constraint (`ConstraintBuilder.applyZeroVolunteerFloor`) requiring every
weekend to have ≥1 eligible coverer. In the app: the auto-schedule screen checkbox *"Require 0
volunteer weekends (hard)"*. Uncheck to revert — one click, fully reversible. Proven feasible, so
turning it on cannot make the model infeasible for this data.

> **Tuning caveat (see [PROJECT.md → Key findings](PROJECT.md#key-findings)):** the hard floor is
> the binding constraint on *fragile* weekends (floor ON pins fragile ~11). The current tuning
> direction is floor **OFF**, target 2, a swept Sunday-coverage weight — trading 1 volunteer weekend
> for roughly halving fragile. Whether to accept 1 volunteer is a clinical/policy call.

**Reproducing the proof.**
```
mvn exec:java -Dexec.mainClass=com.residency.tools.CoverageFloorRunner -Dexec.args="2 0"
#   args: <year> <timeLimitSeconds(0=unlimited)> [trans]
#   add "trans" to hard-lock volunteers to 0 and minimize transitions instead.
```

---

## 12. Block ↔ call schedule (cross-project)

_2026-06-18. An **output-vs-output** review (not an import/export pipeline). The block schedule that
drives call is **built by hand inside the call app** (Scheduler 5.0); the question is conceptual — do
the rules governing Y7 Saturday/Sunday call fit the block schedules we produce, such that the call
scheduler never needs the volunteer fallback?_

**The auto-call mechanism.** A PGY-1 on a **call-generating rotation** for a half-block produces a
HARD, mandatory Y7 night assignment in the call solver. Three auto-call rules
(`rotation_auto_call_rules`), all PGY-1, `applies_to = BOTH`:

| Call rotation | Block-app equivalent | Generates |
|---|---|---|
| Inpatient GI | Inpatient GI | **Y7 Sunday** night (1st choice) |
| ID | Infectious Disease | **Y7 Sunday** night (backup) |
| Younker 8 (Pulm) | Younker 8 Pulmonology | **Y7 Saturday** night |

These auto-call assignments **override** the call solver's forbidden-weekend/eligibility constraints
(they are mandatory). Auto-call is **PGY-1 only** — a senior on the same rotation does not generate
the intern auto-call.

**Weekend eligibility model (the correct way to score coverage).** A half-block runs Monday → second
Sunday; the trailing Sat+Sun belongs to that rotation. Coverage is scored at the *weekend* level
with rest-eligibility — **not** by mere block-level presence. A categorical can cover a back-end
weekend only if (1) **not on a heavy rotation** that half-block, **and** (2) **not entering a heavy
rotation** the next half-block (the pre-rotation rest lock). Heavy set: ICU, VA, Broadlawns, Younker
7 Days, Younker 7 Nights (NM-IMMC), Younker 8 Pulmonology. NM-IMMC is Mon–Fri nights only and does
**not** cover Sat/Sun; Saturday is the Pulm intern's duty (the one "heavy" exception), except the
Broadlawns-resident-when-on-Pulm, who is excused.

> ⚠️ An earlier version reported "0 volunteer weeks" for both schedules — an artifact of a naive
> block-level presence check, since corrected with the eligibility model above.

**Finding: Sunday Y7 — real schedule has 4 volunteer weekends; the app cuts it to 1 (then 0 with the
coverage objective).** Scoring each back-end weekend (categoricals only, apples-to-apples vs. the
11-resident real schedule):

| | Real (in-use) | Block app (Year-2) |
|---|---|---|
| Covered by GI (1st choice) | 7 | 4 |
| Covered by ID (backup) | 4 | 9 |
| Covered by outpatient/elective fallback | 11 | 12 |
| **No eligible intern (→ volunteer)** | **4** (blk 1, 13, 15, 19) | **1** (blk 24) |

The four real-schedule failures are **total wipeouts**: all 11 categoricals simultaneously
rest-locked. This is precisely the structural failure the block app exists to eliminate. Adding a
**soft Tier-3 Sunday-coverage objective** (reward *surplus* coverers per weekend, target ≥2) drives
volunteers to **zero** with no clinical or transition cost — it lives in Phase 3 with Tier-1/Tier-2
locked ≤ best, so it can only break ties on the already-optimal frontier. Gated on config keys
`weight_sunday_coverage`, `sunday_coverage_target`, `heavy_rotation_ids`,
`sunday_source_rotation_ids`; disables itself when weight = 0. Tier source is the §7 list, not
`rotation_type`.

**Finding: Saturday Y7 — covered to the mathematical maximum (working as designed).** Saturday call
needs a PGY-1 on Y8Pulm. 11 interns × 2 half-blocks = **22** intern half-block-assignments, but the
year has **26** half-blocks → exactly **4 half-blocks must have no intern on Y8Pulm**, by counting
alone. The app hits this floor optimally (22/26 covered, perfectly even 2-each distribution); the
residual 4 are what the upper-level/volunteer pool is designed to absorb. No block-side change
possible or needed.

**Net effect.** The app buys a ~75% reduction in volunteer-dependent Sunday call (4 → 1, then → 0
with the objective) and eliminates all heavy→different-heavy transitions (5 → 0), paying only a few
extra six-week runs — none exceeding the owner's 6-week preferred maximum.

**Notes (no action):** block "Infectious Disease" = call "ID"; block "Younker 8 Pulmonology" = call
"Younker 8 (Pulm)" — names only need to stay mentally aligned since the schedule is hand-rebuilt. Any
future change to GI/ID placement should re-run this coverage check to keep Sunday at zero.

---

## 13. Encoded-vs-real rule review (known gaps)

_2026-06-17. Compares the rules **encoded** in the app against the **real program rules** and what
the committed Year-2 schedule produced. The committed schedule satisfies every encoded constraint;
these items are about constraints that were **under-enforced, inverted, inconsistent, or absent** —
not about the current schedule being broken._

**Overarching goal (context).** The block schedule feeds the **call scheduler** (Scheduler 5.0):
`AutoCallRule`s attach to rotations, so the real purpose of arranging blocks is to make a feasible,
well-distributed call schedule always possible. The block app has **no representation of call** — the
block rules are a proxy for call-feasibility (see §12).

**B1 — Per-resident minimums were under-enforced.** ✅ FIXED. The old formula
`ceil(minBlocks) * minLen` conflated "how much is required" with "segment length," under-enforcing
any rotation that allows a 2-week segment (VA enforced 2 slots vs. 4 intended; Outpatient GI / Amb A
/ Outpatient Pulm enforced 1 vs. 2). The minimum now routes through `ScheduleUnits.blocksToSlots`
(`round(min_blocks × 2)` slots) in `ConstraintBuilder` and the diagnostic copies, with regression
tests (`RuleConstraintTest`, cited in code). 13 of 15 required rotations have no coverage floor, so
this per-resident minimum is the only completion guarantee — important that it's now correct.

**B2 — The 4+2 pattern objective is NOT a bug (retracted).** An earlier draft claimed it was
inverted; that was a misread. At cycle positions 0,1 (`b % 3 < 2`) the code penalizes outpatient
(→ encourages inpatient 2 of 3 slots); at position 2 it penalizes inpatient (→ outpatient 1 of 3).
That is 4 weeks inpatient + 2 outpatient per cycle — exactly the intent. *Real, lower-severity
limitation:* the cadence is anchored to the absolute block index (`b % 3`), so every resident's
phase is aligned rather than staggered. A cleaner encoding (per-resident max-consecutive-inpatient,
"option B") is deferred unless real output shows too many long inpatient runs.

**I1 — Inpatient GI was unprotected against heavy transitions (RESOLVED 2026-06-23).** Root cause:
the 6 `CANNOT_IMMEDIATELY_FOLLOW` rules meant to bar each heavy rotation from immediately following
a Sunday-call source pointed at **Outpatient GI (id 2)** instead of **Inpatient GI (id 19)** — a
rotation-ID mix-up. Infectious Disease (15) was correct in those rules. Inpatient GI was still
*partly* shielded because its own `mutually_non_adjacent_with` list (symmetric) already covered 5 of
the 6 heavies — which is why no Inpatient GI→heavy adjacency ever appeared in produced schedules and
masked the bug. The single genuinely-unprotected edge was **Inpatient GI → Younker 7 Nights (11)**,
the one heavy missing from that non-adjacency list. **Fix applied to the live DB:** (1) repointed
all 6 sequence rules `related_rotation_id` 2 → 19, so every heavy now cannot immediately follow
**both** Infectious Disease *and* Inpatient GI; (2) added Younker 7 Nights (11) to the
`mutually_non_adjacent_with` lists of Inpatient GI (19) and Infectious Disease (15), closing the gap
symmetrically. **Outpatient GI** is, per owner, an ordinary outpatient/elective rotation (no
different from Outpatient Pulm / Amb A / Elective): it correctly retains its place in the
`sunday_source` list as a generic fallback coverer and correctly now has **no** heavy-transition
protection (matching its peers). Backup: `residency_scheduler.backup-pre-gi-fix-20260623-194812.db`.

**Dead / inert config (no effect today):** post-call system OFF (trigger/mandatory/discouraged lists
empty — intentional; weekend-shift protection is instead handled by the 12 `CANNOT_IMMEDIATELY_FOLLOW`
rules — each of the 6 heavies barred from following Infectious Disease *and* Inpatient GI as of the
2026-06-23 I1 fix — plus the symmetric `mutually_non_adjacent_with` lists and the inpatient-split
penalty); `weight_pgy_imbalance = 0` and `weight_variance = 0` (never fire — all 11 are PGY-1);
no `rotation_pgy_caps` rows.

**Missing rules (surfaced, not all encoded):**
1. **Continuous coverage incl. ancillary residents** — some services must *always* have someone
   (Y8 Pulm, Y7 Days, VA), satisfied jointly by categorical + ancillary. The model enforces
   categorical floors and pre-counts ancillary, but the "always ≥1 across both pools" rule isn't
   explicitly guaranteed.
2. **Spread out inpatient / avoid stacked heavies** — adjacency rules catch specific GI/ID
   transitions but there's no general "limit consecutive inpatient load across the year" rule.
   (Held backup: "option B" max-consecutive-inpatient soft penalty.)
3. **Call-feasibility as the real objective** — not modeled in the block app at all.
4. **Variable block 1 / block 13 length** — 13 blocks over 52 weeks means block 1's first half and
   block 13's last half aren't exactly 14 days; all 26 slots are treated as uniform 2-week units (a
   modeling simplification, not necessarily a rule to encode).

**Confirmed NOT rules:** no resident time off in this model; no prerequisite rotations; no special
block 1/13 content rules beyond the length variance above.

---

## 14. Quick hand-audit checklist

1. **ICU** ≤1 categorical / ≤2 total per block.
2. **VA** ≤2 categoricals per block.
3. **Broadlawns** ≤2 total per block.
4. **Younker 7 Days** = exactly 2 total every block (valid pairs: cat+BMC, BMC+TY, cat+TY at blk 7,
   cat+cat at blk 13); never 2 BMC together; never 1 or 3; each categorical does exactly 1 Y7 Days
   block somewhere in the year.
5. **Younker 7 Nights** = two separate 2-week segments per resident; never a 4-week run.
6. **Elective** appears exactly twice program-wide; those 2 residents do 2wk nights.
7. **Every Sunday weekend** has ≥1 eligible intern (GI/ID/light now, not entering heavy next) —
   ideally ≥2. Zero = a forced volunteer.
8. **No consecutive heavy+medium run > 8 weeks** (preferably ≤6); no direct heavy→different-heavy
   jumps.
9. **Each resident scheduled every block**, completes required rotations.
