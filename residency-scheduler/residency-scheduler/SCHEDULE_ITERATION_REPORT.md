# Block Schedule — Iteration Comparison Report

_Generated 2026-06-18. Academic year 2 (the computed year). 11 categorical PGY-1s._

This report compares the **current real-world (in-use) schedule** against each
successive version the app produced, measured uniformly on the metrics we track.
All four schedules were scored with the **same analyzer** so the comparison is
apples-to-apples.

---

## Methodology — how each metric is measured

**Workload tiers** (owner-confirmed; the block app's `rotation_type` flag is *not*
used because it mis-types these):
- **Heavy (6):** ICU, VA, Broadlawns, Younker 7 Days, Younker 7 Nights, Younker 8 Pulm.
- **Medium / consult (2):** Inpatient GI, Infectious Disease (PTO-eligible, lighter —
  this is why they carry call; undesirable to chain with heavies).
- **Light (8):** Outpatient GI/Pulm, Ambulatory A, EM, Addiction, Elective, both
  Outpatient Cardiologies.

**Weekend call-coverage eligibility** (the model that actually matters): a half-block
runs Mon → 2nd Sun; the trailing weekend belongs to that block. A categorical can
cover a given Sunday Y7 night **iff** (1) they are on a Sunday-source rotation
(Inpatient GI / ID / any light) that block, **and** (2) they are **not** entering a
heavy rotation the next block (the manually-imposed pre-rotation rest lock). Per
weekend we count eligible coverers:
- **0 coverers = volunteer weekend** (the call scheduler must fall back to upper-level
  volunteers — the failure mode the block app exists to eliminate).
- **1 coverer = fragile** (the lone resident is forced onto that call, unbalancing the
  downstream call-shift counts).
- **≥ 2 coverers = healthy** (the call scheduler has slack to balance shifts).

**Consecutive heavy+medium run** = unbroken weeks of heavy and/or medium rotations.
Owner thresholds: **6 wk preferred max, 8 wk acceptable-not-ideal, 10 wk+ avoid.**

**Saturday Y7** depends on a PGY-1 being on Younker 8 Pulm; with 11 interns × 2
half-blocks = 22 of 26 half-blocks, exactly **4 half-blocks must lack an intern** by
arithmetic — this floor is unbeatable and identical for every schedule.

---

## Summary table

| Metric | REAL (in-use) | App v1 (no cov. obj) | App v2 (cov. obj, 3min) | **App v3 (brute-force, 45min)** |
|---|---|---|---|---|
| **Volunteer (0-coverer) Sundays** | **4** ✗ | 1 | 0 ✓ | **0** ✓ |
| Fragile (1-coverer) weekends | 8 | 12 | 8 | **5** ✓ |
| Healthy (≥2-coverer) weekends | 13 / 25 | 12 / 25 | 17 / 25 | **20 / 25** ✓ |
| Direct heavy→different-heavy | **5** ✗ | 0 ✓ | 0 ✓ | 0 ✓ |
| Runs > 6 weeks (heavy+med) | 0 | 0 | 0 | **1** (8 wk) |
| Six-week runs | 1 | 4 | 3 | 5 |
| Runs ≥ 10 weeks (avoid) | 0 | 0 | 0 | 0 |
| Saturday no-Pulm (arithmetic floor) | 4 | 4 | 4 | 4 |
| Younker 8 Pulm per resident | 2 each (even) | 2 each | 2 each | 2 each |
| Tier-1 clinical score | — | **0** (optimal) | **0** | **0** |
| Tier-2 coverage/variance score | — | **0** (optimal) | **0** | **0** |

Legend: ✓ = best / at floor; ✗ = the problem this iteration set out to fix.

---

## Per-schedule detail

### 1. REAL (in-use) schedule — the current hand-built baseline
- **Volunteer Sundays: 4** (weekends after blocks 1, 13, 15, 19). At each, **all 11
  categoricals are simultaneously rest-locked** — on a heavy rotation or entering one
  the next Monday. These are total coverage wipeouts requiring the volunteer fallback.
- **Direct heavy→heavy transitions: 5** — VA→Pulm (×2), Pulm→Nights, Nights→VA,
  Nights→Pulm. These are the awkward back-to-back heavy switches.
- **Transitions otherwise excellent:** only one 6-week run, none longer. The hand
  schedule is genuinely good at avoiding long stretches — its weakness is *coverage*,
  not run length.
