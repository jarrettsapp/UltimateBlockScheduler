#!/usr/bin/env python3
"""A/B config comparison for real solves: is config B reliably better than A, across solver noise?

Reads the cumulative `solve_runs` (+ `solve_run_metrics`) tables (READ-ONLY), groups runs by
config_label, and for each metric reports a nonparametric bootstrap CI on the DIFFERENCE in means
(B − A), resampling whole runs so each config's mean carries its own variability.

DECISION RULE (mirrors the seed cap analysis): adopt B over A for a metric ONLY if the difference
CI is separated from zero (does not straddle 0). A CI that includes 0 means the observed gap is
within solver noise — do not chase it. This is the engine of "don't make blind runs": it tells the
design-solver-batch skill which knob moves actually beat the noise floor, and by how much.

The bootstrap mirrors SolveStats.bootstrapDiffCi (same percentile-index convention, same RNG-seed
determinism) so the Python report and the Java unit test agree.

Usage:  python analyze_config_compare.py CONFIG_A CONFIG_B [DB_PATH] [DATA_EPOCH]
        CONFIG_A / CONFIG_B  config_label values to compare (e.g. cfgR6-03  cfgR6-07)
        DB_PATH              default residency_scheduler.db   (opened mode=ro)
        DATA_EPOCH           default post_fix_seeded
"""
import sqlite3, sys, random

ITERS = 5000
SEED = 12345
# Metrics to compare: (column, label, table). Lower is better for all of these.
METRICS = [
    ("tier1_score", "Tier-1 score", "solve_runs"),
    ("tier2_score", "Tier-2 score", "solve_runs"),
    ("tier3_score", "Tier-3 score", "solve_runs"),
    ("volunteer", "volunteer weekends", "solve_run_metrics"),
    ("fragile", "fragile weekends", "solve_run_metrics"),
    ("healthy", "healthy weekends", "solve_run_metrics"),
    ("heavy_heavy", "heavy->heavy", "solve_run_metrics"),
    ("runs_gt6wk", "runs >6wk", "solve_run_metrics"),
    ("saturday_coverage", "Saturday Y7 coverage", "solve_run_metrics"),
]


def ro_connect(db_path):
    return sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)


def mean(xs):
    return sum(xs) / len(xs)


def bootstrap_diff_ci(a, b, iters=ITERS, seed=SEED, pct_lo=2.5, pct_hi=97.5):
    """Bootstrap CI on mean(b) − mean(a). Resamples each group's whole runs independently.
    Percentile indexing matches SolveStats: int(pct/100 * len), clamped to len-1."""
    rng = random.Random(seed)
    point = mean(b) - mean(a)

    def resample_mean(xs):
        return sum(xs[rng.randrange(len(xs))] for _ in range(len(xs))) / len(xs)

    boots = sorted(resample_mean(b) - resample_mean(a) for _ in range(iters))
    lo = boots[min(len(boots) - 1, int(pct_lo / 100 * len(boots)))]
    hi = boots[min(len(boots) - 1, int(pct_hi / 100 * len(boots)))]
    return point, lo, hi


def fetch(c, epoch, label, col, table):
    """Per-run values of `col` for runs with the given config_label (feasible only)."""
    if table == "solve_runs":
        sql = (f"SELECT {col} FROM solve_runs "
               f"WHERE config_label = ? AND data_epoch = ? AND feasible = 1 AND {col} IS NOT NULL")
        params = (label, epoch)
    else:
        sql = (f"SELECT m.{col} FROM solve_run_metrics m JOIN solve_runs r ON m.run_id = r.id "
               f"WHERE r.config_label = ? AND r.data_epoch = ? AND r.feasible = 1 AND m.{col} IS NOT NULL")
        params = (label, epoch)
    return [row[0] for row in c.execute(sql, params).fetchall()]


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    cfg_a, cfg_b = sys.argv[1], sys.argv[2]
    db_path = sys.argv[3] if len(sys.argv) > 3 else "residency_scheduler.db"
    epoch = sys.argv[4] if len(sys.argv) > 4 else "post_fix_seeded"
    conn = ro_connect(db_path)
    c = conn.cursor()

    print(f"=== A/B compare: {cfg_a} (A) vs {cfg_b} (B)   epoch={epoch} ===")
    print("Rule: adopt B for a metric only if the (B−A) CI is separated from 0 (else it's noise).\n")
    print(f"{'metric':<22} {'nA':>3} {'nB':>3} {'meanA':>8} {'meanB':>8} "
          f"{'Δ(B−A)':>9} {'95% CI':>20}  verdict")
    print("-" * 100)

    any_data = False
    for col, label, table in METRICS:
        a = fetch(c, epoch, cfg_a, col, table)
        b = fetch(c, epoch, cfg_b, col, table)
        if not a or not b:
            print(f"{label:<22} {len(a):>3} {len(b):>3}   (insufficient data)")
            continue
        any_data = True
        point, lo, hi = bootstrap_diff_ci(a, b)
        separated = (lo > 0 and hi > 0) or (lo < 0 and hi < 0)
        if not separated:
            verdict = "noise (keep A)"
        elif point < 0:
            verdict = "ADOPT B (lower)"
        else:
            verdict = "A better (lower)"
        print(f"{label:<22} {len(a):>3} {len(b):>3} {mean(a):>8.2f} {mean(b):>8.2f} "
              f"{point:>9.2f} [{lo:>7.2f},{hi:>7.2f}]  {verdict}")

    if not any_data:
        print("\nNo overlapping data for these two configs yet — run repeated solves of each first.")
    else:
        print("\nNote: 'lower is better' for every metric here. A CI straddling 0 = within solver noise.")


if __name__ == "__main__":
    main()
