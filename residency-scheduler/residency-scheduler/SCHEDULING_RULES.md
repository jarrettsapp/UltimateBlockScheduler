# Residency Block Schedule — Master Rule Set

_The authoritative, plain-English list of the rules a valid block schedule must follow._
_Maintained for collaborators and for hand-auditing a schedule (including rules the
solver does **not** flag). Last updated 2026-06-18._

> **How to use this document.** Each rule states what must be true, and an
> **Enforcement** tag saying where it is guaranteed:
> - 🟥 **Solver-hard** — the optimizer cannot produce a schedule that violates it.
> - 🟦 **Soft objective** — the optimizer is *pushed* toward it but may trade it off;
>   worth checking by hand.
> - 🟨 **Operational / manual** — **not modeled in the solver at all**; it is satisfied
>   by external staffing (BMC, TY residents) or must be checked by a human. **These are
>   the rules most likely to be silently violated — audit them first.**

---

## 0. Terminology (read this first)

- The program's **block** = **4 weeks**. A **half-block** = **2 weeks**.
- The academic year = **52 weeks = 13 blocks = 26 half-blocks**.
- Internally the app/code calls each 2-week half-block a "block" (a 26-unit grid,
  "slots" in this document). So **1 program block = 2 slots**. Slot index = position
  0–25; slot label = e.g. `1A` (slot 0), `1B` (slot 1), … `13B` (slot 25).
- **Block 7** = slots 12–13 (labels 7A, 7B). **Block 13** = slots 24–25 (13A, 13B).
- The schedule covers the **11 categorical PGY-1 interns**. Other residents
  (**TY** = transitional-year; **BMC** = Broadlawns group) also appear on some
  rotations and count toward coverage, but the solver does **not** schedule them — their
  placement is external/variable.

---

## 1. Per-block rotation capacities (hard limits)

How many people may be on a rotation in any given block. "Categorical" = the 11 interns;
"total" = categoricals + TY + BMC together. **These are real bed/slot limits — exceeding
them is a true violation even if a schedule otherwise looks fine.**

| Rotation | Rule | Enforcement |
|---|---|---|
| **ICU** | ≤ **1 categorical** per block; ≤ **2 total** (a TY may add a 2nd body) | 🟥 Solver-hard |
| **VA** | ≤ **2 categoricals** per block (never 3) | 🟥 Solver-hard |
| **Broadlawns (BMC rotation)** | ≤ **2 total** (categorical + TY) per block | 🟥 Solver-hard (total cap) |
| **Younker 7 Days** | **exactly 2 total** every block, never more; categoricals ≤ 1 per block **except block 13 = exactly 2**; **never 2 BMC together** (see §2) | 🟥 Solver-hard + 🟨 |
| Younker 8 Pulmonology | ≥ 1 every block (continuous coverage); evenly distributed | 🟥 / 🟦 |
| All other rotations | per-rotation `max_per_week` from config | 🟥 Solver-hard |

**Audit tip:** count bodies per column (block) for ICU, VA, BMC, Y7 Days. ICU/VA/BMC are
quick: never more than the number above. Y7 Days is special — see §2.

---

## 2. Younker 7 Days coverage (the most nuanced rule)

**Y7 Days must have exactly 2 people every block — never 1, never more than 2.** The two
bodies are composed from three sources, and the composition varies by block:

| Block(s) | Body 1 | Body 2 |
|---|---|---|
| Most blocks (a categorical is placed) | **1 categorical** | **1 BMC** |
| Blocks where no categorical is placed (e.g. 3, 6, 12) | **1 BMC** | **1 TY** |
| **Block 7** (slots 12–13) | **1 categorical** | **1 TY** (BMC is absent this block) |
| **Block 13** (slots 24–25) | **1 categorical** | **1 categorical** (no BMC, no TY) |

Rules that produce this:
- Each categorical does **exactly one** 4-week Y7 Days block during the year — this is an
  **absolute requirement; a categorical must never lose their Y7 Days block.** 🟥
- **BMC supplies exactly ONE body** on every block **except block 7 and block 13** (where
  it is absent). **Never two BMC on Y7 Days at once.** 🟨 (written into the schedule by the
  app's filler so it is **visible**, not assumed)
- **TY supplies the 2nd body** on any block where a categorical is not placed (and at
  block 7). TY placement is variable/external; the app writes a TY placeholder so the
  schedule visibly shows the full team of 2. 🟨
- **Block 13 = 2 categoricals** (solver-hard). 🟥
- **Total is capped at 2** — never 3. 🟥

**Audit tip:** every Y7 Days block should show exactly 2 names. Valid combinations:
categorical+BMC, BMC+TY, categorical+TY (block 7 only), or categorical+categorical (block
13 only). **Invalid:** any block with 1 or 3 bodies, or 2 BMC together, or a categorical
without their one required Y7 Days block somewhere in the year.

---

## 3. Younker 7 Nights (call-driving rotation)

- **Always two separate 2-week segments** — Y7 Nights is **never** a single 4-week run.
  The two segments are split across the year. 🟥
- A resident's two night segments may be close (2 weeks apart) or far (up to opposite
  ends of the year); both are acceptable. 🟦 (well-distributed, not crammed)
- Y7 Nights cannot start in the first program block (earliest start enforced). 🟥
- A break is required between the two segments. 🟥

---

## 4. The Elective / Younker 7 Nights link rule