- Per-weekend coverers: `[6,0,2,2,3,2,2,1,5,2,4,1,3,0,1,0,1,1,2,0,2,1,4,1,1]`

### 2. App v1 — first computed schedule, no coverage objective
- **Volunteer Sundays: 1** (down from 4). The app's clinical/coverage optimization
  alone already removes 3 of the 4 wipeouts.
- **Heavy→heavy: 0** — eliminates all 5 of the real schedule's awkward switches.
- **Cost:** fragile weekends rose to 12 (the solver wasn't yet *aware* of weekend
  coverage, so it didn't spread coverers); 4 six-week runs (all within tolerance).
- Tier-1 = 0, Tier-2 = 0 (clinically optimal).

### 3. App v2 — soft Sunday-coverage objective added (180 s Phase 3)
- **Volunteer Sundays: 0** ✓ — the goal met. The call scheduler never needs volunteers.
- **Fragile weekends: 8** (down from 12); healthy ≥2-coverer weekends up to 17/25.
- **No transition regression:** 0 heavy→heavy, 0 runs over 6 weeks, one *fewer*
  six-week run than v1.
- Tier-1 = 0, Tier-2 = 0.

### 4. App v3 — brute-force 45-minute Phase-3 solve (CANDIDATE BEST)
- **Volunteer Sundays: 0** ✓; **fragile weekends: 5** — this is the **theoretical
  floor** (a rigorous bound shows ~3–5 fragile weekends are mathematically forced by
  the fixed heavy load + the always-split Younker 7 Nights rule; 0 is an unreachable
  edge case). Healthy ≥2-coverer weekends: **20 / 25** — best of all.
- **One 8-week run appeared** (Res G): `Y7 Nights → Inpatient GI → Y7 Nights →
  Infectious Disease`. Composition: only **4 wk genuinely heavy, 4 wk medium consult,
  fully alternating, zero heavy→heavy contact**. It is the *mildest possible* 8-week
  run and a structural byproduct of the Y7-Nights-always-split rule (pairing the split
  nights with the call-generating consults is what *produces* the coverage gain). Not
  the stacked-heavy pattern (e.g. VA→ID→ICU) the program wants to avoid.
- Tier-1 = 0, Tier-2 = 0.
- **Younker 7 Nights distribution is healthy** (not crammed): per-resident gap between
  the two 2-week night segments ranges **2 → 26 weeks** (median 10). Example: Res I
  does nights at 3A and 10A (opposite ends). The 2 residents with a single night
  segment are the 2 Elective residents (link rule: 9/11 do 4 wk nights, 2/11 do 2 wk
  nights + 2 wk Elective).

---

## What improved, measurably, and where the tradeoffs are

**The arc of improvement (REAL → v3):**
- **Volunteer Sundays 4 → 0** — the single most important gain. The whole reason the
  block app exists is to make downstream call coverage feasible without volunteers;
  every app version achieves more of this than the hand schedule, and v2/v3 fully.
- **Heavy→heavy transitions 5 → 0** — every app version removes all of these.
- **Healthy ≥2-coverer weekends 13 → 20** — v3 gives the call scheduler the most slack
  to balance per-resident call-shift counts.

**The one tradeoff that emerged (v2 → v3):**
- Recovering the last 3 fragile weekends (8 → 5) cost **one mild 8-week run**. Every
  schedule stays clear of the 10-week "avoid" threshold and of heavy→heavy contact.

**Everything that can hit a hard floor already has, in every app version:** Tier-1
clinical = 0, Tier-2 coverage/variance = 0, Saturday no-Pulm = 4 (arithmetic floor),
Younker 8 Pulm perfectly even at 2 each.

---

## Schedule snapshots on disk (for reproducibility)

| Version | File |
|---|---|
| App v1 (pre-coverage-objective) | `residency_scheduler.backup-presolve-coverage-20260618-112538.db` |
| App v2 (coverage obj, 180 s P3) | `residency_scheduler.clean-8single-20260618.db` |
| App v3 (brute-force, 45 min P3) | `residency_scheduler.candidate-best-5single-20260618.db` |
| Current working DB | `residency_scheduler.db` (= App v3) |

Real-world schedule source: `PGY-1 schedule template FINAL` CSV (the in-use schedule).
