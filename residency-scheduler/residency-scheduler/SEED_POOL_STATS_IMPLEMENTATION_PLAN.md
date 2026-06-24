# Seed-Pool Statistics — Implementation Plan

**Status:** Ready to implement. Produced after a 3-round statistical-modeling review (analytical
session + relay session + this session), each round cross-checked against the actual code. No
disagreements remain. This plan is the build artifact; it folds the KM / distance-monitor / ICC
specs inline so the implementer has minimal thinking to do.

**Scope decision (locked):** ONE plan, phased into independent PRs. Data-gated items (ICC, racing,
Chao1) are specified but marked BLOCKED — do not build them until their gating data exists.

**Guardrails (carry over):** additive + env-gated where behavior changes; default behavior
unchanged; reversible; never touch `residency_scheduler.db` live (work on copies); stepwise verify.

---

## The consolidated work-list (net of all three review rounds)

| # | Item | PR | Buildable now? | Gate |
|---|------|----|----|------|
| A | Bug: caps above censoring time reported as if observed | PR1 | yes | — |
| B | Bug: cost CI treats `mean_run` as fixed → too narrow | PR1 | yes | — |
| 3 | KM survival analysis as primary cap tool (subsumes the grid; auto-fixes A) | PR2 | yes | — |
| 4 | Nearest-neighbor Hamming distance logged per new seed (corrected saturation monitor) | PR3 | yes | — |
| 5 | Lexicographic reward (T1→T2→T3) + fix "three independent `best_*` ≠ one schedule" | PR4 | yes | — |
| 6 | ICC / variance-decomposition: "does seed identity predict quality?" | — | **NO** | needs full-run reward data: ≥5 runs each on ≥10 seeds |
| 7 | Racing / best-arm identification (not UCB) | — | **NO** | gated on ICC > 0 |
| 8 | Chao1 / Good–Turing saturation | — | **NO** | needs doubletons under exact dedup → likely never; superseded by #4 |

PR order is the dependency order. PR1–PR4 are mutually independent and can land in any order, but the
numbering reflects value × cheapness (PR1 first: pure-Python bug fixes, smallest blast radius).

---

## PR1 — Fix bugs A and B in `analyze_collection_cap.py`

**File:** [`analyze_collection_cap.py`](analyze_collection_cap.py) only. Pure Python, no new deps, no
DB, no Java. Smallest, highest-confidence change.

### Bug A — refuse / flag candidate caps above the data's censoring time

