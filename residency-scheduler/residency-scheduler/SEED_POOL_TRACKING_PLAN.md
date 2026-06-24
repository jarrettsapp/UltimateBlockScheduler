# Seed Pool — Per-Seed Tracking & Selection Plan

Status: **tracking + coverage-first selection = TO IMPLEMENT now.** Exploit/prune = **planned, deferred** (gated on data proving seed→quality correlation is real).

## Why
The Phase-0 cache pool (`PHASE0_FIX=cache`) is currently a bag we draw from at RANDOM each run.
That treats every feasible seed as interchangeable. The user's insight: seeds are NOT
necessarily interchangeable — the feasible basin a run starts in may bias the final
(Phase 1–3) schedule quality. So we want to:
1. Know each seed's **track record** (how often it's been used to start a full run, and how
   good the resulting schedules were).
2. Steer selection: early on, **cover every seed fairly** (coverage-first / round-robin);
   later, **exploit** the seeds that lead to good schedules and **prune** the ones that don't.

This converts the pool from "random warm-start" into an **instrumented portfolio of starting
points with measured outcomes** — effectively a search over starting basins.

## The open empirical question (decides whether exploit/prune is worth building)
**Does the starting seed actually predict final schedule quality?**
- If YES (seed identity correlates with final Tier scores): exploit/prune pays off hugely —
  we're searching over starting points and should concentrate effort on good ones.
- If NO (Phases 1–3 wash out the start): random replay is already optimal; tracking is just
  telemetry.
We do NOT know yet. So: build TRACKING first (cheap, always useful), MEASURE the correlation
via the quality A/B + per-seed records, then build exploit/prune ONLY if the correlation holds.

## Lifecycle (the three selection policies = phases of one lifecycle)
A classic **multi-armed bandit**: each seed = an "arm", reward = final schedule quality.
1. **Coverage-first (exploration)** — pull every arm a fair number of times before judging any.
   = round-robin / "knock them out one by one / use unused seeds first". **IMPLEMENT NOW.**
2. **Exploit-the-winners (exploitation)** — bias toward seeds with the best track record.
   **DEFERRED.**
3. **Prune-the-losers** — demote/retire seeds whose reward is confidently below the field.
   **DEFERRED.** Guardrail: a feasible seed is EXPENSIVE to collect (15–180s, sometimes a
   capped 300s miss) — never retire a seed on a handful of runs. Apply a **powering model**
   (like the cap analysis): require N full runs per seed with a confidence interval on its
   reward before any prune/exploit decision. (UCB / Thompson-sampling formalize explore→exploit
   with the statistics built in; same math as the clinical NNT/powering discipline.)

## DECISIONS LOCKED (user, 2026-06-24)
- **Seed IDs must scale to THOUSANDS of seeds** (project may grow large). → IDs are not tied to
  pool position; they are stable, unique, and cheap to index at that scale.
- **Coverage-first = sequential round-robin**: go through each seed in order, using
  least-recently / least-used first, so unused seeds are consumed before any seed repeats.
- **Exploit + prune: plan only, do not implement yet.**
- **Reward metric ("best"): TBD** — must decide whether "best" = lowest Tier-1 (hard clinical
  quality) or a blended Tier-1/2/3 score. This defines what the whole system optimizes for.
  (Open question to resolve before exploit/prune; tracking will RECORD all three tiers so we
  can decide later without re-collecting.)

## DATA MODEL (to implement)

### Seed identity
- Each pooled assignment gets a **stable seed ID**. Use the existing occupancy **fingerprint**
  (sorted set of "var=1" placements) as the natural key — it's already computed, content-based
  (so the same schedule always maps to the same ID, dedup-consistent), and unique. For compact
  storage/indexing at THOUSANDS scale, store a short **SHA-256 hex of the fingerprint** as the
  `seed_id` (64 hex chars, collision-free in practice), plus an optional human-friendly
  **ordinal** (1,2,3,…) assigned at insertion for readability. Ordinal is display-only; the
  hash is the key (so IDs never collide or get reused even across millions of seeds).

