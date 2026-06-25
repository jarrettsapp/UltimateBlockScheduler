#!/usr/bin/env python3
"""Empirical phase-budget calibration: size [P0,P1,P2,P3] from data, not guesswork.

Reads (READ-ONLY) the per-phase timings in `solve_runs` and the Phase-3 trajectory in
`solve_run_trajectory` over a small post-wiring calibration batch, and reports per phase:

  P0 : observed Phase-0 seconds WITH a seed (expect near-zero once seed-wiring is live).
  P1 : Phase-1 convergence seconds (expect single-digit seconds).
  P2 : Phase-2 convergence seconds (expect seconds).
  P3 : the OBJECTIVE PLATEAU — the elapsed_s past which the incumbent stops improving. This is
       the existing "size budgets from the plateau, not the branches" principle, automated and
       cumulative. The recommended P3 budget is plateau + a margin so a re-run reliably reaches
       the same incumbent in less wall-clock.

Output: a recommended [P0,P1,P2,P3] with the evidence (each phase's observed spread across the
batch, and the P3 plateau time + its spread across repeats). Feed P3 into sweep_driver.DEFAULT_BUDGET.

The plateau per run = the elapsed_s of the LAST incumbent that improved the objective by more than
`--eps` over the previous one; runs agree on a plateau when their last-improvement times cluster.

Usage:  python analyze_budget_calibration.py [DB_PATH] [DATA_EPOCH] [--eps E] [--margin-frac F]
        DB_PATH       default residency_scheduler.db   (opened mode=ro)
        DATA_EPOCH    default post_fix_seeded
        --eps         min objective improvement to count as "still improving" (default 0.5)
        --margin-frac P3 budget = ceil(plateau_p90 * (1+F)); default F=0.25
"""
import sqlite3, sys, math, argparse


def ro_connect(db_path):
    return sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)


def pct(xs, p):
    """Nearest-rank percentile of a list (p in [0,100]); xs assumed non-empty."""
    s = sorted(xs)
    return s[min(len(s) - 1, int(p / 100 * len(s)))]


def summarize(name, xs):
    if not xs:
        print(f"  {name:<4} no data")
        return None
    print(f"  {name:<4} n={len(xs):<3} min={min(xs):.1f}s  median={pct(xs,50):.1f}s  "
          f"p90={pct(xs,90):.1f}s  max={max(xs):.1f}s")
    return pct(xs, 90)


def plateau_time(traj, eps):
    """Given a run's trajectory rows [(elapsed_s, objective), ...] (sorted), return the elapsed_s of
    the last incumbent that improved the objective by > eps. None if no rows."""
    if not traj:
        return None
    traj = sorted(traj)
    last_improve = traj[0][0]
    prev_obj = traj[0][1]
    for elapsed, obj in traj[1:]:
        if prev_obj is not None and obj is not None and (prev_obj - obj) > eps:
            last_improve = elapsed
        prev_obj = obj
    return last_improve


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db_path", nargs="?", default="residency_scheduler.db")
    ap.add_argument("epoch", nargs="?", default="post_fix_seeded")
    ap.add_argument("--eps", type=float, default=0.5)
    ap.add_argument("--margin-frac", type=float, default=0.25)
    a = ap.parse_args()

    conn = ro_connect(a.db_path)
    c = conn.cursor()

    if a.epoch == "*":
        run_rows = c.execute(
            "SELECT id, p0_secs, p1_secs, p2_secs, p3_secs FROM solve_runs WHERE feasible = 1"
        ).fetchall()
    else:
        run_rows = c.execute(
            "SELECT id, p0_secs, p1_secs, p2_secs, p3_secs FROM solve_runs "
            "WHERE feasible = 1 AND data_epoch = ?", (a.epoch,)
        ).fetchall()

    print(f"=== Phase-budget calibration  (epoch={a.epoch}) ===")
    if not run_rows:
        print("No feasible solve_runs rows yet — run a small calibration batch first, then re-run.")
        return

    p0 = [r[1] for r in run_rows if r[1] is not None]
    p1 = [r[2] for r in run_rows if r[2] is not None]
    p2 = [r[3] for r in run_rows if r[3] is not None]
    p3 = [r[4] for r in run_rows if r[4] is not None]

    print(f"\nObserved per-phase wall time over {len(run_rows)} feasible runs:")
    p0_p90 = summarize("P0", p0)
    p1_p90 = summarize("P1", p1)
    p2_p90 = summarize("P2", p2)
    summarize("P3", p3)

    # Phase-3 plateau from the trajectory, per run.
    plateaus = []
    for (run_id, *_rest) in run_rows:
        traj = c.execute(
            "SELECT elapsed_s, objective FROM solve_run_trajectory WHERE run_id = ? ORDER BY elapsed_s",
            (run_id,)
        ).fetchall()
        pt = plateau_time(traj, a.eps)
        if pt is not None:
            plateaus.append(pt)

    print(f"\nPhase-3 objective plateau (last improvement > eps={a.eps}):")
    if plateaus:
        print(f"  n={len(plateaus)}  median={pct(plateaus,50):.1f}s  p90={pct(plateaus,90):.1f}s  "
              f"max={max(plateaus):.1f}s")
        plateau_p90 = pct(plateaus, 90)
    else:
        print("  no trajectory rows — P3 recommendation falls back to observed p3 p90")
        plateau_p90 = pct(p3, 90) if p3 else None

    # Recommendation: each phase = ceil(p90 * (1+margin)); minimum sane floors so a phase never
    # gets a 0s budget from a near-instant observation.
    def rec(p90, floor):
        if p90 is None:
            return floor
        return max(floor, int(math.ceil(p90 * (1 + a.margin_frac))))

    rp0 = rec(p0_p90, 30)     # seed validation: seconds, keep a small floor
    rp1 = rec(p1_p90, 60)
    rp2 = rec(p2_p90, 60)
    rp3 = rec(plateau_p90, 300)

    print(f"\nRECOMMENDED BUDGET  [P0,P1,P2,P3] = [{rp0},{rp1},{rp2},{rp3}]")
    print(f"  (each = ceil(p90 * {1+a.margin_frac:.2f}) with sane floors; P3 sized from the plateau.)")
    print(f"  Feed P3={rp3} into sweep_driver.DEFAULT_BUDGET and the queue 'budget' field.")
    if plateaus and len(plateaus) < 3:
        print("  POWER: <3 trajectories — treat the plateau as provisional; collect a few more repeats.")


if __name__ == "__main__":
    main()