**Problem (confirmed):** the data is collected at a finite cap (capped runs sit at ~181s in
`phase0_seed_results.csv`, or ~300s in other CSVs). A candidate cap `C` *greater than* the largest
observed censoring time is **unidentifiable** — we cannot know how many capped runs would have
finished between the censoring time and `C`. The current loop ([analyze_collection_cap.py:78-92](analyze_collection_cap.py#L78-L92))
still computes `p(C)`, NNT, and cost for such `C` (e.g. 210, 300 in the default list at
[line 45](analyze_collection_cap.py#L45)), silently treating them as observed. This makes
"is 300s wasteful?" appear answered when it is not.

**Spec — exact change:**

1. After loading rows, compute the censoring boundary = the largest time among **non-feasible**
   (capped) runs. Censored runs are those whose status is NOT in `{OPTIMAL, FEASIBLE}`. Use their
   recorded `phase0_secs` (the actual stop time) as the censoring time.

   ```python
   censor_times = []
   for r in rows:
       try: t = float(r["phase0_secs"])
       except: t = observed_cap
       if r["phase0_status"] not in ("OPTIMAL", "FEASIBLE"):
           censor_times.append(t)
   # Largest time we have ANY information up to. Beyond this, p(C) is unidentifiable.
   max_observed = max([*feas_times, *censor_times], default=observed_cap)
   ```

   Note: identifiability is bounded by the largest time we observed *anything* at — that is
   `max(all feasible times, all censoring times)`. Past that point every run's fate is unknown.

2. In the per-candidate loop, when `C > max_observed`, do **not** print numeric `p/NNT/cost`. Print
   the row with a `UNIDENTIFIABLE (cap exceeds observed horizon Xs)` flag and `continue` (exclude it
   from the `best` selection at [line 87](analyze_collection_cap.py#L87)).

   ```python
   for C in cands:
       if C > max_observed:
           print(f"{C:6.0f} | UNIDENTIFIABLE — cap exceeds observed horizon {max_observed:.0f}s "
                 f"(no run was observed past it; p(C) cannot be estimated from this data)")
           continue
       ...  # existing computation
   ```

3. Print the horizon once in the header block so the user sees the bound:
   `print(f"observed horizon (max time any run reached): {max_observed:.0f}s — caps above this are unidentifiable")`

**Why not just clamp?** Clamping `C` to `max_observed` would hide the question. The user explicitly
wants to know whether 300s is wasteful; the honest answer from short-cap data is "we cannot see past
~181s, collect at a longer cap if you must compare 300." KM (PR2) is the principled fix; this flag is
the stopgap so the existing tool stops lying.

### Bug B — bootstrap the cost CI instead of propagating only `p`'s interval

**Problem (confirmed):** `cost(C) = mean_run / p(C)` ([line 84](analyze_collection_cap.py#L84)). The
CI ([lines 85-86](analyze_collection_cap.py#L85-L86)) inverts the Wilson interval of `p` but treats
`mean_run` as a known constant. `mean_run` is itself estimated from the same runs AND is correlated
with `p` through the shared `spent` term ([line 81](analyze_collection_cap.py#L81)). The reported
cost CI is therefore **too narrow** (optimistic), especially at small n.

**Spec — replace the analytic cost CI with a nonparametric bootstrap:**

Add a bootstrap that resamples *whole runs* (preserving each run's status+time jointly, which is what
keeps `p` and `mean_run` correctly correlated):

```python
import random

def bootstrap_cost_ci(rows_data, C, observed_cap, B=5000, z_lo=2.5, z_hi=97.5, seed=12345):
    """
    Nonparametric bootstrap of cost(C) = (mean seconds per run) / p(C).
    rows_data: list of (is_feasible: bool, time: float) for every run.
    Resamples whole runs with replacement so p(C) and mean_run stay jointly distributed.
    Returns (point, lo, hi). Point uses the full sample; lo/hi are percentile CI.
    """
    rng = random.Random(seed)
    n = len(rows_data)
    def cost_of(sample):
        k = sum(1 for (f, t) in sample if f and t <= C)
        if k == 0:
            return float('inf')
        spent = sum((t if (f and t <= C) else C) for (f, t) in sample)
        return (spent / len(sample)) / (k / len(sample))   # == spent / k
    point = cost_of(rows_data)
    boots = []
    for _ in range(B):
        sample = [rows_data[rng.randrange(n)] for _ in range(n)]
        c = cost_of(sample)
        if math.isfinite(c):
            boots.append(c)
    if not boots:
        return (point, float('nan'), float('nan'))
    boots.sort()
    lo = boots[int(z_lo/100 * len(boots))]
    hi = boots[int(z_hi/100 * len(boots))]
    return (point, lo, hi)
```

Note `cost_of` simplifies to `spent / k` (the `len(sample)` factors cancel) — that is exactly
"total seconds spent / feasible entries obtained," the quantity the docstring defines. Build
`rows_data` once:

```python
rows_data = []
for r in rows:
    try: t = float(r["phase0_secs"])
    except: t = observed_cap
    rows_data.append((r["phase0_status"] in ("OPTIMAL", "FEASIBLE"), t))
```

In the per-candidate loop, replace the analytic `cost_lo, cost_hi` with
`_, cost_lo, cost_hi = bootstrap_cost_ci(rows_data, C, observed_cap)`. Keep the Wilson interval for
`p` and NNT (those are correct — a clean proportion). Label the cost CI in output as `(bootstrap 95%)`.

**Keep `needed_n_for_margin` but relabel it.** It uses the Wald sample-size approximation while the
rest uses Wilson ([line 38-41](analyze_collection_cap.py#L38-L41)). It's fine as an order-of-magnitude
"are we powered?" check; just change its printout to read `power check (±10% margin on p, Wald
approx)` so the inconsistency is disclosed, not hidden.

### PR1 verification
- Run `python analyze_collection_cap.py phase0_seed_results.csv 181 60 90 120 150 180 210 300`.
  Expect: 210 and 300 print `UNIDENTIFIABLE` (horizon ≈181s); cost CIs are wider than before;
  the `best` cap is chosen only among identifiable candidates.
- Run against the current ~n=55 CSV the data session is producing; sanity-check the headline
  (max feasible ≈146s → ~90–150s cap) survives, now with a bootstrap CI.
- No DB, no Java touched. Diff is confined to one file.

---

## PR2 — Kaplan–Meier survival analysis as the primary cap tool

**New file:** `analyze_cap_survival.py` (sibling of `analyze_collection_cap.py`). The KM tool
*subsumes* the grid analysis: it estimates `S(t) = P(time-to-feasible > t)` from the feasible times
**and** the censored runs, handling censoring correctly, and yields `p(C) = 1 − S(C)` and the
cost-vs-cap curve at *every* `t`, not 8 grid points. KM is undefined past the largest observed time,
which **automatically enforces Bug A's fix** — it simply will not report `S(t)` past the horizon.

**Framing (for the report, do not oversell):** KM makes the cap choice *rigorous*, not a plot twist.
If the real data's max feasible time is ~146s, KM will confirm ~90–150s with a proper CI and prove
300s wasteful — it converts a defensible eyeball call into a powered one. Headline unlikely to change.

### Dependency decision (locked: implementer chooses + documents)

**Chosen: pure stdlib + a hand-rolled KM estimator. No new dependency (not even numpy required).**

- **Why this over `lifelines`:** this project runs unattended solver sweeps for *days* while the user
  is away (see autonomous-sweep tooling). Environment fragility is a real cost here — a missing/
  mis-pinned package surfacing mid-sweep is exactly the failure mode to avoid. KM + Greenwood is
  ~40 lines and uses only `math`/`csv`/`statistics`, matching the existing `analyze_collection_cap.py`
  footprint (it imports only stdlib). The estimator is textbook and easy to review.
- **What we gave up:** `lifelines.KaplanMeierFitter` would make this a 3-line call with plotting and
  log-log CIs for free. If the team later wants survival *regression* (Cox/AFT to compare worker
  counts or configs — item deferred), revisit `lifelines` then; for a single-group KM it is not worth
  the dependency.

### KM math spec (drop-in)

Input: each run is `(time, event)` where `event = 1` if feasible (the event "found a feasible seed"
occurred at `time`), `event = 0` if censored (capped; we only know time-to-feasible > `time`).

```python
def kaplan_meier(observations):
    """
    observations: list of (time, event) with event in {0,1} (1 = feasible found, 0 = censored).
    Returns a list of (t, S_t, var_greenwood) at each DISTINCT EVENT time, plus the max observed
    time (the horizon past which S is undefined). Standard KM with Greenwood variance.
    """
    obs = sorted(observations, key=lambda x: x[0])
    n_total = len(obs)
    times = sorted({t for (t, e) in obs if e == 1})   # distinct EVENT times only
    horizon = max((t for (t, _) in obs), default=0.0)
    S = 1.0
    cum_var_sum = 0.0     # running sum d_i / (n_i (n_i - d_i)) for Greenwood
    curve = []
    for t in times:
        n_i = sum(1 for (tt, _) in obs if tt >= t)            # at risk just before t
        d_i = sum(1 for (tt, e) in obs if tt == t and e == 1) # events at t
        if n_i == 0:
            break
        S *= (1.0 - d_i / n_i)
        if n_i - d_i > 0:
            cum_var_sum += d_i / (n_i * (n_i - d_i))
        var = (S * S) * cum_var_sum                            # Greenwood's formula
        curve.append((t, S, var))
    return curve, horizon
```

**Confidence band:** use the **log-log transform** for the CI (keeps it inside [0,1], standard for KM
at the tails):

```python
def km_ci_at(S_t, var_t, z=1.96):
    """log-log 95% CI for S(t). Returns (lo, hi). Degenerate-safe."""
    if S_t <= 0.0 or S_t >= 1.0 or var_t <= 0.0:
        return (max(0.0, S_t), min(1.0, S_t))
    se_logS = math.sqrt(var_t) / S_t            # delta-method SE of log S
    c = z * se_logS / math.log(S_t)             # log-log pivot
    lo = S_t ** math.exp(+abs(c))
    hi = S_t ** math.exp(-abs(c))
    return (max(0.0, lo), min(1.0, hi))
```

### Cost-vs-cap curve from KM (the decision output)

`p(C) = 1 − S(C)`. Expected seconds per feasible entry, computed directly from the KM curve and the
censored mass, for any candidate `C ≤ horizon`:

```python
def cost_at_cap(curve, horizon, C):
    """
    cost(C) = E[seconds spent per feasible entry at cap C]
            = (expected seconds per RUN at cap C) / p(C),
    where a run contributes its event time if it finishes <= C, else C (censored at the cap).
    Uses the KM step function for the event-time distribution. Refuses C > horizon.
    """
    if C > horizon:
        return None  # unidentifiable — KM auto-enforces Bug A
    # p(C) = 1 - S(C): S is right-continuous step; take S at the largest event time <= C.
    S_C = 1.0
    for (t, S, _) in curve:
        if t <= C: S_C = S
        else: break
    pC = 1.0 - S_C
    if pC <= 0: return float('inf')
    # E[min(T, C)] = integral_0^C S(t) dt  (standard expected-value-of-censored-time identity).
    # Approximate the integral over the KM step function up to C.
    area, prev_t, prev_S = 0.0, 0.0, 1.0
    for (t, S, _) in curve:
        seg_end = min(t, C)
        area += prev_S * (seg_end - prev_t)
        prev_t, prev_S = seg_end, S
        if t >= C: break
    if prev_t < C:                      # tail from last event time to C at current S
        area += prev_S * (C - prev_t)
    mean_run = area                     # = E[min(T, C)]
    return mean_run / pC
```

**Output the tool should print:**
- The KM curve as a small table: `t | S(t) | 95% CI` at each event time.
- `p(C)`, `mean s/run`, `cost s/entry` (with CI via bootstrap-over-KM or Greenwood-propagated) for
  each candidate `C ≤ horizon`; `UNIDENTIFIABLE` for `C > horizon`.
- The **continuous cost minimum**: scan `C` from min event time to horizon in ~1s steps, report the
  `C*` minimizing `cost_at_cap`. This replaces "guess from a candidate list" with "read off the curve."
- The **hazard interpretation note**: report whether the per-interval hazard `d_i / n_i` is decreasing
  (late runs increasingly hopeless → cap aggressively) or roughly flat (a run still going at 150s is
  just unlucky → a longer cap may still pay). One sentence; it's the "doomed vs unlucky" distinction
  the binary grid cannot make.

### Non-informative censoring — state the assumption
KM assumes censoring is independent of the would-be event time. **It holds here** because the cap is a
fixed wall-clock independent of search state (a run hitting the cap tells us nothing about its
would-be finish beyond "> cap"). Put this as a one-line assumption note in the tool's docstring so it's
auditable.

### PR2 verification
- Feed the `fjportfolio` rows from `phase0_fix_results.csv` (all feasible, 14.8–202.5s) plus the
  `mono` censored rows (300s) as a known mixed-censoring input; confirm `S(t)` is monotone
  non-increasing, `horizon=300`, and `cost_at_cap` refuses C>300.
- Cross-check against PR1: at caps ≤ horizon, KM's `p(C)` should match the grid's `p(C)` to within
  sampling (they estimate the same thing; KM just uses censored runs correctly).
- Pure stdlib; no DB, no Java.

---

## PR3 — Nearest-neighbor Hamming distance as the saturation monitor

**This replaces "watch the duplicate rate."** Confirmed against code: dedup is **exact-fingerprint
string equality** ([`fingerprint`](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L1491-L1495)
compared via `.equals` at [line 1459](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L1459)).
With ~900 placements, two independent searches producing a *bit-identical* assignment is
astronomically unlikely, so `delta=0` (exact re-discovery) will read ~0% essentially forever and then
jump — useless in the window that matters. The honest saturation signal is **geometric packing**: are
new seeds landing *close* to existing ones (small nearest-neighbor Hamming distance), even when not
identical? That trends down long before any exact duplicate appears.

**The machinery already exists.** [`hammingPlacements`](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L1762)
computes the symmetric-difference size between two assignments, and `auditPool` already runs an
all-pairs diversity report ([lines 1729-1744](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L1729-L1744)).
PR3 reuses `hammingPlacements`; it does **not** add new distance math.

### Spec

**At collection time** — in [`saveCachedFeasibleHints`](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L1453),
*before* appending the new seed (after the dedup check passes, i.e. after
[line 1464](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L1464), before
[line 1471](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L1471)), compute the new
seed's distance to its **nearest** existing pool member and persist it:

```java
// Saturation monitor: distance from this NEW seed to its closest existing pool member.
// Exact-dedup re-discovery (delta=0) is a near-useless saturation signal at ~900 placements;
// shrinking nearest-neighbor distance is the real "packing" signal. Cheap; reuses hammingPlacements.
int nnDist = Integer.MAX_VALUE;
for (Map<String, Long> existing : pool) {
    nnDist = Math.min(nnDist, hammingPlacements(found, existing));
}
if (pool.isEmpty()) nnDist = -1;  // first seed has no neighbor
```

**Where to store it (locked: distance is a per-collection-event fact, not a per-seed fact).** Add it
to the seed's stats row at registration — it is the seed's "distance-to-pool-at-insertion," a fixed
historical fact. Extend `phase0_seed_stats` with one additive column and pass the value through
`ensureSeed`:

- Schema (in [`DatabaseManager.java`](src/main/java/com/residency/db/DatabaseManager.java#L276) migrations):
  add `nn_dist_at_insert INTEGER` to the `CREATE TABLE` (additive; existing rows get NULL — fine).
  For an already-created DB, add an idempotent `ALTER TABLE phase0_seed_stats ADD COLUMN
  nn_dist_at_insert INTEGER` guarded by a "column exists?" check (PRAGMA table_info), in the same
  migration list.
- DAO ([`Phase0SeedStatsDAO.ensureSeed`](src/main/java/com/residency/db/Phase0SeedStatsDAO.java#L22)):
  add an `int nnDistAtInsert` parameter; write it in the INSERT. Keep the no-op-if-present behavior.
- Engine call site ([line 1480](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L1480)):
  pass `nnDist`.

**Why store on the seed row, not a new table:** the value is computed exactly once, at insertion, and
never changes; it is naturally one-per-seed. A separate collection-runs table (proposed earlier) is
still worth having for *run-level* telemetry (cap used, status, secs), but that is a larger change —
keep PR3 minimal and additive. (Flagged as optional follow-up below.)

**Analysis (read-only, Python, can ship with PR3 or follow):** a tiny script that reads
`nn_dist_at_insert` ordered by `ordinal` (insertion order) and reports the trend — e.g. the slope of
nearest-neighbor distance vs. ordinal, or a simple moving median. **Downward trend = the search is
crowding into already-covered basins = saturation onset.** No threshold is set or acted on yet (see
gate below).

> **Backfill / NULL handling (confirmed):** [`ensureSeed`](src/main/java/com/residency/db/Phase0SeedStatsDAO.java#L22)
> is idempotent — it no-ops if the seed already exists. So seeds that entered the pool **before** PR3
> (the ~8 originals + anything banked before this column existed) will have `nn_dist_at_insert = NULL`,
> because there is no way to reconstruct their distance-to-pool-at-insertion after the fact (insertion
> order context is gone). That is correct — do NOT backfill a fake value. **Requirement:** the trend
> script must `WHERE nn_dist_at_insert IS NOT NULL` (skip NULLs) and the first post-PR3 seed will also
> be `-1` (no neighbor) — skip both. The trend is only meaningful over seeds banked after the monitor
> went live; that is fine, since saturation is a forward-looking signal.

### The open sub-question — preserved, NOT resolved here
What distance counts as "too close to bother banking" is a **domain judgment** (two schedules 4
placements apart may be operationally identical or meaningfully different depending on which
placements), and it **interacts with the quality question**: if the ICC test (item 6) shows seed
identity does *not* predict final quality, then near-duplicate seeds don't matter and the whole
saturation worry is moot. **Discipline: wire up the monitor cheaply now (data is half-computed), but
do not set a rejection threshold or change collection behavior until the ICC result is in.** PR3 only
*logs* the distance; it must not reject or alter any seed.

### Chao1 / Good–Turing — demote (item 8)
Capture-recapture needs *recaptures* (doubletons). Under exact-match dedup there will be no doubletons
for a very long time, possibly never in practical use. Species-counting assumes discrete
interchangeable species; a ~900-dim near-continuous assignment space is a packing problem, not a
species problem. **Do not build Chao1/Good–Turing.** The nearest-neighbor distance monitor supersedes
it. (Keep the one-line rejection-count log if trivial, but expect it un-fittable.)

### PR3 verification
- Unit-level: insert two known assignments differing in exactly k placements; assert
  `nn_dist_at_insert` of the second = k. First seed records -1.
- Run a short collection (against a DB copy) and confirm the column populates in insertion order with
  plausibly large distances (early seeds far apart).
- Confirm default behavior unchanged: the value is logged only; no seed is rejected on distance.

---

## PR4 — Lexicographic reward + fix the "three independent `best_*`" schema bug

**Two confirmed problems in the reward record:**

1. **Schema bug (confirmed):** [`recordOutcome`](src/main/java/com/residency/db/Phase0SeedStatsDAO.java#L78)
   updates `best_tier1`, `best_tier2`, `best_tier3` **independently** (each takes its own min,
   lines 81-83). So a seed's `best_tier1` and `best_tier2` may come from **different runs** — they do
   not correspond to one schedule. Anyone later reading "the seed's best result" as the triple
   `(best_tier1, best_tier2, best_tier3)` gets a Frankenstein schedule that never existed. For
   *telemetry* (what's the lowest T1 ever seen) the independent minima are fine; for *ranking seeds*
   (item 7) they are wrong.

2. **Reward metric undecided (locked decision):** reward is **lexicographic (Tier-1, then Tier-2, then
   Tier-3)**. The tiers are already a priority order; lexicographic comparison matches how the solver
   orders objectives and avoids inventing arbitrary composite weights. Tier-1 is the primary outcome;
   lower tiers break ties.

### Spec

**Store the best *run* as a coherent triple**, in addition to (or instead of) the independent minima.
Add three columns that move together, set only when a new run is lexicographically better than the
stored best:

- Schema: add `best_run_tier1 INTEGER, best_run_tier2 INTEGER, best_run_tier3 INTEGER` to
  `phase0_seed_stats` (additive; same ALTER-with-guard pattern as PR3). Keep the existing
  `best_tier1/2/3` columns as-is for telemetry (lowest-ever-per-tier) — they are not wrong, just
  not a schedule; relabel them in comments as "per-tier minima (telemetry, NOT one schedule)".

- `recordOutcome` ([Phase0SeedStatsDAO.java:78](src/main/java/com/residency/db/Phase0SeedStatsDAO.java#L78)):
  add the lexicographic-better update. Pure SQL `CASE` keeps it atomic:

  ```sql
  -- Lexicographic "best run": (t1, t2, t3) replaces the stored best iff it is lexicographically
  -- smaller. NULL stored best (no run yet) is always replaced.
  best_run_tier1 = CASE WHEN best_run_tier1 IS NULL
        OR ? < best_run_tier1
        OR (? = best_run_tier1 AND ? < best_run_tier2)
        OR (? = best_run_tier1 AND ? = best_run_tier2 AND ? < best_run_tier3)
      THEN ? ELSE best_run_tier1 END,
  -- best_run_tier2 and best_run_tier3 use the SAME predicate (so all three move together):
  best_run_tier2 = CASE WHEN <same predicate> THEN ? ELSE best_run_tier2 END,
  best_run_tier3 = CASE WHEN <same predicate> THEN ? ELSE best_run_tier3 END,
  ```

  Bind the lexicographic predicate's parameters identically for all three columns so they update as a
  unit. (Implementer's choice: doing the comparison in Java — read current best, compare tuples,
  conditionally UPDATE — is simpler to read than the triplicated SQL predicate and is acceptable since
  this runs once per full solve, not in a hot loop. Either is fine; the requirement is that the three
  `best_run_*` columns always reflect ONE run.)

- Keep `sum_tier1/2/3` and `runs_scored` exactly as they are — they correctly support per-tier means
  for the ICC test (item 6). Do not change them.

**Caller note (no change required now):** the call site at
[CpSatSchedulerEngine.java:1014-1026](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L1014-L1026)
already passes `(t1, t2, t3)` from one solution — so the triple it hands in *is* coherent. The bug is
purely in how the DAO stored it. After PR4, the same call now also populates `best_run_*` correctly.
Note `t3` there is `max(0, -objectiveValue)` (a Phase-3 objective proxy, line 1018) — fine as the
tie-break tier; document that `best_run_tier3` is that proxy, not a raw count.

> **CRITICAL behavioral gate (confirmed in code):** `recordOutcome` fires only when
> `runSeedId != null` ([line 1014](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L1014)),
> and `runSeedId` is set **only inside the cache-hit branch** at
> [line 710](src/main/java/com/residency/cpsat/CpSatSchedulerEngine.java#L710) — i.e. only when the
> run was launched with `PHASE0_FIX=cache` **and** a pooled seed was actually selected (cache hit).
> A normal **cold** run records NOTHING. **Consequence:** for the item-6 ICC data to ever accumulate,
> the full 4-phase runs MUST be launched with `PHASE0_FIX=cache` so they warm-start from (and are
> attributed to) a tracked seed. No matter how many cold full solves run, `best_run_*`, `sum_*`, and
> `runs_scored` will stay empty. Whoever runs the eventual ICC data collection must set
> `PHASE0_FIX=cache` (and, to spread runs across seeds, `PHASE0_SEED_SELECT=roundrobin`). This is a
> launch-config requirement, not a code change — but it is the difference between the reward record
> filling up and silently staying empty.

### PR4 verification
- Record two runs for one seed: run X = (T1=2, T2=9, T3=100), run Y = (T1=2, T2=5, T3=400). Assert
  `best_run_*` = Y's triple (2,5,400) because Y is lexicographically smaller (tie on T1, lower T2),
  even though X has a lower T3. Assert the legacy per-tier minima are `best_tier1=2, best_tier2=5,
  best_tier3=100` (each independent min) — demonstrating exactly why the independent minima ≠ one run.
- Confirm additive/reversible: dropping the new columns returns to prior behavior.

---

## BLOCKED items — specified, do not build until the gate opens

### Item 6 — ICC / variance-decomposition: "does seed identity predict final quality?"
**This gates everything downstream (items 7).** It is the confirmatory test the whole exploit/prune
question hinges on. Build ONLY when reward data exists.

- **Gate / required data:** full 4-phase runs with recorded Tier outcomes, **replicated**: roughly
  **≥5 runs each on ≥10 distinct seeds** (the solver's Phases 1–3 are themselves stochastic, so each
  seed needs multiple runs to separate between-seed variance from within-seed noise). Until then ICC
  is not estimable. Currently the reward columns are essentially empty (only Phase-0 collection has
  run) → BLOCKED.
- **How the data must be collected (do not skip):** the reward record only populates on
  `PHASE0_FIX=cache` cache-hit runs (see the CRITICAL behavioral gate in PR4). To produce the ICC
  dataset, launch the full 4-phase runs with `PHASE0_FIX=cache PHASE0_SEED_SELECT=roundrobin` so each
  run warm-starts from a tracked seed and the runs spread across seeds (≈5 passes through a ≥10-seed
  pool gives the ≥5×≥10 replication). Cold runs contribute nothing to ICC.
- **Model:** one-way **random-effects ANOVA** (a.k.a. variance-components / mixed model) with seed as
  the grouping factor and per-run **Tier-1** as the outcome (the primary reward; lower tiers as
  secondary analyses). Report the **intraclass correlation** ICC = between-seed variance /
  (between-seed + within-seed variance).
  - ICC ≈ 0 → Phases 1–3 wash out the start. Seed tracking is pure telemetry; **do NOT build item 7.**
  - ICC meaningfully > 0 → the warm-start basin is consequential; item 7 (racing) is justified.
- **Confirmatory test:** likelihood-ratio test of the seed random-effect vs. a null pooled model
  (a clean yes/no in the clinical-trial idiom). Report a CI on the ICC.
- **Tooling note when built:** this is the one place a stats package earns its keep
  (`statsmodels` MixedLM, or compute the components by hand from the ANOVA mean-squares). Decide deps
  at build time, when the data volume is known.

### Item 7 — Racing / best-arm identification (NOT UCB)
**Gate:** item 6 returns ICC > 0. Until then there is no established signal to exploit → BLOCKED.

- **Why racing, not UCB/Thompson:** pulls are *expensive* (a full multi-minute 4-phase solve) and
  the operational guardrail is "never retire a seed on noise." Regret-minimizing bandits (UCB/Thompson)
  optimize cumulative regret and will abandon arms fast — the wrong objective. The right sub-field is
  **best-arm identification / pure exploration**: **Successive Halving / Sequential Halving** or
  **racing (Hoeffding / empirical-Bernstein races)**, which eliminate clearly-inferior seeds with a
  CI-separation rule and a fixed evaluation budget — the same CI-separation discipline already in the
  cap analysis and the plan's prune guardrail.
- **Powering gate before any prune/exploit:** require a minimum runs-per-seed and a CI on each seed's
  reward; only favor/retire a seed when its reward CI is *separated* from the field — never on a
  handful of runs.
- Thompson sampling is acceptable LATER, only for large-pool *online* exploit once best-arm
  identification has earned the right to exploit; prefer it over UCB in the small-sample regime
  (less brittle to the tuning constant).

### Item 8 — Chao1 / Good–Turing
**Do not build (see PR3).** No doubletons under exact dedup; superseded by the nearest-neighbor
distance monitor.

---

## Optional follow-ups (not required; flagged for completeness)
- **Per-run collection telemetry table** `phase0_collection_runs(run, cap_secs, status, secs,
  new_seed_id, worker_count, started_at)` — makes the cap analysis durable/queryable and records the
  explicit censoring cap (instead of inferring it from the ~181 clustering) and the worker/RNG seed
  (so stationarity can be *verified*, not just assumed). Worth doing before the pool gets large; not
  needed for PR1–PR4.
- **Stationarity check:** once ~20+ collection runs exist, regress `phase0_secs` on run-index; a flat
  slope *confirms* the "cap valid forever" assumption the whole collection strategy rests on. ~5 lines.

---

## Recommended landing order
1. **PR1** (Python bug fixes) — smallest, highest confidence, unblocks honest cap reporting today.
2. **PR2** (KM tool) — the headline rigor upgrade; can run against the same CSVs immediately.
3. **PR3** (distance monitor) — additive Java/DB; cheap; corrects the saturation signal before the
   pool grows enough to matter.
4. **PR4** (lexicographic reward + schema fix) — additive Java/DB; fixes the reward record before the
   first full runs start writing into it (so the data accumulates correctly from run #1).
5. **Then collect full-run reward data** until the item-6 gate (≥5×≥10) opens; run the ICC test;
   build item 7 only if ICC > 0.