### New DB table (planned schema — additive, reversible)
```
CREATE TABLE IF NOT EXISTS phase0_seed_stats (
    seed_id        TEXT PRIMARY KEY,   -- sha256(fingerprint); scales to thousands+
    ordinal        INTEGER,            -- human-friendly 1..N, display only
    year           INTEGER NOT NULL,
    created_at     TEXT NOT NULL,      -- when first cached
    times_started  INTEGER NOT NULL DEFAULT 0,  -- # full runs that began from this seed
    last_used_at   TEXT,               -- for least-recently-used round-robin
    best_tier1     INTEGER,            -- best (lowest) Tier-1 seen from this seed
    best_tier2     INTEGER,
    best_tier3     INTEGER,
    sum_tier1      INTEGER NOT NULL DEFAULT 0,   -- running totals for averages
    sum_tier2      INTEGER NOT NULL DEFAULT 0,
    sum_tier3      INTEGER NOT NULL DEFAULT 0,
    runs_scored    INTEGER NOT NULL DEFAULT 0    -- # runs with a recorded Tier outcome
);
CREATE INDEX IF NOT EXISTS idx_seed_stats_lru ON phase0_seed_stats(year, times_started, last_used_at);
```
- `times_started` + `last_used_at` drive **coverage-first** selection (pick least-used, oldest).
- `best_*` / `sum_*` / `runs_scored` are the **reward record** for later exploit/prune; populated
  by full 4-phase runs (the quality A/B and production runs), NOT by Phase-0-only collection.
- Additive table, no change to existing schema → fully reversible (drop table = back to random).

## SELECTION MECHANIC (to implement)
Replace the single random draw in `loadCachedFeasibleHints` with a **selection mode** env knob:
- `PHASE0_SEED_SELECT=random`     — current behavior (default; unchanged).
- `PHASE0_SEED_SELECT=roundrobin` — **coverage-first**: pick the seed with the lowest
  `times_started`, breaking ties by oldest `last_used_at` (least-recently-used). Increments
  `times_started` + sets `last_used_at` on selection. Guarantees every seed is used before any
  repeat, then cycles fairly. **IMPLEMENT NOW.**
- `PHASE0_SEED_SELECT=specific:<id>` — force a named seed (for deliberate iteration on one seed).
  **planned.**
- `PHASE0_SEED_SELECT=weighted`    — bias by track record (exploit). **deferred.**

## OUTCOME FEEDBACK (to implement minimally now, fully later)
A full 4-phase run that started from seed S must, on completion, record S's final Tier-1/2/3
into `phase0_seed_stats` (update best_*, sum_*, runs_scored). For NOW: record the outcome so the
data accumulates from the first full runs onward (cheap, no behavior change). The exploit/prune
LOGIC that consumes it is deferred.

## IMPLEMENTATION ORDER
1. **[NOW] Seed IDs + stats table** — assign `seed_id` (sha256 of fingerprint) + ordinal on
   cache insert; create `phase0_seed_stats`; record created_at. Backfill existing pool entries.
2. **[NOW] Coverage-first selection** — `PHASE0_SEED_SELECT=roundrobin`; LRU/least-used pick;
   update times_started + last_used_at on use.
3. **[NOW, minimal] Outcome recording** — on a full 4-phase run, write final Tier scores back to
   the seed's row (so reward data starts accumulating).
4. **[DEFERRED] Reward metric decision** (Tier-1 vs blended) — needed before exploit/prune.
5. **[DEFERRED] Exploit + prune policies** — powered by the accumulated records, with a
   per-seed sample-size / CI gate before any seed is favored or retired.
6. **[DEFERRED] PoolAudit extension** — show per-seed usage + reward summary in the audit report.