- Each categorical does **2 slots total** of (Younker 7 Nights + Elective) combined. 🟥
- Only **2 Elective slots exist program-wide** per year. 🟥
- Concretely: **9 of 11** residents do 4 weeks of Y7 Nights; **2 of 11** do 2 weeks of
  Y7 Nights + 2 weeks of Elective. 🟥

**Audit tip:** exactly 2 residents should have an Elective block; everyone else has the
full 4 weeks of nights.

---

## 5. Younker 7 weekend night call coverage (the program's core purpose)

The block schedule exists so the downstream **call schedule** can cover Younker 7 weekend
nights without resorting to volunteers. A weekend belongs to the rotation a resident is on
(a block runs Mon → 2nd Sunday).

- **Saturday Y7 night** is covered by the intern on **Younker 8 Pulmonology**. With 11
  interns × 2 half-blocks each = 22 of 26 half-blocks, exactly **4 half-blocks have no
  intern on Y8 Pulm** — these are covered by upper-levels and are **mathematically
  unavoidable**, not a defect. 🟨 operational
- **Sunday Y7 night** priority chain: (1) Inpatient GI intern → (2) Infectious Disease
  intern → (3) any intern on an outpatient/elective rotation → (4) volunteer (last
  resort). 🟨 operational
- **Eligibility rule (critical for auditing):** an intern can cover a given weekend only
  if they are **not on a heavy rotation that block** *and* **not entering a heavy rotation
  the next block** (a rested-weekend rule — they can't take call the weekend before a
  heavy Monday start). 🟦 soft objective (the solver is pushed to keep ≥2 eligible
  coverers per weekend; **0 means a volunteer is forced**).

**Audit tip:** for each weekend, list interns who are (on GI/ID/light now) AND (not
entering a heavy rotation next block). Zero such interns = a volunteer weekend = the thing
the schedule is meant to prevent.

---

## 6. Workload tiers & transition quality

Used for judging how punishing a resident's run of rotations is. **The app's internal
`rotation_type` flag is unreliable for this — use this list.**

- **Heavy (6):** ICU, VA, Broadlawns, Younker 7 Days, Younker 7 Nights, Younker 8 Pulm.
- **Medium / consult (2):** Inpatient GI, Infectious Disease (lighter; PTO-eligible —
  this is why they carry call).
- **Light (8):** Outpatient GI, Outpatient Pulm, Ambulatory A, Emergency Medicine,
  Addiction Medicine, Elective, Outpatient TIC Cardiology, Outpatient UPH Cardiology.

**Transition rules (measured in consecutive weeks of heavy + medium):** 🟦 soft
- **6 weeks** = preferred maximum.
- **8 weeks** = acceptable but not ideal.
- **10+ weeks** = avoid (e.g. 4wk VA → 2wk ID → 4wk ICU).
- Avoid jumping directly from one heavy rotation into a **different** heavy rotation.
- The bad pattern to avoid is *stacked heavies* (VA→ID→ICU). A run that alternates heavy
  with medium/light (e.g. Y7 Nights → GI → Y7 Nights → ID) is far gentler even if long.

---

## 7. Rotation shapes & structure

- **4-week full blocks, even-start, consecutive:** ICU, Broadlawns, Younker 7 Days. 🟥
- **Flexible 2-or-4 weeks:** VA, Younker 8 Pulmonology, Ambulatory A. 🟥
- **2-week half-blocks only:** everything else. 🟥
- VA requires a break between its segments. 🟥
- Each resident is scheduled **every block** (52/52 weeks); there is no vacation/flex in
  the model — intentional. 🟥
- Rotations are not always exactly 4 weeks because 13 blocks span 52 weeks; blocks 1 and
  13 carry edge-case nuances (e.g. Y7 Days block 13 staffing in §2).

---

## 8. Per-resident requirements

- Each categorical completes their required rotations for the year (minimums per
  rotation). 🟥 *(Note: historically these minimums were under-enforced for VA /
  Outpatient GI / Ambulatory A / Outpatient Pulm — see RULES_REVIEW.md item B1. Verify a
  re-solve respects them.)*
- No prerequisite-rotation ordering is required. 🟥 (none configured)
- Inpatient blocks are spaced out where possible (only specific GI/ID adjacency rules are
  explicitly encoded). 🟦

---

## 9. What the solver does NOT model (audit these by hand)

- **The call schedule itself** — no call eligibility/credit/coverage is computed here;
  the block rules are a *proxy* for call-feasibility (§5).
- **TY resident placement** — variable year-to-year, external.
- **BMC staffing** beyond the Y7 Days filler — external.
- **Time off / PTO** — handled in the downstream call system, not here.

---

## Appendix — quick hand-audit checklist

1. **ICU** ≤1 categorical / ≤2 total per block.
2. **VA** ≤2 categoricals per block.
3. **Broadlawns** ≤2 total per block.
4. **Younker 7 Days** = exactly 2 total every block (valid pairs: cat+BMC, BMC+TY,
   cat+TY at blk 7, cat+cat at blk 13); never 2 BMC together; never 1 or 3; each
   categorical does exactly 1 Y7 Days block somewhere in the year.
5. **Younker 7 Nights** = two separate 2-week segments per resident; never a 4-week run.
6. **Elective** appears exactly twice program-wide; those 2 residents do 2wk nights.
7. **Every Sunday weekend** has ≥1 eligible intern (GI/ID/light now, not entering heavy
   next) — ideally ≥2. Zero = a forced volunteer.
8. **No consecutive heavy+medium run > 8 weeks** (preferably ≤6); no direct
   heavy→different-heavy jumps.
9. **Each resident scheduled every block**, completes required rotations.
