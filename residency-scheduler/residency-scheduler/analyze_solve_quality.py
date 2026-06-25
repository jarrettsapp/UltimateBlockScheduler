#!/usr/bin/env python3
"""ICC analysis for real solves: does the starting seed_id predict final solution quality?

Reads the cumulative `solve_runs` table (READ-ONLY) grouped by seed_id and computes the one-way
ANOVA intraclass correlation (ICC) for each Tier score. This is the deferred empirical question
(PROJECT.md / SEED_POOL_TRACKING_PLAN.md) that GATES seed exploit/prune:

  * ICC ≈ 0  → the seed does NOT predict the outcome; solver noise dominates. Exploiting/pruning
               seeds by their past reward is not yet justified — keep round-robin collection.
  * ICC ↑ 1  → the seed strongly determines the outcome; per-seed reward is real signal and an
               exploit/prune policy is warranted.

Power: needs >=5 runs each on >=10 seeds to be meaningful. We report the actual (#seeds, runs/seed)
and flag UNDERPOWERED when below that, exactly like the seed analyses (never imply more than the
data supports).

The ICC estimator is the Java SolveStats.iccOneWay reference, re-implemented identically here so the
Python report and the Java unit test agree.

Usage:  python analyze_solve_quality.py [DB_PATH] [DATA_EPOCH]
        DB_PATH    default residency_scheduler.db   (opened mode=ro)
        DATA_EPOCH default post_fix_seeded          (the separation key; '*' = all epochs)
"""
import sqlite3, sys

MIN_SEEDS = 10
MIN_RUNS_PER_SEED = 5
TIERS = [("tier1_score", "Tier-1"), ("tier2_score", "Tier-2"), ("tier3_score", "Tier-3")]


def ro_connect(db_path):
    """Open the DB strictly read-only so this can never write while seed-gen holds it."""
    return sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)


def icc_one_way(groups):
    """One-way ANOVA ICC. groups: list of lists (each = the metric values sharing one seed).
    Mirrors SolveStats.iccOneWay: (MSB-MSW)/(MSB+(n0-1)MSW), n0 unbalanced correction, clamp [0,1].
    Returns None if <2 groups or no within-group replication."""
    groups = [g for g in groups if g]
    k = len(groups)
    n_total = sum(len(g) for g in groups)
    if k < 2 or n_total <= k:
        return None
    grand = sum(v for g in groups for v in g) / n_total
    ss_between = sum(len(g) * (sum(g) / len(g) - grand) ** 2 for g in groups)
    ss_within = sum((v - sum(g) / len(g)) ** 2 for g in groups for v in g)
    ms_between = ss_between / (k - 1)
    ms_within = ss_within / (n_total - k)
    sum_ni_sq = sum(len(g) ** 2 for g in groups)
    n0 = (n_total - sum_ni_sq / n_total) / (k - 1)
    if ms_within == 0 and ms_between == 0:
        return 0.0
    icc = (ms_between - ms_within) / (ms_between + (n0 - 1) * ms_within)
    return max(0.0, min(1.0, icc))


def main():
    db_path = sys.argv[1] if len(sys.argv) > 1 else "residency_scheduler.db"
    epoch = sys.argv[2] if len(sys.argv) > 2 else "post_fix_seeded"
    conn = ro_connect(db_path)
    c = conn.cursor()

    if epoch == "*":
        sql = ("SELECT seed_id, tier1_score, tier2_score, tier3_score FROM solve_runs "
               "WHERE seed_id IS NOT NULL AND feasible = 1")
        params = ()
    else:
        sql = ("SELECT seed_id, tier1_score, tier2_score, tier3_score FROM solve_runs "
               "WHERE data_epoch = ? AND seed_id IS NOT NULL AND feasible = 1")
        params = (epoch,)
    rows = c.execute(sql, params).fetchall()

    print(f"=== ICC: does seed_id predict final quality?  (epoch={epoch}) ===")
    if not rows:
        print("No seeded feasible solve_runs rows yet — run seed-fed solves first, then re-run.")
        return

    by_seed = {}
    for seed_id, t1, t2, t3 in rows:
        by_seed.setdefault(seed_id, []).append((t1, t2, t3))

    n_seeds = len(by_seed)
    runs_per = [len(v) for v in by_seed.values()]
    min_rps = min(runs_per)
    powered = n_seeds >= MIN_SEEDS and min_rps >= MIN_RUNS_PER_SEED
    print(f"seeds={n_seeds}  runs/seed: min={min_rps} max={max(runs_per)} "
          f"(target >={MIN_SEEDS} seeds, >={MIN_RUNS_PER_SEED} runs each)")
    print("POWER:", "OK" if powered else "UNDERPOWERED — treat ICC as provisional")
    print()

    for idx, (col, label) in enumerate(TIERS):
        groups = [[run[idx] for run in runs] for runs in by_seed.values()]
        icc = icc_one_way(groups)
        if icc is None:
            print(f"{label}: ICC n/a (need >=2 seeds with replication)")
        else:
            verdict = ("seed STRONGLY predicts outcome" if icc >= 0.5 else
                       "seed weakly predicts outcome" if icc >= 0.2 else
                       "seed does NOT predict outcome (noise dominates)")
            print(f"{label}: ICC = {icc:.3f}  → {verdict}")
    print()
    print("Interpretation: low ICC across tiers ⇒ exploit/prune NOT yet justified (keep round-robin).")


if __name__ == "__main__":
    main()