## GUARDRAILS (carry over from the seeding work)
- Additive + env-gated; default `PHASE0_SEED_SELECT=random` keeps today's behavior. Reversible.
- WAL-safe DB handling (the seeding harness already checkpoints before copies).
- Never prune a seed on underpowered data (a feasible seed is expensive to collect).
- Stepwise verification before scaling, same as the seeding ladder.
```

## COLLECTION SCALING — cap stationarity vs diversity saturation (user discussion 2026-06-24)

Two DIFFERENT "efficiencies" behave differently as the pool grows. Getting this distinction
right determines what we monitor and what lever we pull when collection slows.

### 1. Time-to-find-A-feasible-seed = STATIONARY (does NOT degrade)
Each collect solve is an INDEPENDENT cold Phase-0 search with a fresh random seed; the solver
has no memory of prior runs and the model is identical every time. So the expected time to find
*a* feasible point is the SAME at run #2 and run #2000. **Consequence:** once we power the
collection cap (Step 4b → n≈55), that cap stays valid for collection essentially forever — we do
NOT need to re-derive it as the pool grows. (User's insight, correct.)

### 2. Time-to-find-a-NEW (not-yet-cached) seed = DEGRADES with pool size
The solver draws feasible points from an underlying distribution. Early on nearly every draw is
new. As the pool fills, the solver increasingly RE-DISCOVERS points we already have; dedup
correctly rejects them (`delta 0` on an `OPTIMAL` run). So even though each SOLVE costs the same,
the cost per NEW seed climbs. This is **diversity saturation** — the real scaling limit, not
solve time.

### What to MONITOR (the right metric)
Not time-to-feasible (stationary). Watch the **DUPLICATE RATE = fraction of `OPTIMAL` runs with
`delta 0`.** Rising duplicate rate = the feasible space near our search is being exhausted.
As of n=35+ runs / 34 seeds: duplicate rate = 0% (feasible space vast relative to pool). Expect
this to stay ~0 for a long while, then rise.

### What to ADJUST when saturation appears (the right lever — NOT the cap)
When duplicates start climbing, the fix is **seed-exclusion constraints**, not a longer cap:
add the existing pooled assignments as FORBIDDEN solutions (constraints banning re-finding them)
so the search is forced into NEW territory. This is a COLLECTION-TIME model change (distinct from
the time cap, which only controls when a solve gives up). Note: a longer cap does NOT fix
saturation — the solve still keeps landing on the same easy basins, just with more time.

User's "skip already-used seeds" lever shows up in BOTH halves of the system, applied to
different problems:
- **Collection:** exclude already-FOUND seeds (forbid constraints) → keeps finding NEW seeds when
  saturation hits. (Deferred — only needed once duplicate rate climbs.)
- **Usage:** skip already-USED seeds when replaying for real runs = the coverage-first round-robin
  already implemented (`PHASE0_SEED_SELECT=roundrobin`).

### Cap-confirmation note (documented, deferred)
The powered cap is derived RETROACTIVELY from 180s-cap data (we know each run's actual
time-to-feasible, so we can exactly model any SHORTER cap — a cap is just a stop time and doesn't
change early-finishing runs). So a fresh run at the chosen cap is NOT statistically required.
We MAY still do a short (~10-run) confirmation at the chosen cap as cheap hygiene that reality
matches the model — optional, not required. A fresh run IS required if we change anything that
alters search behavior (worker count, portfolio config) — those shift the distribution.

### Caveat: cap optimizes THROUGHPUT, not seed QUALITY
The cap analysis minimizes seconds-per-feasible-SEED (collection throughput). It does NOT know
whether a lower cap discards slow-to-find seeds that would have led to BETTER final schedules.
Whether seed identity predicts final quality is the open question the quality A/B answers. Keep
the cap and quality questions independent — don't let an aggressive throughput cap silently trade
away schedule quality.
